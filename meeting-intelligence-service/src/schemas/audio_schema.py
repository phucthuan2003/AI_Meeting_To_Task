from typing import Literal

from pydantic import BaseModel


class Segment(BaseModel):
    start: float
    end: float
    text: str


class FileTranscription(BaseModel):
    duration_seconds: float
    processing_time_seconds: float
    rtf: float
    segments: list[Segment]
    full_text: str


class TranscriptionDelta(Segment):
    type: Literal["TRANSCRIPTION_DELTA"] = "TRANSCRIPTION_DELTA"
    is_final: Literal[True] = True
