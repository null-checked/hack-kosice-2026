"""POST /api/v1/chat — streaming chat endpoint.

This is the primary conversational entry point for the IntelliJ plugin and
VS Code extension. It emits Server-Sent Events (SSE) with a typed JSON
event protocol, enabling token-by-token rendering in the IDE.

The legacy non-streaming `/api/v1/code-chat` endpoint remains registered
for backwards compatibility.
"""

from __future__ import annotations

import logging

from typing import Any

from fastapi import APIRouter, Depends
from fastapi.responses import StreamingResponse

from src.api.v1.deps import get_llm, get_orchestrator, require_optional_token
from src.core.chat_stream import iter_chat_events, stream_chat
from src.core.llm_client import BaseLLMClient
from src.models.api import ChatRequest, ChatResponse, ChatUsage

logger = logging.getLogger(__name__)

router = APIRouter()


# SSE response headers. `Cache-Control: no-transform` prevents intermediaries
# from gzipping or rewriting the stream (which would buffer it); `X-Accel-
# Buffering: no` disables nginx's 8 KB proxy buffer — matters once we're
# behind a reverse proxy in production.
_SSE_HEADERS = {
    "Cache-Control": "no-cache, no-transform",
    "X-Accel-Buffering": "no",
    "Connection": "keep-alive",
}


@router.post("/chat", dependencies=[Depends(require_optional_token)])
async def chat(
    req: ChatRequest,
    llm: BaseLLMClient = Depends(get_llm),
    orchestrator: Any = Depends(get_orchestrator),
):
    """Streaming chat endpoint.

    When `req.stream` is True (default) returns an SSE stream of typed event
    frames. When False, collects the stream server-side and returns a single
    JSON `ChatResponse` for clients that don't want to deal with SSE.
    """
    logger.debug(
        "/chat: messages=%d slash=%s skill=%s stream=%s mode=%s",
        len(req.messages),
        req.slash_command,
        req.skill_name,
        req.stream,
        req.resolved_mode,
    )

    if not req.stream:
        # Collect the stream server-side into a single response.
        content_parts: list[str] = []
        usage: ChatUsage | None = None
        finish_reason: str | None = None
        error_message: str | None = None
        async for event in iter_chat_events(req, llm, orchestrator=orchestrator):
            kind = event.get("type")
            if kind == "token":
                content_parts.append(event.get("delta", ""))
            elif kind == "usage":
                usage = ChatUsage(
                    prompt_tokens=event.get("prompt_tokens"),
                    completion_tokens=event.get("completion_tokens"),
                    total_tokens=event.get("total_tokens"),
                )
            elif kind == "done":
                finish_reason = event.get("finish_reason")
            elif kind == "error":
                error_message = event.get("message")
        if error_message and not content_parts:
            # Surface the failure in a structured way — HTTP 200 is fine
            # because the client asked for a non-streaming JSON envelope.
            return ChatResponse(
                content=f"[error] {error_message}",
                usage=usage,
                finish_reason="error",
                model=getattr(llm, "model", "unknown"),
            )
        return ChatResponse(
            content="".join(content_parts),
            usage=usage,
            finish_reason=finish_reason,
            model=getattr(llm, "model", "unknown"),
        )

    return StreamingResponse(
        stream_chat(req, llm, orchestrator=orchestrator),
        media_type="text/event-stream",
        headers=_SSE_HEADERS,
    )


__all__ = ["router"]
