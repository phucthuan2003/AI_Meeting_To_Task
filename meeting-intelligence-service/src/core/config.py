import os
from dataclasses import dataclass, field

MODEL_NAME = "deepdml/faster-whisper-large-v3-turbo-ct2"
SAMPLE_RATE = 16_000
MAX_UPLOAD_BYTES = 25 * 1024 * 1024
MAX_FILE_SECONDS = 3_600
MAX_STREAM_SECONDS = 7_200
MAX_FRAME_BYTES = 64_000


@dataclass(frozen=True)
class Settings:
    model_name: str = MODEL_NAME
    device: str = "cuda"
    compute_type: str = "float16"
    api_key: str = field(default="", repr=False)
    gemini_api_key: str = field(default="", repr=False)
    gemini_model: str = "gemini-3.5-flash-lite"

    @classmethod
    def from_env(cls) -> "Settings":
        device = os.getenv("DEVICE", "cuda")
        if device not in {"cpu", "cuda"}:
            raise ValueError("DEVICE must be cpu or cuda")
        return cls(
            model_name=os.getenv("MODEL_NAME", MODEL_NAME),
            device=device,
            compute_type=os.getenv("COMPUTE_TYPE", "int8" if device == "cpu" else "float16"),
            api_key=os.getenv("INTELLIGENCE_API_KEY", ""),
            gemini_api_key=os.getenv("GEMINI_API_KEY", ""),
            gemini_model=os.getenv("GEMINI_MODEL", cls.gemini_model),
        )
