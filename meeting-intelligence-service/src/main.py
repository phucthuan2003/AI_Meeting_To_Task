import asyncio
from contextlib import asynccontextmanager

from fastapi import FastAPI, HTTPException, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from python_multipart.exceptions import MultipartParseError

from src.core.config import MAX_UPLOAD_BYTES, Settings
from src.routers import intelligence, transcription
from src.services.llm_analyzer import GeminiAnalyzer
from src.services.stt_engine import SttEngine


class BodyLimit:
    """Bound chunked bodies and multipart overhead before the route parses them."""

    def __init__(self, app):
        self.app = app

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http" or scope["method"] != "POST":
            await self.app(scope, receive, send)
            return
        limit = MAX_UPLOAD_BYTES + 64 * 1024 if scope["path"] == "/api/v1/transcribe/file" else 2 * 1024 * 1024
        count = 0

        async def bounded_receive():
            nonlocal count
            message = await receive()
            count += len(message.get("body", b""))
            if count > limit:
                raise HTTPException(413, detail="REQUEST_TOO_LARGE")
            return message

        length = dict(scope["headers"]).get(b"content-length", b"")
        if length.isdigit() and int(length) > limit:
            await JSONResponse({"detail": "REQUEST_TOO_LARGE"}, status_code=413)(scope, receive, send)
            return
        await self.app(scope, bounded_receive, send)


def create_app(settings: Settings | None = None, engine_factory=SttEngine, analyzer_factory=GeminiAnalyzer) -> FastAPI:
    @asynccontextmanager
    async def lifespan(app: FastAPI):
        config = settings if settings is not None else Settings.from_env()
        if not config.api_key.strip():
            raise RuntimeError("INTELLIGENCE_API_KEY is required")
        engine = engine_factory(config.model_name, config.device, config.compute_type)
        engine.warmup()
        app.state.settings = config
        app.state.engine = engine
        app.state.stt_slots = asyncio.Semaphore(1)
        app.state.stream_slots = asyncio.Semaphore(16)
        app.state.llm_slots = asyncio.Semaphore(4)
        analyzer = analyzer_factory(config.gemini_api_key, config.gemini_model) if config.gemini_api_key else None
        app.state.analyzer = analyzer
        try:
            yield
        finally:
            if analyzer is not None:
                analyzer.close()
            app.state.engine = None

    app = FastAPI(title="Meeting Intelligence Service", version="1.0.0", lifespan=lifespan)
    app.add_middleware(BodyLimit)
    app.include_router(transcription.router)
    app.include_router(intelligence.router)

    @app.get("/healthz", include_in_schema=False)
    async def health(request: Request):
        if getattr(request.app.state, "engine", None) is None:
            return JSONResponse({"status": "starting"}, status_code=503)
        return {"status": "ready"}

    @app.exception_handler(RequestValidationError)
    async def invalid_request(_request, _error):
        # FastAPI's default validation response includes the raw rejected transcript.
        return JSONResponse({"detail": "INVALID_REQUEST"}, status_code=422)

    @app.exception_handler(MultipartParseError)
    async def invalid_multipart(_request, _error):
        return JSONResponse({"detail": "INVALID_MULTIPART"}, status_code=400)

    return app


app = create_app()
