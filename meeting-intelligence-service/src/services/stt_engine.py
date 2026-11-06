from dataclasses import dataclass
from time import perf_counter
from typing import BinaryIO

import numpy as np

from src.core.config import MAX_FILE_SECONDS, SAMPLE_RATE
from src.schemas.audio_schema import FileTranscription, Segment


class InvalidAudio(ValueError):
    pass


class AudioTooLong(InvalidAudio):
    pass


class SttUnavailable(RuntimeError):
    pass


@dataclass(frozen=True)
class RecognizedSegment:
    start: float
    end: float
    text: str
    words: tuple[Segment, ...] = ()


def decode_audio(source: BinaryIO, max_seconds: int = MAX_FILE_SECONDS) -> np.ndarray:
    """Decode and bound the number of samples before allocating the full input array."""
    import av

    parts = []
    samples = 0
    resampler = av.AudioResampler(format="s16", layout="mono", rate=SAMPLE_RATE)

    def append(frames):
        nonlocal samples
        for frame in frames:
            samples += frame.samples
            if samples > max_seconds * SAMPLE_RATE:
                raise AudioTooLong()
            parts.append(frame.to_ndarray().reshape(-1))

    try:
        # Restrict demuxers so a renamed playlist cannot fetch nested files or URLs.
        with av.open(source, mode="r", metadata_errors="ignore", options={
            "format_whitelist": "wav,mp3,mov,matroska,webm,flac,ogg",
            "protocol_whitelist": "pipe",
        }) as container:
            if not container.streams.audio:
                raise InvalidAudio()
            for frame in container.decode(audio=0):
                append(resampler.resample(frame))
            append(resampler.resample(None))
    except (av.error.FFmpegError, OSError, EOFError) as error:
        raise InvalidAudio() from error
    if not samples:
        raise InvalidAudio()
    return np.concatenate(parts).astype(np.float32) / 32768.0


def _runtime_error(error: RuntimeError) -> SttUnavailable:
    message = str(error).casefold()
    if "out of memory" in message or "cublas_status_alloc_failed" in message:
        return SttUnavailable("STT_OUT_OF_MEMORY")
    return SttUnavailable("STT_RUNTIME_ERROR")


class SttEngine:
    def __init__(self, model_name: str, device: str, compute_type: str):
        from faster_whisper import WhisperModel

        try:
            self.model = WhisperModel(model_name, device=device, compute_type=compute_type)
        except RuntimeError as error:
            raise _runtime_error(error) from error

    def warmup(self) -> None:
        # Disabling VAD here ensures even silence executes the CUDA kernels.
        self.transcribe(np.zeros(SAMPLE_RATE, dtype=np.float32), vad=False, words=True)

    def transcribe(self, samples: np.ndarray, *, words: bool = False, vad: bool = True) -> list[RecognizedSegment]:
        duration = samples.size / SAMPLE_RATE
        try:
            segments, _ = self.model.transcribe(
                samples,
                language="vi",
                temperature=0.0,
                vad_filter=vad,
                vad_parameters={"min_silence_duration_ms": 500, "speech_pad_ms": 200},
                word_timestamps=words,
                condition_on_previous_text=not words,
            )
            results = []
            for segment in segments:
                start = max(0.0, segment.start)
                end = min(duration, segment.end)
                text = segment.text.strip()
                if start >= end or not text:
                    continue
                word_spans = tuple(
                    Segment(start=max(start, w.start), end=min(end, w.end), text=w.word)
                    for w in (segment.words or ())
                    if w.word.strip() and max(start, w.start) < min(end, w.end)
                )
                results.append(RecognizedSegment(start, end, text, word_spans))
            return results
        except RuntimeError as error:
            raise _runtime_error(error) from error

    def transcribe_file(self, source: BinaryIO) -> FileTranscription:
        started = perf_counter()
        samples = decode_audio(source)
        segments = [Segment(start=s.start, end=s.end, text=s.text) for s in self.transcribe(samples)]
        elapsed = perf_counter() - started
        duration = samples.size / SAMPLE_RATE
        return FileTranscription(
            duration_seconds=duration,
            processing_time_seconds=elapsed,
            rtf=elapsed / duration,
            segments=segments,
            full_text=" ".join(s.text for s in segments),
        )
