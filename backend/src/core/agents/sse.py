"""Translate a LangGraph `astream(stream_mode=[...])` stream into SSE frames.

The SSE contract for /code-chat/stream is:

    event: token             ← orchestrator's prose tokens (top-level LLM only)
    data: {"delta": "..."}

    event: plan              ← when write_todos is called on the orchestrator
    data: {"todos": [...]}

    event: subagent_start    ← when orchestrator invokes task(subagent_type=...)
    data: {"name": "...", "task": "..."}

    event: subagent_end
    data: {"name": "...", "summary": "..."}

    event: edit              ← from emit_edit custom stream writes
    data: <CodeEdit JSON>

    event: warning           ← recoverable issues
    event: error             ← terminal failure
    event: done              ← terminal success, carries the final DTO

LangGraph's `astream` with `stream_mode=["messages","updates","custom"]`
yields 2-tuples `(mode, payload)`. When `subgraphs=True` (which we want
so we can distinguish orchestrator tokens from sub-agent tokens) the
yields become 3-tuples `(namespace, mode, payload)` where `namespace` is
a tuple of parent-child graph names. An empty tuple == top-level (the
orchestrator itself), non-empty == inside a sub-agent graph.

We squelch sub-agent token output in v1 — the plugin UI renders
subagent_start/subagent_end chips instead. Opening the floodgates would
need the plugin to disambiguate which agent a given delta belongs to.
"""

from __future__ import annotations

import asyncio
import logging
from collections.abc import AsyncIterator
from typing import Any

from langchain_core.messages import AIMessage, AIMessageChunk, BaseMessage, ToolMessage
from langgraph.graph.state import CompiledStateGraph

from src.core.streaming import sse_event
from src.settings import Settings

from .collector import current_sink

logger = logging.getLogger(__name__)


def _extract_chunk_text(msg: BaseMessage) -> str:
    """Pull plain text out of a message or message-chunk.

    Content can be `str` or a list of typed blocks (Anthropic / multimodal
    models use the latter). We take only `text` blocks and concatenate."""
    content = getattr(msg, "content", "")
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        out: list[str] = []
        for block in content:
            if isinstance(block, dict) and block.get("type") == "text":
                txt = block.get("text")
                if isinstance(txt, str):
                    out.append(txt)
            elif isinstance(block, str):
                out.append(block)
        return "".join(out)
    return ""


def _is_top_level(namespace: Any) -> bool:
    """True when the stream chunk belongs to the orchestrator itself
    rather than a sub-agent graph. LangGraph conventionally uses `()`
    (empty tuple) for the top graph."""
    return namespace in (None, (), [])


def _render_todos(updates: Any) -> list[dict[str, Any]] | None:
    """If a node update contains `todos`, return a flat list for the
    `plan` event. Otherwise None so the caller skips the emit."""
    if not isinstance(updates, dict):
        return None
    todos = updates.get("todos")
    if not isinstance(todos, list):
        return None
    out: list[dict[str, Any]] = []
    for t in todos:
        if isinstance(t, dict):
            out.append(
                {
                    "task": t.get("content") or t.get("task") or "",
                    "status": t.get("status", "pending"),
                }
            )
    return out or None


def _translate_update(
    node: str, update: Any
) -> list[str]:
    """Map a single `updates` payload into 0..N SSE frames."""
    frames: list[str] = []
    if not isinstance(update, dict):
        return frames

    todos = _render_todos(update)
    if todos is not None:
        frames.append(sse_event("plan", {"todos": todos}))

    messages = update.get("messages")
    if isinstance(messages, list):
        for msg in messages:
            if isinstance(msg, AIMessage):
                # Tool calls launching a sub-agent → subagent_start.
                for tc in getattr(msg, "tool_calls", []) or []:
                    if tc.get("name") == "task":
                        args = tc.get("args") or {}
                        target = (
                            args.get("subagent_type")
                            or args.get("name")
                            or "unknown"
                        )
                        brief = (
                            args.get("description") or args.get("task") or ""
                        )
                        frames.append(
                            sse_event(
                                "subagent_start",
                                {"name": target, "task": str(brief)[:400]},
                            )
                        )
            elif isinstance(msg, ToolMessage) and getattr(msg, "name", "") == "task":
                # The `task` tool return message — sub-agent finished.
                summary = _extract_chunk_text(msg) or "done"
                frames.append(
                    sse_event(
                        "subagent_end",
                        {"name": "unknown", "summary": summary[:400]},
                    )
                )
    return frames


async def orchestrator_event_stream(
    orchestrator: CompiledStateGraph,
    *,
    initial_state: dict[str, Any],
    context: dict[str, Any],
    settings: Settings,
    final_response_factory: Any,
) -> AsyncIterator[str]:
    """Drive the orchestrator and yield SSE frames.

    `final_response_factory(sink) -> BaseModel` is called once at the end
    and its `model_dump()` is shipped on the `done` event. `sink` is the
    same `EditSink` the `emit_edit` tool writes into — the endpoint
    binds it before calling this function."""
    it = orchestrator.astream(
        initial_state,
        stream_mode=["messages", "updates", "custom"],
        subgraphs=True,
        context=context,
    )

    try:
        while True:
            try:
                chunk = await asyncio.wait_for(
                    it.__anext__(), timeout=settings.LLM_TIMEOUT_SECONDS
                )
            except StopAsyncIteration:
                break
            except asyncio.TimeoutError:
                yield sse_event(
                    "error",
                    {"error": "per-event timeout", "step": "stream"},
                )
                return

            # With subgraphs=True, chunks are (namespace, mode, data).
            # Without subgraphs, they are (mode, data). Handle both.
            if isinstance(chunk, tuple) and len(chunk) == 3:
                namespace, mode, data = chunk
            elif isinstance(chunk, tuple) and len(chunk) == 2:
                namespace, (mode, data) = (), chunk
            else:
                continue

            if mode == "messages":
                if not _is_top_level(namespace):
                    continue  # squelch sub-agent tokens in v1
                if not isinstance(data, tuple) or len(data) < 1:
                    continue
                msg = data[0]
                if not isinstance(msg, AIMessageChunk | AIMessage):
                    continue
                text = _extract_chunk_text(msg)
                if text:
                    yield sse_event("token", {"delta": text})

            elif mode == "updates":
                # `data` is {node_name: update_payload}.
                if isinstance(data, dict):
                    for node, upd in data.items():
                        for frame in _translate_update(node, upd):
                            yield frame

            elif mode == "custom":
                if isinstance(data, dict) and data.get("kind") == "edit":
                    yield sse_event("edit", data.get("edit") or {})

        # End-of-stream: build the final DTO from the collected edits sink.
        sink = current_sink() or []
        try:
            final = final_response_factory(sink)
            yield sse_event("done", final.model_dump())
        except Exception as exc:  # noqa: BLE001
            logger.exception("orchestrator_event_stream: final_response_factory failed")
            yield sse_event(
                "error",
                {"error": str(exc), "step": "final_response_factory"},
            )

    except asyncio.CancelledError:
        raise
    except Exception as exc:  # noqa: BLE001
        logger.exception("orchestrator_event_stream: provider error")
        yield sse_event("error", {"error": str(exc), "step": "stream"})
    finally:
        aclose = getattr(it, "aclose", None)
        if aclose is not None:
            try:
                await aclose()
            except Exception:  # noqa: BLE001
                pass


__all__ = ["orchestrator_event_stream"]
