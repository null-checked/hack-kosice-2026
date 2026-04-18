"""Request/response DTOs for the four HTTP endpoints."""

from __future__ import annotations

from typing import Any

from pydantic import BaseModel, ConfigDict, Field

from .events import DeveloperEvent


class HealthResponse(BaseModel):
    model_config = ConfigDict(extra="ignore")

    status: str = "ok"
    llm_provider: str
    llm_model: str
    llm_available: bool
    demo_mock: bool = False
    version: str = "0.2.0"


class ContextFile(BaseModel):
    model_config = ConfigDict(extra="ignore")
    path: str
    content: str


class GenerateTestsRequest(BaseModel):
    model_config = ConfigDict(extra="ignore")

    language: str = "python"
    code: str
    function_name: str | None = None
    file_path: str | None = None
    context_files: list[ContextFile] = Field(default_factory=list)


class GenerateTestsResponse(BaseModel):
    model_config = ConfigDict(extra="ignore")

    test_code: str
    test_file_name: str
    framework: str
    reasoning: str


class SelectionRange(BaseModel):
    model_config = ConfigDict(extra="ignore")
    start_line: int
    end_line: int


class SuggestRefactorRequest(BaseModel):
    model_config = ConfigDict(extra="ignore")

    language: str = "python"
    code: str
    file_path: str | None = None
    selection_range: SelectionRange | None = None
    smell_hints: list[str] = Field(default_factory=list)


class DetectedSmell(BaseModel):
    model_config = ConfigDict(extra="ignore")
    type: str
    line: int
    description: str


class SuggestRefactorResponse(BaseModel):
    model_config = ConfigDict(extra="ignore")

    refactored_code: str
    diff_unified: str
    smells_detected: list[DetectedSmell]
    reasoning: str


class SocraticRequest(BaseModel):
    model_config = ConfigDict(extra="ignore")

    recent_events: list[DeveloperEvent] = Field(default_factory=list)
    current_code: str = ""
    stuck_reason: str = "unknown"


class SocraticResponse(BaseModel):
    model_config = ConfigDict(extra="ignore")

    question: str
    follow_ups: list[str] = Field(default_factory=list)
    confidence: float = 0.7


class ErrorEnvelope(BaseModel):
    model_config = ConfigDict(extra="ignore")

    error: dict[str, Any]
