"""Paired replay of fixed, adaptive VAD, and selective boundary repair."""
import argparse
import csv
from dataclasses import asdict, dataclass
import hashlib
from importlib.metadata import version
import json
from pathlib import Path
import platform
import sys
from time import perf_counter

if __package__ in {None, ""}:
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import jiwer
import numpy as np
from faster_whisper.vad import get_vad_model

from benchmarks.run_benchmark import load_manifest, normalize
from src.core.config import SAMPLE_RATE
from src.schemas.audio_schema import Segment
from src.services.audio_buffer import FinalizedTranscript, HOP, OVERLAP, WINDOW, Window
from src.services.stt_engine import RecognizedSegment, SttEngine, decode_audio

FRAME = 512
MIN_NEW = SAMPLE_RATE * 2
MAX_NEW = SAMPLE_RATE * 4
QUIET_FRAMES = 16  # 512 ms
QUIET_THRESHOLD = 0.35
LOOKAHEAD = SAMPLE_RATE // 2
REPAIR_HISTORY = SAMPLE_RATE
MODES = ("fixed", "vad", "vad_repair")


@dataclass(frozen=True)
class Cut:
    start: int
    end: int
    final: bool
    forced: bool = False


def fixed_cuts(length):
    start = 0
    while start + WINDOW <= length:
        yield Cut(start, start + WINDOW, False)
        start += HOP
    if start < length:
        yield Cut(start, length, True)


def vad_cuts(length, scores):
    previous = 0
    quiet = 0
    had_speech = False
    for i, score in enumerate(scores):
        end = (i + 1) * FRAME
        if end >= length:
            break
        if score < QUIET_THRESHOLD:
            quiet += 1
        else:
            had_speech = True
            quiet = 0
        natural = end - previous >= MIN_NEW and had_speech and quiet >= QUIET_FRAMES
        forced = end - previous >= MAX_NEW
        if natural or forced:
            yield Cut(max(0, previous - OVERLAP), end, False, forced and not natural)
            previous = end
            quiet = 0
            had_speech = False
    yield Cut(max(0, previous - OVERLAP), length, True)


def spans(segments, offset=0.0):
    for segment in segments:
        for word in segment.words or (Segment(start=segment.start, end=segment.end, text=segment.text),):
            yield RecognizedSegment(word.start + offset, word.end + offset, word.text)


def merge_repair(primary, candidate, low, high):
    """Keep the original hypothesis if the focused decode yields no usable words."""
    def in_band(word):
        return low < (word.start + word.end) / 2 <= high

    replacements = [word for word in candidate if in_band(word)]
    if not replacements:
        return primary, False
    original = [word for word in spans(primary) if not in_band(word)]
    merged = sorted(original + replacements, key=lambda word: (word.start, word.end))
    # Use one result per word here. The streaming merger applies the same timestamp
    # ownership and lexical deduplication as it does for the baseline hypotheses.
    return merged, True


@dataclass
class Replay:
    text: str
    inference_s: float
    delays_s: list[float]
    windows: int
    repair_calls: int
    repairs_used: int


def replay(engine, audio, mode, scores=None):
    cuts = fixed_cuts(audio.size) if mode == "fixed" else vad_cuts(audio.size, scores)
    transcript = FinalizedTranscript()
    deltas = []
    delays = []
    inference = clock = 0.0
    windows = repair_calls = repairs_used = 0
    for cut in cuts:
        windows += 1
        window = Window(cut.start / SAMPLE_RATE, audio[cut.start:cut.end], cut.final)
        clock = max(clock, cut.end / SAMPLE_RATE)
        started = perf_counter()
        hypothesis = engine.transcribe(window.samples, words=True)
        duration = perf_counter() - started
        inference += duration
        clock += duration
        if mode == "vad_repair" and cut.forced and cut.end + LOOKAHEAD <= audio.size:
            repair_calls += 1
            start = cut.end - REPAIR_HISTORY
            clock = max(clock, (cut.end + LOOKAHEAD) / SAMPLE_RATE)
            started = perf_counter()
            candidate = engine.transcribe(audio[start:cut.end + LOOKAHEAD], words=True)
            duration = perf_counter() - started
            inference += duration
            clock += duration
            hypothesis, used = merge_repair(
                hypothesis, spans(candidate, (start - cut.start) / SAMPLE_RATE),
                window.samples.size / SAMPLE_RATE - 0.75,
                window.samples.size / SAMPLE_RATE - 0.25,
            )
            repairs_used += used
        for delta in transcript.accept(window, hypothesis):
            deltas.append(delta.text)
            delays.append(max(0, clock - delta.end))
    return Replay(" ".join(deltas), inference, delays, windows, repair_calls, repairs_used)


def noisy(audio, index):
    noise = np.random.default_rng(20261106 + index).normal(0, 1, audio.size).astype(np.float32)
    scale = np.sqrt(np.mean(audio**2) / np.mean(noise**2) / 10)  # 10 dB SNR before clipping
    return np.clip(audio + scale * noise, -1, 1)


def run(args):
    engine = SttEngine(args.model, args.device, args.compute_type)
    engine.warmup()
    observations = []
    manifest = load_manifest(args.manifest)
    for index, (file, reference) in enumerate(manifest):
        with file.open("rb") as stream:
            clean = decode_audio(stream)
        conditions = [("clean", clean)]
        if index < min(args.stress, len(manifest)):
            conditions.append(("noise_10db", noisy(clean, index)))
        for condition, audio in conditions:
            padded = np.pad(audio, (0, -audio.size % FRAME))
            scores = get_vad_model()(padded).reshape(-1)
            for offset in range(len(MODES)):  # Rotate order to reduce warm-cache/order bias.
                mode = MODES[(index + offset) % len(MODES)]
                result = replay(engine, audio, mode, scores)
                errors = jiwer.process_words(reference, normalize(result.text))
                row = asdict(result)
                row.update(file=file.name, condition=condition, mode=mode,
                           audio_s=audio.size / SAMPLE_RATE, reference=reference,
                           words=errors.hits + errors.substitutions + errors.deletions,
                           substitutions=errors.substitutions, insertions=errors.insertions,
                           deletions=errors.deletions)
                observations.append(row)
        print(f"{index + 1}/{len(manifest)} {file.name}", flush=True)
    write_results(args, observations)


def paired_wer_intervals(observations):
    """Percentile bootstrap by recording, keeping each comparison paired."""
    by_mode = {
        mode: {row["file"]: row for row in observations if row["mode"] == mode and row["condition"] == "clean"}
        for mode in MODES
    }
    files = list(by_mode["fixed"])
    picks = np.random.default_rng(20261106).integers(len(files), size=(10_000, len(files)))
    words = np.array([by_mode["fixed"][file]["words"] for file in files])
    denominators = words[picks].sum(axis=1)

    def errors(mode):
        return np.array([
            sum(by_mode[mode][file][field] for field in ("substitutions", "insertions", "deletions"))
            for file in files
        ])

    return [
        {
            "comparison": f"{left} minus {right}",
            "wer_difference_ci95_percentage_points": np.quantile(
                100 * (errors(left) - errors(right))[picks].sum(axis=1) / denominators,
                [0.025, 0.975],
            ).tolist(),
        }
        for left, right in (("vad", "fixed"), ("vad_repair", "vad"))
    ]


def write_results(args, observations):
    args.output.mkdir(parents=True, exist_ok=True)
    summaries = []
    for condition in dict.fromkeys(r["condition"] for r in observations):
        for mode in MODES:
            rows = [r for r in observations if r["mode"] == mode and r["condition"] == condition]
            total = lambda key: sum(r[key] for r in rows)
            delays = [delay for r in rows for delay in r["delays_s"]]
            substitutions, insertions, deletions = (total(key) for key in ("substitutions", "insertions", "deletions"))
            summaries.append(dict(
                condition=condition, mode=mode, clips=len(rows), audio_s=total("audio_s"), words=total("words"),
                wer=(substitutions + insertions + deletions) / total("words"),
                substitutions=substitutions, insertions=insertions, deletions=deletions,
                rtf=total("inference_s") / total("audio_s"),
                emission_p95_s=float(np.percentile(delays, 95)) if delays else None,
                windows=total("windows"), repair_calls=total("repair_calls"), repairs_used=total("repairs_used"),
            ))
    payload = dict(
        model=args.model, device=args.device, compute_type=args.compute_type, platform=platform.platform(),
        faster_whisper=version("faster-whisper"), ctranslate2=version("ctranslate2"),
        manifest_sha256=hashlib.sha256(args.manifest.read_bytes()).hexdigest(), results=summaries,
        paired_clean_intervals=paired_wer_intervals(observations),
    )
    (args.output / "summary.json").write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    (args.output / "observations.json").write_text(
        json.dumps(observations, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
    )
    with (args.output / "comparison.csv").open("w", newline="", encoding="utf-8") as stream:
        writer = csv.DictWriter(stream, fieldnames=summaries[0].keys())
        writer.writeheader()
        writer.writerows(summaries)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--model", default="base")
    parser.add_argument("--device", choices=("cpu", "cuda"), default="cpu")
    parser.add_argument("--compute-type", default="int8")
    parser.add_argument("--stress", type=int, default=4, help="Number of paired clips at 10 dB SNR")
    run(parser.parse_args())
