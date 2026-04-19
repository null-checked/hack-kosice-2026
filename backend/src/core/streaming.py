"""Shared SSE helpers used by the streaming siblings of the legacy endpoints.

Framing rules:
  - UTF-8 `text/event-stream`.
  - Each event: `event: <name>\\n` + `data: <utf-8 JSON on one line>\\n\\n`.
  - Required response headers: `Cache-Control: no-cache`, `X-Accel-Buffering: no`.
"""

from __future__ import annotations

import asyncio
import json
import logging
from collections.abc import AsyncIterator, Awaitable, Callable, Iterable
from typing import Any

from pydantic import BaseModel

from src.core.llm_client import BaseLLMClient, LLMUnavailableError

logger = logging.getLogger(__name__)


def sse_event(name: str, data: Any) -> str:
    """Render a single SSE frame. `data` is JSON-encoded on one line."""
    payload = json.dumps(data, ensure_ascii=False, separators=(",", ":"))
    return f"event: {name}\ndata: {payload}\n\n"


def sse_headers() -> dict[str, str]:
    """Headers required for a streaming SSE response behind a proxy."""
    return {
        "Cache-Control": "no-cache",
        "X-Accel-Buffering": "no",
        "Content-Type": "text/event-stream; charset=utf-8",
    }


async def stream_prose_with_final(
    llm: BaseLLMClient,
    *,
    prompt: str,
    system: str | None,
    final_builder: Callable[[str], BaseModel | Awaitable[BaseModel]],
    temperature: float = 0.2,
    max_tokens: int = 2048,
    mock_tokens: Iterable[str] | None = None,
    token_delay: float = 0.02,
) -> AsyncIterator[str]:
    """Stream prose tokens as they arrive, then emit a final DTO.

    `final_builder(full_text) -> BaseModel` (or awaitable that returns one) is
    responsible for parsing the buffered prose into the endpoint's response
    DTO — usually this means `extract_first_json` + typed construction.

    When `mock_tokens` is supplied (DEMO_MOCK path), the LLM is not called;
    those canned tokens are dripped at `token_delay` seconds apart instead.
    Useful for giving the same "typing" UX in demo mode.
    """
    buf: list[str] = []
    try:
        if mock_tokens is not None:
            for tok in mock_tokens:
                buf.append(tok)
                yield sse_event("token", {"delta": tok})
                if token_delay > 0:
                    await asyncio.sleep(token_delay)
        else:
            async for chunk in llm.stream(
                prompt, system=system, temperature=temperature, max_tokens=max_tokens
            ):
                if not chunk:
                    continue
                buf.append(chunk)
                yield sse_event("token", {"delta": chunk})
    except LLMUnavailableError as exc:
        yield sse_event("error", {"error": str(exc), "step": "llm"})
        return
    except asyncio.CancelledError:
        raise
    except Exception as exc:  # noqa: BLE001
        logger.exception("stream_prose_with_final: provider error")
        yield sse_event("error", {"error": str(exc), "step": "llm"})
        return

    full_text = "".join(buf)
    try:
        result = final_builder(full_text)
        if asyncio.iscoroutine(result):
            result = await result
        if not isinstance(result, BaseModel):
            raise TypeError(
                f"final_builder returned {type(result).__name__}, expected BaseModel"
            )
        yield sse_event("done", result.model_dump())
    except Exception as exc:  # noqa: BLE001
        logger.exception("stream_prose_with_final: final_builder failed")
        yield sse_event("error", {"error": str(exc), "step": "final_builder"})
