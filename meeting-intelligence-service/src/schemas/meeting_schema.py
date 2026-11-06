from datetime import date
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, StringConstraints

NonBlank = Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=2_000)]


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid")


class MeetingRequest(StrictModel):
    meeting_id: Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=128)]
    transcript: Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=200_000)]


class ActionItem(StrictModel):
    task: NonBlank
    assignee: NonBlank | None
    deadline: date | None
    priority: Literal["HIGH", "MEDIUM", "LOW"] | None


class MeetingAnalysis(StrictModel):
    summary: NonBlank
    key_decisions: list[NonBlank]
    action_items: list[ActionItem]
