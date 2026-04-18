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


class CodeEdit(BaseModel):
    """A proposed code edit."""

    model_config = ConfigDict(extra="ignore")

    file_path: str
    original_code: str
    proposed_code: str
    explanation: str = ""
    start_line: int | None = None
    end_line: int | None = None
    diff_unified: str = ""


class ChatTurn(BaseModel):
    """A single turn in the /code-chat conversation. Uses the standard LLM
    `user`/`assistant` vocabulary (not Socratic `coach`/`developer`) because
    the chat is now general-purpose Q&A about the developer's code."""

    model_config = ConfigDict(extra="ignore")

    role: Literal["user", "assistant"]
    text: str
    edits: list[CodeEdit] = Field(default_factory=list)


class CodeChatRequest(BaseModel):
    """Ask the duck a question about the current file."""

    model_config = ConfigDict(extra="ignore")

    conversation: list[ChatTurn] = Field(default_factory=list)
    current_code: str = ""
    file_path: str | None = None
    language: str = "python"
    context_files: list[ContextFile] = Field(default_factory=list)
    allow_edits: bool = False


class CodeChatResponse(BaseModel):
    model_config = ConfigDict(extra="ignore")

    answer: str
    model: str = "unknown"
    edits: list[CodeEdit] = Field(default_factory=list)


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


class ExplainCodeRequest(BaseModel):
    """Ask the duck to explain a chunk of legacy / unfamiliar code."""

    model_config = ConfigDict(extra="ignore")

    code: str
    file_path: str | None = None
    language: str = "text"
    context: str = ""


class ExplainCodeResponse(BaseModel):
    model_config = ConfigDict(extra="ignore")

    explanation: str
    complexity: str = "unknown"
    suggestions: list[str] = Field(default_factory=list)
    model: str = "unknown"


class ErrorEnvelope(BaseModel):
    model_config = ConfigDict(extra="ignore")

    error: dict[str, Any]


# ---------------------------------------------------------------------------
# /api/v1/chat — streaming chat endpoint (Step 2 of the chat feature rollout).
#
# These models power the SSE-based chat protocol used by the IntelliJ plugin
# and VS Code extension. They are INTENTIONALLY separate from the legacy
# CodeChat* models above, which back the non-streaming /api/v1/code-chat
# endpoint (kept for backwards compatibility).
# ---------------------------------------------------------------------------


class ChatMessage(BaseModel):
    """A single message in a multi-turn chat conversation.

    Uses the canonical OpenAI-ish vocabulary (system/user/assistant) so the
    message list can be handed to any chat-completions-shaped LLM verbatim.
    """

    model_config = ConfigDict(extra="ignore")

    role: Literal["system", "user", "assistant"]
    content: str


class ChatSelection(BaseModel):
    """The active editor selection the developer is asking about."""

    model_config = ConfigDict(extra="ignore")

    file_path: str | None = None
    language: str | None = None
    code: str


class ChatAttachedFile(BaseModel):
    """A file the client attached as additional context (e.g. via @-mention)."""

    model_config = ConfigDict(extra="ignore")

    file_path: str
    content: str
    language: str | None = None


class ChatDiagnostic(BaseModel):
    """An editor diagnostic (compiler error, linter warning, …) attached as context."""

    model_config = ConfigDict(extra="ignore")

    file_path: str
    line: int | None = None
    severity: Literal["error", "warning", "info", "hint"] = "error"
    message: str


class ChatContext(BaseModel):
    """Bundle of IDE context sent alongside the conversation turns.

    Each field is optional; the context builder renders only the sections
    that are actually populated.
    """

    model_config = ConfigDict(extra="ignore")

    selection: ChatSelection | None = None
    attached_files: list[ChatAttachedFile] = Field(default_factory=list)
    diagnostics: list[ChatDiagnostic] = Field(default_factory=list)
    open_files: list[str] = Field(default_factory=list)  # paths only, for awareness


class ChatRequest(BaseModel):
    """POST body for /api/v1/chat."""

    model_config = ConfigDict(extra="ignore")

    messages: list[ChatMessage] = Field(default_factory=list)
    context: ChatContext = Field(default_factory=ChatContext)
    slash_command: Literal["explain", "tests", "refactor", "fix", "doc"] | None = None
    # Skill name as declared in a .skills/<name>/SKILL.md frontmatter. For the
    # hackathon the server doesn't try to resolve project-scoped skill files;
    # the client sends the markdown body inline via `skill_body` instead.
    skill_name: str | None = None
    skill_body: str | None = None  # full SKILL.md body, overrides skill_name lookup
    model: str | None = None  # override LLM_MODEL for this request
    temperature: float | None = None
    max_tokens: int | None = None
    stream: bool = True


# ---- SSE event envelopes (server → client) ----
#
# Each event is serialised as a JSON object and wrapped in one SSE frame:
#     data: {"type":"token","delta":"..."}\n\n
# A trailing `data: [DONE]\n\n` sentinel is emitted after the stream completes
# so the client can close the reader without having to parse the final frame.


class ChatTokenEvent(BaseModel):
    type: Literal["token"] = "token"
    delta: str


class ChatToolCallEvent(BaseModel):
    type: Literal["tool_call"] = "tool_call"
    name: str
    arguments: dict[str, Any] = Field(default_factory=dict)


class ChatUsageEvent(BaseModel):
    type: Literal["usage"] = "usage"
    prompt_tokens: int | None = None
    completion_tokens: int | None = None
    total_tokens: int | None = None


class ChatDoneEvent(BaseModel):
    type: Literal["done"] = "done"
    finish_reason: str | None = None


class ChatErrorEvent(BaseModel):
    type: Literal["error"] = "error"
    message: str


class ChatUsage(BaseModel):
    """Token usage block for the non-streaming response."""

    model_config = ConfigDict(extra="ignore")

    prompt_tokens: int | None = None
    completion_tokens: int | None = None
    total_tokens: int | None = None


class ChatResponse(BaseModel):
    """Non-streaming fallback response returned when `stream=False`."""

    model_config = ConfigDict(extra="ignore")

    content: str
    usage: ChatUsage | None = None
    finish_reason: str | None = None
    model: str = "unknown"
