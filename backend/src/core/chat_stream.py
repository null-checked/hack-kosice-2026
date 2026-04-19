"""SSE wrapper around `llm.stream_complete` for the /api/v1/chat endpoint.

The flow:
    ChatRequest  →  build_chat_prompt(...)  →  llm.stream_complete(...)
                                                      │
                                                      ▼
                              async iterator of typed event dicts
                                                      │
                                                      ▼
                    SSE frames  "data: {json}\\n\\n"  →  client

The final frame is always a `data: [DONE]\\n\\n` sentinel so a naive client
can close its reader the moment it sees that line, without having to parse
the preceding event.

Exceptions raised anywhere in the pipeline are caught and surfaced as an
`{type: "error"}` SSE event — the endpoint still returns HTTP 200 in that
case because the headers have already been sent by the time streaming
starts; a mid-stream 500 isn't representable in SSE.

When ``ChatRequest.allow_edits`` is True the iterator switches into
*envelope mode*: tokens are buffered (not forwarded) until the upstream
model finishes, then the buffered text is parsed by
``chat_edits.parse_structured_response`` and the extracted ``answer`` is
re-streamed as a single ``token`` event followed by one ``code_edit``
event per parsed edit. If parsing fails we fall back to emitting the raw
buffer so the user still sees something.
"""

from __future__ import annotations

import json
import logging
import uuid
from collections.abc import AsyncIterator
from pathlib import Path
from typing import Any

from src.core.chat_edits import parse_structured_response
from src.core.context_builder import build_chat_prompt
from src.core.llm_client import BaseLLMClient
from src.models.api import (
    ChatCodeEditEvent,
    ChatDoneEvent,
    ChatErrorEvent,
    ChatRequest,
    ChatTokenEvent,
    ChatToolCallEvent,
    ChatUsageEvent,
)

logger = logging.getLogger(__name__)


def _format_sse(payload: str) -> str:
    """Wrap a JSON payload in a single SSE `data:` frame."""
    return f"data: {payload}\n\n"


def _event_to_json(event: dict[str, Any]) -> str:
    """Convert a raw stream event dict into a JSON string via the typed models.

    Going through Pydantic keeps the wire format honest (any extra/missing
    keys get normalised) and guarantees the `type` discriminator is present.
    Unknown event types are serialised verbatim so future additions don't
    require a code change on this hot path.
    """
    kind = event.get("type")
    try:
        if kind == "token":
            return ChatTokenEvent(**event).model_dump_json()
        if kind == "tool_call":
            return ChatToolCallEvent(**event).model_dump_json()
        if kind == "usage":
            return ChatUsageEvent(**event).model_dump_json()
        if kind == "done":
            return ChatDoneEvent(**event).model_dump_json()
        if kind == "error":
            return ChatErrorEvent(**event).model_dump_json()
        if kind == "code_edit":
            return ChatCodeEditEvent(**event).model_dump_json()
    except Exception as exc:  # noqa: BLE001
        logger.warning("Failed to serialise stream event via model: %s", exc)
    return json.dumps(event, default=str)


async def _iter_plain_events(
    req: ChatRequest,
    llm: BaseLLMClient,
    skills_dir: Path | None,
) -> AsyncIterator[dict[str, Any]]:
    """Default streaming path — forwards every upstream event verbatim."""
    messages = build_chat_prompt(req, skills_dir=skills_dir)
    saw_done = False
    try:
        async for event in llm.stream_complete(
            messages,
            model=req.model,
            temperature=req.temperature,
            max_tokens=req.max_tokens,
        ):
            if event.get("type") == "done":
                saw_done = True
            yield event
    except Exception as exc:  # noqa: BLE001
        logger.exception("Chat stream failed mid-flight")
        yield {"type": "error", "message": str(exc) or exc.__class__.__name__}
        saw_done = False  # ensure we still close with a done frame below
    if not saw_done:
        yield {"type": "done", "finish_reason": "error"}


async def _iter_edits_envelope_events(
    req: ChatRequest,
    llm: BaseLLMClient,
    skills_dir: Path | None,
) -> AsyncIterator[dict[str, Any]]:
    """Envelope-mode streaming path — buffer tokens, parse, emit code_edit events.

    The upstream LLM is asked for a single JSON object (`{answer, edits}`)
    via `json_mode=True`. We buffer every `token` event's delta, let
    non-token events (usage, tool_call) pass through, and once the
    upstream stream is done we parse the buffer and re-emit:

        token  (answer text)
        code_edit  (one per parsed edit)
        usage      (if the upstream provided one — deferred to end)
        done

    On parse failure we fall back to streaming the raw buffer so the user
    still sees something useful.
    """
    messages = build_chat_prompt(req, skills_dir=skills_dir)
    buffered: list[str] = []
    deferred_usage: dict[str, Any] | None = None
    upstream_done: dict[str, Any] | None = None
    upstream_error: str | None = None

    try:
        async for event in llm.stream_complete(
            messages,
            model=req.model,
            temperature=req.temperature,
            max_tokens=req.max_tokens,
            json_mode=True,
        ):
            kind = event.get("type")
            if kind == "token":
                # Don't forward — the raw JSON would leak to the client.
                delta = event.get("delta") or ""
                if delta:
                    buffered.append(delta)
            elif kind == "usage":
                # Defer so `done` stays last.
                deferred_usage = event
            elif kind == "done":
                upstream_done = event
            else:
                # tool_call / other unknown types — pass through verbatim.
                yield event
    except Exception as exc:  # noqa: BLE001
        logger.exception("Chat stream (envelope mode) failed mid-flight")
        upstream_error = str(exc) or exc.__class__.__name__

    raw = "".join(buffered).strip()
    answer, edits = parse_structured_response(raw) if raw else (None, [])

    if answer is None and raw:
        # Parse failed — stream the raw buffer as the answer so the user
        # still sees a response. No code_edit events in this case.
        logger.warning(
            "chat-stream: envelope parse failed; streaming raw buffer (%d chars)",
            len(raw),
        )
        yield {"type": "token", "delta": raw}
    elif answer is not None:
        if answer:
            yield {"type": "token", "delta": answer}
        for edit in edits:
            yield {
                "type": "code_edit",
                "edit_id": str(uuid.uuid4()),
                "file_path": edit.file_path,
                "original_code": edit.original_code,
                "proposed_code": edit.proposed_code,
                "explanation": edit.explanation,
                "start_line": edit.start_line,
                "end_line": edit.end_line,
                "diff_unified": edit.diff_unified,
            }

    if deferred_usage is not None:
        yield deferred_usage

    if upstream_error is not None:
        yield {"type": "error", "message": upstream_error}
        yield {"type": "done", "finish_reason": "error"}
    elif upstream_done is not None:
        yield upstream_done
    else:
        yield {"type": "done", "finish_reason": "stop"}


async def iter_chat_events(
    req: ChatRequest,
    llm: BaseLLMClient,
    skills_dir: Path | None = None,
) -> AsyncIterator[dict[str, Any]]:
    """Run the chat pipeline and yield raw event dicts.

    Kept separate from `stream_chat` so the non-streaming endpoint branch can
    collect events into a single response without having to parse SSE frames.
    Errors are caught and yielded as an `error` event; `done` is guaranteed
    to be the last event yielded.
    """
    if req.allow_edits:
        async for event in _iter_edits_envelope_events(req, llm, skills_dir):
            yield event
    else:
        async for event in _iter_plain_events(req, llm, skills_dir):
            yield event


async def stream_chat(
    req: ChatRequest,
    llm: BaseLLMClient,
    skills_dir: Path | None = None,
) -> AsyncIterator[str]:
    """Yield SSE-formatted strings for the /api/v1/chat endpoint.

    Always terminates with `data: [DONE]\\n\\n` so the client can safely
    break its read loop on that literal.
    """
    async for event in iter_chat_events(req, llm, skills_dir=skills_dir):
        yield _format_sse(_event_to_json(event))
    yield "data: [DONE]\n\n"


__all__ = ["iter_chat_events", "stream_chat"]
