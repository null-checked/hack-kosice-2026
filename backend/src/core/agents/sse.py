"""Translate a DeepAgents `astream(...)` into ChatXXXEvent dicts.

This module is the bridge between the DeepAgents orchestrator (which speaks
LangGraph's native stream vocabulary: `messages`/`updates`/`custom` chunks)
and the unified-chat wire protocol in `chat_stream.py` (typed dict events
serialised through Pydantic models: `token` / `tool_call` / `code_edit` /
`usage` / `done` / `error`).

The contract is: yield plain dicts with a `type` discriminator. The caller
(`chat_stream.stream_chat`) will pipe them through `_event_to_json` →
`ChatXXXEvent(**event).model_dump_json()` → `data: {json}\\n\\n` framing.

## Mapping from LangGraph chunks to ChatXXXEvent dicts

| LangGraph chunk                                        | Yielded dict                                                                                     |
| ------------------------------------------------------ | ------------------------------------------------------------------------------------------------ |
| `messages` on top-level namespace, AIMessage(Chunk)    | `{"type":"token","delta": text}`                                                                  |
| `updates` node payload with a `todos` key              | `{"type":"tool_call","name":"write_todos","arguments":{"todos":[{task,status},...]}}`            |
| `updates` AIMessage with `tool_calls[{name=='task'}]`  | `{"type":"tool_call","name":"task","arguments":{"subagent":<name>,"brief":<str>}}`               |
| `updates` ToolMessage with `name=='task'`              | `{"type":"tool_call","name":"task_return","arguments":{"summary":<str>}}`                         |
| `custom` payload `{"kind":"edit","agent":...,"edit":}` | `{"type":"code_edit", **edit, "edit_id": uuid4()}` (server-computes `diff_unified` if missing)   |
| stream completes                                       | `{"type":"done","finish_reason":"stop"}`                                                          |
| any exception                                          | `{"type":"error","message": str(exc)}` + `{"type":"done","finish_reason":"error"}`                 |

## Why `tool_call` is overloaded for `write_todos` / `task` / `task_return`

The unified-chat ChatXXXEvent union is deliberately narrow — adding new
event types would force coordinated changes across backend + webui +
plugin JCEF bridge + VS Code extension. The existing
`ChatToolCallEvent(name, arguments)` frame is general-purpose: any tool
invocation the agent makes can be surfaced through it. We reuse that one
event type to render write_todos planning updates AND sub-agent
dispatch/return chips in the webui, switching on `arguments` on the
client side. That keeps the wire format closed.

## Why `code_edit` events arrive inline (not in a final DTO)

The old deep-agents branch had a `final_response_factory(sink) -> DTO`
pattern that read edits from the request-scoped `EditSink` at end-of-
stream. In the unified chat, each edit is its own event — so we rely on
the `custom` stream-mode channel populated by `make_emit_edit(...)` which
calls `get_stream_writer()(...)` from inside the tool body. The
`EditSink` ContextVar is still bound (for the non-streaming `/chat`
collection path), but the streaming path here emits edits the moment
they're produced.
"""

from __future__ import annotations

import asyncio
import logging
import uuid
from collections.abc import AsyncIterator
from typing import Any

from langchain_core.messages import AIMessage, AIMessageChunk, BaseMessage, ToolMessage
from langgraph.graph.state import CompiledStateGraph

from src.core.chat_edits import unified_diff_for_edit
from src.models.api import CodeEdit
from src.settings import Settings

logger = logging.getLogger(__name__)


def _extract_chunk_text(msg: BaseMessage) -> str:
    """Pull plain text out of a message or message-chunk.

    Content can be `str` or a list of typed blocks (Anthropic / multimodal
    models use the latter). We take only `text` blocks and concatenate.
    """
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
    """Extract a flattened list of todo dicts, or None if no todos here."""
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


def _translate_update(update: Any) -> list[dict[str, Any]]:
    """Map a single `updates` payload into 0..N ChatXXXEvent dicts."""
    frames: list[dict[str, Any]] = []
    if not isinstance(update, dict):
        return frames

    todos = _render_todos(update)
    if todos is not None:
        frames.append(
            {
                "type": "tool_call",
                "name": "write_todos",
                "arguments": {"todos": todos},
            }
        )

    messages = update.get("messages")
    if isinstance(messages, list):
        for msg in messages:
            if isinstance(msg, AIMessage):
                # Tool calls launching a sub-agent → task dispatch chip.
                for tc in getattr(msg, "tool_calls", []) or []:
                    if tc.get("name") != "task":
                        continue
                    args = tc.get("args") or {}
                    target = (
                        args.get("subagent_type")
                        or args.get("name")
                        or "unknown"
                    )
                    brief = args.get("description") or args.get("task") or ""
                    frames.append(
                        {
                            "type": "tool_call",
                            "name": "task",
                            "arguments": {
                                "subagent": target,
                                "brief": str(brief)[:400],
                            },
                        }
                    )
            elif isinstance(msg, ToolMessage) and getattr(msg, "name", "") == "task":
                # The `task` tool return message — sub-agent finished.
                summary = _extract_chunk_text(msg) or "done"
                frames.append(
                    {
                        "type": "tool_call",
                        "name": "task_return",
                        "arguments": {"summary": summary[:400]},
                    }
                )
    return frames


def _edit_to_event(raw: dict[str, Any]) -> dict[str, Any] | None:
    """Coerce a `custom` stream payload `{"edit": {...}}` into a code_edit dict.

    Returns None if the payload isn't a valid CodeEdit — we don't want a
    single malformed emit to kill the whole stream. Falls back to
    computing `diff_unified` server-side when the tool didn't supply one.
    """
    try:
        edit = CodeEdit(**raw)
    except Exception as exc:  # noqa: BLE001
        logger.warning("Skipping malformed custom edit payload: %s", exc)
        return None
    if not edit.diff_unified:
        edit = edit.model_copy(update={"diff_unified": unified_diff_for_edit(edit)})
    return {
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


async def orchestrator_event_stream_as_chat_events(
    orchestrator: CompiledStateGraph,
    *,
    initial_state: dict[str, Any],
    context: dict[str, Any],
    settings: Settings,
) -> AsyncIterator[dict[str, Any]]:
    """Drive the orchestrator and yield ChatXXXEvent dicts.

    Terminates with exactly one `done` event on success, or `error` +
    `done` on failure. Uses a per-event timeout (`LLM_TIMEOUT_SECONDS`)
    so a stuck provider call eventually surfaces as an error rather than
    hanging the HTTP response indefinitely.
    """
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
                yield {"type": "error", "message": "per-event timeout"}
                yield {"type": "done", "finish_reason": "error"}
                return

            # With subgraphs=True, chunks are (namespace, mode, data).
            # Without subgraphs, they are (mode, data). Handle both for safety.
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
                if not isinstance(msg, (AIMessageChunk, AIMessage)):
                    continue
                text = _extract_chunk_text(msg)
                if text:
                    yield {"type": "token", "delta": text}

            elif mode == "updates":
                if isinstance(data, dict):
                    for _node, upd in data.items():
                        for frame in _translate_update(upd):
                            yield frame

            elif mode == "custom":
                if isinstance(data, dict) and data.get("kind") == "edit":
                    ev = _edit_to_event(data.get("edit") or {})
                    if ev is not None:
                        yield ev

        yield {"type": "done", "finish_reason": "stop"}

    except asyncio.CancelledError:
        raise  # let the FastAPI stream teardown observe the client disconnect
    except Exception as exc:  # noqa: BLE001
        logger.exception("orchestrator stream: provider error")
        yield {"type": "error", "message": str(exc) or exc.__class__.__name__}
        yield {"type": "done", "finish_reason": "error"}
    finally:
        aclose = getattr(it, "aclose", None)
        if aclose is not None:
            try:
                await aclose()
            except Exception:  # noqa: BLE001
                pass


__all__ = ["orchestrator_event_stream_as_chat_events"]
