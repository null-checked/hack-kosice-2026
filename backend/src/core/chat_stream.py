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
"""

from __future__ import annotations

import json
import logging
from collections.abc import AsyncIterator
from pathlib import Path
from typing import Any

from src.core.context_builder import build_chat_prompt
from src.core.llm_client import BaseLLMClient
from src.models.api import (
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
    except Exception as exc:  # noqa: BLE001
        logger.warning("Failed to serialise stream event via model: %s", exc)
    return json.dumps(event, default=str)


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
