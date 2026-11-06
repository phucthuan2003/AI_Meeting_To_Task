"""Measure WER, warm inference latency, RTF and peak process VRAM."""
import argparse
import csv
from dataclasses import asdict, dataclass
from importlib.metadata import version
import json
import multiprocessing
import os
from pathlib import Path
import platform
from statistics import median
import sys
from time import perf_counter
import unicodedata

# Support both an absolute script path and `python -m benchmarks.run_benchmark`.
if __package__ in {None, ""}:
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import jiwer
import numpy as np

from benchmarks.gpu_memory import GpuMemory
from src.core.config import SAMPLE_RATE, Settings
from src.services.stt_engine import SttEngine, decode_audio

HERE = Path(__file__).resolve().parent
BASELINES = ("base", "small")
CHUNK_SECONDS = (1, 2, 5)


@dataclass(frozen=True)
class Measurement:
    model: str
    file: str
    scope: str
    offset_s: float
    duration_s: float
    repeat: int
    latency_s: float
    rtf: float
    word_errors: int | None
    reference_words: int | None


def normalize(text: str) -> str:
    nfc = unicodedata.normalize("NFC", text).casefold()
    return " ".join("".join(" " if unicodedata.category(c).startswith("P") else c for c in nfc).split())


def load_manifest(path: Path) -> list[tuple[Path, str]]:
    rows = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(rows, list) or not rows:
        raise ValueError("Manifest must be a non-empty JSON list")
    dataset = []
    seen = set()
    for row in rows:
        if not isinstance(row, dict) or not isinstance(row.get("file"), str) or not isinstance(row.get("transcript"), str):
            raise ValueError("Every manifest row needs a file and transcript")
        name = Path(row["file"])
        file = (path.parent / name).resolve()
        reference = normalize(row["transcript"])
        if name.is_absolute() or ".." in name.parts or not file.is_file() or file in seen or not reference:
            raise ValueError(f"Invalid dataset entry: {name}")
        seen.add(file)
        dataset.append((file, reference))
    return dataset


def windows(samples):
    yield "full", 0, samples
    for seconds in CHUNK_SECONDS:
        width = seconds * SAMPLE_RATE
        for offset in range(0, samples.size - width + 1, width):
            yield f"{seconds}s", offset, samples[offset : offset + width]


def measure_model(model, dataset, device, compute_type, repeats, measure_torch):
    torch = None
    if measure_torch:
        import torch
        torch.cuda.reset_peak_memory_stats()
    records = []
    with GpuMemory(device == "cuda") as memory:
        engine = SttEngine(model, device, compute_type)
        engine.warmup()
        for file, reference in dataset:
            with file.open("rb") as stream:
                samples = decode_audio(stream)
            for scope, offset, chunk in windows(samples):
                duration = chunk.size / SAMPLE_RATE
                for repeat in range(1, repeats + 1):
                    started = perf_counter()
                    segments = engine.transcribe(chunk, words=scope != "full")
                    latency = perf_counter() - started
                    errors = reference_words = None
                    if scope == "full" and repeat == 1:
                        words = jiwer.process_words(reference, normalize(" ".join(s.text for s in segments)))
                        errors = words.substitutions + words.deletions + words.insertions
                        reference_words = words.hits + words.substitutions + words.deletions
                    records.append(Measurement(
                        model, file.name, scope, offset / SAMPLE_RATE, duration, repeat,
                        latency, latency / duration, errors, reference_words,
                    ))
    return model, records, memory.peak_bytes, sorted(memory.devices), (
        torch.cuda.max_memory_allocated() if torch is not None else None
    )


def report(output: Path, results, device: str, compute_type: str, manifest: Path, repeats: int) -> None:
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    output.mkdir(parents=True, exist_ok=True)
    with (output / "benchmark_metrics.csv").open("w", newline="", encoding="utf-8") as stream:
        records = (asdict(row) for _, rows, *_ in results for row in rows)
        first = next(records)
        writer = csv.DictWriter(stream, fieldnames=first.keys())
        writer.writeheader()
        writer.writerow(first)
        writer.writerows(records)

    def mib(value):
        return "N/A" if value is None else f"{value / 1024**2:.1f}"

    lines = [
        "# Benchmark STT tiếng Việt", "",
        f"- Manifest `{manifest.name}`. Số lượt mỗi mẫu `{repeats}`.",
        f"- Thiết bị `{device}`, kiểu tính toán `{compute_type}`, hệ thống `{platform.platform()}`.",
        f"- faster-whisper `{version('faster-whisper')}`, CTranslate2 `{version('ctranslate2')}`.",
        "- Latency là thời gian từ mảng PCM đến khi tiêu thụ hết kết quả suy luận, sau warm-up.",
        "- RTF gộp là tổng thời gian suy luận chia tổng độ dài audio. Mục tiêu GPU cho file đầy đủ < 0,1.",
        "- WER gộp tính trên file đầy đủ ở lượt đầu, giữ dấu tiếng Việt, phân tách theo khoảng trắng.",
        "- VRAM NVML là đỉnh lấy mẫu 20 ms của tiến trình, gồm cả model. Cột PyTorch chỉ đo allocator của PyTorch.",
        "",
        "| Model | Đầu vào | Lượt đo | Latency p50 (s) | Latency p95 (s) | RTF gộp | WER |",
        "|---|---|---:|---:|---:|---:|---:|",
    ]
    summaries = {}
    for model, records, *_ in results:
        summaries[model] = {}
        for scope in [*(f"{seconds}s" for seconds in CHUNK_SECONDS), "full"]:
            rows = [row for row in records if row.scope == scope]
            if not rows:
                lines.append(f"| {model} | {scope} | 0 | N/A | N/A | N/A | N/A |")
                continue
            latency = [row.latency_s for row in rows]
            rtf = sum(latency) / sum(row.duration_s for row in rows)
            denominator = sum(row.reference_words or 0 for row in rows)
            wer = f"{sum(row.word_errors or 0 for row in rows) / denominator:.2%}" if denominator else "N/A"
            summaries[model][scope] = (median(latency), rtf)
            lines.append(f"| {model} | {scope} | {len(rows)} | {median(latency):.4f} | "
                         f"{np.percentile(latency, 95):.4f} | {rtf:.4f} | {wer} |")
    lines.extend([
        "", "| Model | GPU | Đỉnh VRAM NVML (MiB) | Đỉnh PyTorch (MiB) |",
        "|---|---|---:|---:|",
    ])
    for model, _, peak, devices, torch_peak in results:
        lines.append(f"| {model} | {', '.join(devices) or 'N/A'} | {mib(peak)} | {mib(torch_peak)} |")
    (output / "benchmark_metrics.md").write_text("\n".join(lines) + "\n", encoding="utf-8")

    figure, axis = plt.subplots(figsize=(9, 5))
    for model, summary in summaries.items():
        points = [(seconds, summary[f"{seconds}s"][0]) for seconds in CHUNK_SECONDS if f"{seconds}s" in summary]
        if points:
            axis.plot(*zip(*points), marker="o", label=model)
    axis.set(xlabel="Chunk size (s)", ylabel="Median inference latency (s)", xticks=CHUNK_SECONDS)
    if axis.lines:
        axis.legend()
    figure.tight_layout()
    figure.savefig(output / "latency_vs_chunk_size.png", dpi=150)
    plt.close(figure)

    figure, axis = plt.subplots(figsize=(9, 5))
    models = list(summaries)
    axis.bar(models, [summaries[model]["full"][1] for model in models])
    axis.set(ylabel="Aggregate real-time factor", title="Full-file inference")
    axis.tick_params(axis="x", labelrotation=15)
    if device == "cuda":
        axis.axhline(0.1, linestyle="--", label="GPU target 0.10")
        axis.legend()
    figure.tight_layout()
    figure.savefig(output / "rtf_comparison.png", dpi=150)
    plt.close(figure)


def main() -> None:
    settings = Settings.from_env()
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, default=HERE / "test_datasets" / "ground_truth.json")
    parser.add_argument("--output", type=Path, default=HERE / "results")
    parser.add_argument("--model", default=settings.model_name)
    parser.add_argument("--device", choices=["cpu", "cuda"], default=settings.device)
    parser.add_argument("--compute-type", default=os.getenv("COMPUTE_TYPE"))
    parser.add_argument("--repeats", type=int, default=3)
    parser.add_argument("--torch-memory", action="store_true", help="Also measure the optional PyTorch CUDA allocator")
    args = parser.parse_args()
    if args.repeats < 1 or (args.torch_memory and args.device != "cuda"):
        parser.error("Use at least one repeat; --torch-memory requires CUDA")
    compute_type = args.compute_type or ("float16" if args.device == "cuda" else "int8")
    try:
        dataset = load_manifest(args.manifest)
        models = dict.fromkeys((args.model, *BASELINES))
        jobs = [(model, dataset, args.device, compute_type, args.repeats, args.torch_memory) for model in models]
        # One CUDA process per model prevents allocator/cache carryover between models.
        with multiprocessing.get_context("spawn").Pool(1, maxtasksperchild=1) as pool:
            results = pool.starmap(measure_model, jobs, chunksize=1)
        report(args.output, results, args.device, compute_type, args.manifest, args.repeats)
    except (ImportError, OSError, RuntimeError, ValueError) as error:
        parser.exit(1, f"Benchmark failed: {error}\n")
    print(f"Saved CSV, Markdown and charts to {args.output.resolve()}")


if __name__ == "__main__":
    main()
