"""Code Q&A assistant — answers the developer's questions about their file.

Separate from `socratic_coach.py`: this one **gives direct answers** instead
of returning more questions. It powers the tool-window chat panel after the
UX split where Socratic questions became passive balloons and the chat
became a real assistant.

With the v0.4 orchestrator, `/code-chat` has TWO code paths:

  1. Legacy single-shot — the default, unchanged from v0.3. One LLM
     call via `answer_code_chat(...)` → plain prose answer.
  2. Multi-agent — triggered by `allow_edits=True` AND the orchestrator
     was successfully built at startup. `run_orchestrator_nonstreaming`
     drives the DeepAgent graph to completion via `ainvoke`, collects
     emitted edits via the ContextVar sink, and returns a
     CodeChatResponse with `edits` populated.
"""

from __future__ import annotations

import logging
import os
from typing import Any

from src.core.agents import (
    EditSink,
    bind_edit_sink,
    mock_orchestrator_response,
)
from src.core.agents.collector import reset_edit_sink
from src.core.llm_client import BaseLLMClient
from src.core.prompts import build_code_chat_prompt, build_orchestrator_user_message
from src.models.api import CodeChatRequest, CodeChatResponse, CodeEdit
from src.settings import Settings

logger = logging.getLogger(__name__)


_MOCK_ANSWERS: list[str] = [
    (
        "This function appears to return the input unchanged when the user "
        "hasn't actually asked a specific question. Try highlighting a "
        "method and asking 'what does this do?' for a more useful answer."
    ),
    (
        "Your code reads the file, transforms the rows, and writes them "
        "back. The main risk is that `line 3`'s map over `xs` is O(n²) "
        "because it calls `sum()` inside the loop — pull the sum out once "
        "and cap the inner call."
    ),
    (
        "I can see the function signature and a handful of branches, but "
        "I'm not sure what the caller expects on the empty-input path. "
        "What happens if `xs` is `[]`?"
    ),
    (
        "Short answer: yes, this loop is the hot spot. Long answer: the "
        "nested comprehension allocates a list per iteration. For inputs "
        "≥ 10 000 you'll want a generator and a running accumulator."
    ),
]


def _mock_answer_text(req: CodeChatRequest) -> str:
    last_question = ""
    for turn in reversed(req.conversation):
        if turn.role == "user":
            last_question = turn.text
            break
    idx = (hash(last_question) % len(_MOCK_ANSWERS) + len(_MOCK_ANSWERS)) % len(
        _MOCK_ANSWERS
    )
    return _MOCK_ANSWERS[idx]


def _mock_response(req: CodeChatRequest) -> CodeChatResponse:
    """DEMO_MOCK branch — if the caller asked for edits, return the
    richer multi-agent mock; otherwise the single-shot Q&A mock."""
    if req.allow_edits:
        return mock_orchestrator_response()
    return CodeChatResponse(answer=_mock_answer_text(req), model="mock")


async def answer_code_chat(
    req: CodeChatRequest,
    llm: BaseLLMClient,
    *,
    demo_mock: bool = False,
) -> CodeChatResponse:
    if demo_mock:
        logger.debug("code-chat: DEMO_MOCK short-circuit (allow_edits=%s)", req.allow_edits)
        return _mock_response(req)

    if not req.conversation:
        # No user question yet → return a helpful hint instead of failing.
        return CodeChatResponse(
            answer="Ask me a question about your code — what does it do, where's the bug, how would you refactor it, …",
            model=getattr(llm, "model", "unknown"),
        )

    system, user = build_code_chat_prompt(
        language=req.language,
        current_code=req.current_code,
        file_path=req.file_path,
        conversation=req.conversation,
    )
    raw = await llm.complete(user, system=system, temperature=0.3, max_tokens=500)
    answer = (raw or "").strip()
    if not answer:
        answer = "(the assistant returned nothing — try rephrasing)"
    return CodeChatResponse(answer=answer, model=getattr(llm, "model", "unknown"))


def _build_initial_state(
    req: CodeChatRequest, *, work_dir: str, allow_edits: bool
) -> dict[str, Any]:
    """Turn the request into the `messages` list LangGraph expects.

    The DeepAgent state schema has a `messages` channel; we seed it with
    a single human message built from the orchestrator prompt helper."""
    user_text = build_orchestrator_user_message(
        language=req.language,
        current_file_path=req.file_path,
        current_code=req.current_code,
        conversation=req.conversation,
        context_files=req.context_files,
        work_dir=work_dir,
        allow_edits=allow_edits,
    )
    return {"messages": [{"role": "user", "content": user_text}]}


def _extract_final_prose(result: Any) -> str:
    """Pull the orchestrator's final AI message text out of the ainvoke
    result. `result["messages"]` is the full transcript; we take the
    last AI-role message's content."""
    messages = (result or {}).get("messages") or []
    for msg in reversed(messages):
        # Works for both dict-shaped messages (during streaming-adjacent
        # paths) and BaseMessage objects from ainvoke.
        if isinstance(msg, dict):
            if msg.get("role") in ("assistant", "ai"):
                content = msg.get("content")
                if isinstance(content, str) and content.strip():
                    return content.strip()
            continue
        role = getattr(msg, "type", None) or getattr(msg, "role", None)
        if role in ("ai", "assistant"):
            content = getattr(msg, "content", "")
            if isinstance(content, str) and content.strip():
                return content.strip()
            if isinstance(content, list):
                text_parts = [
                    b.get("text", "")
                    for b in content
                    if isinstance(b, dict) and b.get("type") == "text"
                ]
                joined = "".join(text_parts).strip()
                if joined:
                    return joined
    return ""


def _build_context(
    req: CodeChatRequest, settings: Settings
) -> dict[str, Any]:
    """Construct the `RubberDuckContext`-shaped dict passed via `context`."""
    work_dir = settings.ORCHESTRATOR_WORK_DIR or os.getcwd()
    return {
        "language": req.language,
        "current_file_path": req.file_path,
        "current_code": req.current_code,
        "context_files": [cf.model_dump() for cf in req.context_files],
        "allow_edits": req.allow_edits,
        "work_dir": work_dir,
    }


async def run_orchestrator_nonstreaming(
    orchestrator: Any,
    req: CodeChatRequest,
    settings: Settings,
) -> CodeChatResponse:
    """Drive the DeepAgent graph to completion via `ainvoke`.

    Binds a fresh `EditSink` to the current async task's ContextVar
    before invoking, so the `emit_edit` tool calls populate it. Unbinds
    in a `try/finally` so one request's edits never leak into another."""
    work_dir = settings.ORCHESTRATOR_WORK_DIR or os.getcwd()
    initial_state = _build_initial_state(req, work_dir=work_dir, allow_edits=req.allow_edits)
    context = _build_context(req, settings)

    sink: EditSink = []
    token = bind_edit_sink(sink)
    try:
        result = await orchestrator.ainvoke(initial_state, context=context)
    finally:
        reset_edit_sink(token)

    answer = _extract_final_prose(result) or "(no orchestrator prose)"
    edits: list[CodeEdit] = []
    for entry in sink:
        raw_edit = entry.get("edit") if isinstance(entry, dict) else None
        if not isinstance(raw_edit, dict):
            continue
        try:
            edits.append(CodeEdit(**raw_edit))
        except Exception as exc:  # noqa: BLE001
            logger.warning("Skipping malformed edit from agent=%s: %s", entry.get("agent"), exc)

    return CodeChatResponse(
        answer=answer,
        model=settings.OPENAI_MODEL,
        edits=edits,
    )
