import asyncio

from fastapi import APIRouter, Depends, HTTPException, Request
from starlette.concurrency import run_in_threadpool

from src.core.security import require_key
from src.schemas.meeting_schema import MeetingAnalysis, MeetingRequest
from src.services.llm_analyzer import AnalysisUnavailable

router = APIRouter(prefix="/api/v1/intelligence", dependencies=[Depends(require_key)])


@router.post("/extract-actions", response_model=MeetingAnalysis)
async def extract_actions(request: Request, meeting: MeetingRequest) -> MeetingAnalysis:
    analyzer = request.app.state.analyzer
    if analyzer is None:
        raise HTTPException(503, detail="LLM_NOT_CONFIGURED")
    slots: asyncio.Semaphore = request.app.state.llm_slots
    try:
        await asyncio.wait_for(slots.acquire(), 5)
    except asyncio.TimeoutError as error:
        raise HTTPException(503, detail="LLM_BUSY") from error
    try:
        return await run_in_threadpool(analyzer.extract, meeting)
    except AnalysisUnavailable as error:
        raise HTTPException(error.status, detail=str(error)) from error
    finally:
        slots.release()
