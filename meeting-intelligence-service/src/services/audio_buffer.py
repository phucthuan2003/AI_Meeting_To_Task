from collections import deque
from dataclasses import dataclass
from itertools import groupby
import unicodedata
from typing import Iterator

import numpy as np

from src.core.config import MAX_STREAM_SECONDS, SAMPLE_RATE
from src.schemas.audio_schema import TranscriptionDelta
from src.services.stt_engine import RecognizedSegment

WINDOW = SAMPLE_RATE * 2
OVERLAP = SAMPLE_RATE // 2
HOP = WINDOW - OVERLAP
SAMPLE_BYTES = 2


@dataclass(frozen=True)
class Window:
    offset: float
    samples: np.ndarray
    final: bool = False

    @property
    def end(self) -> float:
        return self.offset + self.samples.size / SAMPLE_RATE


class AudioBuffer:
    def __init__(self):
        self._pcm = bytearray()
        self._offset = 0
        self._total_bytes = 0

    def push(self, frame: bytes) -> Iterator[Window]:
        self._total_bytes += len(frame)
        if self._total_bytes > MAX_STREAM_SECONDS * SAMPLE_RATE * SAMPLE_BYTES:
            raise ValueError("STREAM_TOO_LONG")
        self._pcm.extend(frame)
        while len(self._pcm) >= WINDOW * SAMPLE_BYTES:
            window = self._window(WINDOW * SAMPLE_BYTES)
            del self._pcm[: HOP * SAMPLE_BYTES]
            self._offset += HOP
            yield window

    def finish(self) -> Window | None:
        if len(self._pcm) % SAMPLE_BYTES:
            raise ValueError("INCOMPLETE_PCM_SAMPLE")
        if not self._pcm:
            return None
        window = self._window(len(self._pcm), final=True)
        self._pcm.clear()
        return window

    def _window(self, byte_count: int, final: bool = False) -> Window:
        samples = np.frombuffer(self._pcm[:byte_count], dtype="<i2").astype(np.float32) / 32768.0
        return Window(self._offset / SAMPLE_RATE, samples, final)


def _tokens(text: str) -> str:
    return "".join(c for c in unicodedata.normalize("NFC", text).casefold() if c.isalnum())


class FinalizedTranscript:
    """Use word centers to assign the shared region, with lexical matching for timestamp drift."""

    def __init__(self):
        self._committed_until = 0.0
        self._last_end = 0.0
        self._recent = deque(maxlen=16)

    def accept(self, window: Window, segments: list[RecognizedSegment]) -> Iterator[TranscriptionDelta]:
        cutoff = window.end if window.final else window.end - OVERLAP / SAMPLE_RATE / 2
        spans = []
        for segment_index, segment in enumerate(segments):
            words = [(w.start, w.end, w.text) for w in segment.words]
            for start, end, text in words or [(segment.start, segment.end, segment.text)]:
                start += window.offset
                end += window.offset
                if self._committed_until < (start + end) / 2 <= cutoff:
                    spans.append((segment_index, start, end, text))

        # The same words can move across the cutoff when a window is decoded again.
        # Only deduplicate a prefix timestamped near the already emitted suffix.
        recent = list(self._recent)
        for size in range(min(len(recent), len(spans)), 0, -1):
            prefix, suffix = spans[:size], recent[-size:]
            if all(
                _tokens(text) and _tokens(text) == old_text
                and abs((start + end) / 2 - old_center) <= 0.18
                for (_, start, end, text), (old_text, old_center) in zip(prefix, suffix)
            ):
                del spans[:size]
                break

        for _, group in groupby(spans, key=lambda span: span[0]):
            words = list(group)
            start = max(self._last_end, words[0][1])
            end = words[-1][2]
            text = "".join(word[3] for word in words).strip()
            if round(start, 3) >= round(end, 3) or not text:
                continue
            self._last_end = end
            self._recent.extend((_tokens(text), (start + end) / 2) for _, start, end, text in words)
            yield TranscriptionDelta(start=round(start, 3), end=round(end, 3), text=text)
        self._committed_until = cutoff
