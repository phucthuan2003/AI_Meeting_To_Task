import asyncio
import json
from pathlib import Path

from fastapi import APIRouter, Depends, HTTPException, Request, WebSocket, WebSocketDisconnect
from starlette.concurrency import run_in_threadpool
from starlette.datastructures import UploadFile

from src.core.config import MAX_FRAME_BYTES, MAX_UPLOAD_BYTES
from src.core.security import authorized, require_key
from src.schemas.audio_schema import FileTranscription
from src.services.audio_buffer import AudioBuffer, FinalizedTranscript, Window
from src.services.stt_engine import AudioTooLong, InvalidAudio, SttUnavailable

router = APIRouter()


async def inference(state, work, *args, **kwargs):
    try:
        wait = getattr(getattr(state, "settings", None), "stt_queue_wait", 45.0)
        await asyncio.wait_for(state.stt_slots.acquire(), timeout=wait)
    except asyncio.TimeoutError as error:
        raise SttUnavailable("STT_BUSY") from error
    try:
        return await run_in_threadpool(work, *args, **kwargs)
    finally:
        state.stt_slots.release()


@router.post(
    "/api/v1/transcribe/file",
    response_model=FileTranscription,
    dependencies=[Depends(require_key)],
    openapi_extra={"requestBody": {"required": True, "content": {"multipart/form-data": {
        "schema": {"type": "object", "required": ["file"], "properties": {"file": {
            "type": "string", "format": "binary",
        }}},
    }}}},
)
async def transcribe_file(request: Request) -> FileTranscription:
    if request.headers.get("content-type", "").split(";")[0].lower() != "multipart/form-data":
        raise HTTPException(415, detail="EXPECTED_MULTIPART")
    state = request.app.state
    try:
        async with request.form(max_files=1, max_fields=0) as form:
            file = form.get("file")
            if not isinstance(file, UploadFile):
                raise HTTPException(422, detail="FILE_REQUIRED")
            if Path(file.filename or "").suffix.lower() not in {".wav", ".mp3", ".m4a", ".webm"}:
                raise HTTPException(415, detail="UNSUPPORTED_AUDIO_FORMAT")
            if file.size is not None and file.size > MAX_UPLOAD_BYTES:
                raise HTTPException(413, detail="FILE_TOO_LARGE")
            return await inference(state, state.engine.transcribe_file, file.file)
    except AudioTooLong as error:
        raise HTTPException(413, detail="AUDIO_TOO_LONG") from error
    except InvalidAudio as error:
        raise HTTPException(422, detail="INVALID_AUDIO") from error
    except SttUnavailable as error:
        raise HTTPException(503, detail=str(error)) from error


async def send_window(websocket: WebSocket, transcript: FinalizedTranscript, window: Window) -> None:
    state = websocket.app.state
    segments = await inference(state, state.engine.transcribe, window.samples, words=True)
    for delta in transcript.accept(window, segments):
        await websocket.send_json(delta.model_dump())


@router.websocket("/ws/transcribe/stream")
async def stream(websocket: WebSocket) -> None:
    state = websocket.app.state
    scheme, _, token = websocket.headers.get("authorization", "").partition(" ")
    if scheme.lower() != "bearer" or not authorized(state.settings.api_key, token):
        await websocket.close(code=1008)
        return
    if state.stream_slots.locked():
        await websocket.close(code=1013)
        return
    async with state.stream_slots:
        await websocket.accept()
        buffer = AudioBuffer()
        transcript = FinalizedTranscript()
        try:
            while True:
                message = await asyncio.wait_for(websocket.receive(), 30)
                if message["type"] == "websocket.disconnect":
                    return
                frame = message.get("bytes")
                if frame is not None:
                    if len(frame) > MAX_FRAME_BYTES:
                        await websocket.close(code=1009, reason="FRAME_TOO_LARGE")
                        return
                    for window in buffer.push(frame):
                        await send_window(websocket, transcript, window)
                    continue
                if json.loads(message.get("text") or "null") != {"type": "STOP"}:
                    raise ValueError("INVALID_STREAM_COMMAND")
                tail = buffer.finish()
                if tail is not None:
                    await send_window(websocket, transcript, tail)
                await websocket.send_json({"type": "STREAM_END"})
                await websocket.close()
                return
        except asyncio.TimeoutError:
            await websocket.close(code=1008, reason="STREAM_IDLE_TIMEOUT")
        except (WebSocketDisconnect, OSError):
            return
        except json.JSONDecodeError:
            await websocket.close(code=1003, reason="INVALID_STREAM_COMMAND")
        except ValueError as error:
            await websocket.close(code=1003, reason=str(error))
        except SttUnavailable as error:
            await websocket.close(code=1013, reason=str(error))
