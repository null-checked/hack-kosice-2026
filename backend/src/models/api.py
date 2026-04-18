"""Request/response DTOs for the four HTTP endpoints."""

from __future__ import annotations

from typing import Any, Literal

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


class SocraticTurn(BaseModel):
    """A single turn in the Socratic dialogue."""

    model_config = ConfigDict(extra="ignore")

    role: Literal["coach", "developer"]
    text: str


class SocraticRequest(BaseModel):
    model_config = ConfigDict(extra="ignore")

    recent_events: list[DeveloperEvent] = Field(default_factory=list)
    current_code: str = ""
    stuck_reason: str = "unknown"
    # Empty list = opening question (pick by stuck_reason). Non-empty =
    # the user has replied at least once; backend returns a follow-up.
    conversation: list[SocraticTurn] = Field(default_factory=list)


class SocraticResponse(BaseModel):
    model_config = ConfigDict(extra="ignore")

    question: str
    follow_ups: list[str] = Field(default_factory=list)
    confidence: float = 0.7


class ChatTurn(BaseModel):
    """A single turn in the /code-chat conversation. Uses the standard LLM
    `user`/`assistant` vocabulary (not Socratic `coach`/`developer`) because
    the chat is now general-purpose Q&A about the developer's code."""

    model_config = ConfigDict(extra="ignore")

    role: Literal["user", "assistant"]
    text: str


class CodeChatRequest(BaseModel):
    """Ask the duck a question about the current file."""

    model_config = ConfigDict(extra="ignore")

    conversation: list[ChatTurn] = Field(default_factory=list)
    current_code: str = ""
    file_path: str | None = None
    language: str = "python"


class CodeChatResponse(BaseModel):
    model_config = ConfigDict(extra="ignore")

    answer: str
    model: str = "unknown"


class CompleteRequest(BaseModel):
    """Inline completion request. `prefix` is the code BEFORE the caret,
    `suffix` is the code AFTER. Fill-in-the-middle style."""

    model_config = ConfigDict(extra="ignore")

    language: str = "python"
    prefix: str
    suffix: str = ""
    file_path: str | None = None
    max_tokens: int = 96


class CompleteResponse(BaseModel):
    model_config = ConfigDict(extra="ignore")

    completion: str
    stop_reason: str = "eol"  # eol | max_tokens | llm_truncated
    model: str = "unknown"


class ErrorEnvelope(BaseModel):
    model_config = ConfigDict(extra="ignore")

    error: dict[str, Any]
