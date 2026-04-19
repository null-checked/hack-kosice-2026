"""Request-scoped edit collector backed by a `ContextVar`.

Why a ContextVar? Every invocation of the orchestrator (whether through
`ainvoke` or `astream`) produces a set of `emit_edit` tool calls scattered
across the orchestrator itself plus three sub-agents, plus whatever
sub-sub-agents they spawn. The tool implementation doesn't know how to reach
back to the FastAPI endpoint that started the run — and LangChain tools can't
return an arbitrary Python object to the caller anyway.

A `ContextVar` solves this: the endpoint binds a fresh list before invoking
the agent, the tool appends to whatever list the current async-task context
points at, and the endpoint reads it out when the run completes. This works
identically for `ainvoke` (read at the end) and `astream` (read incrementally
as the SSE stream flows). `asyncio` copies context vars per task, so nested
sub-agents see the same sink automatically.
"""

from __future__ import annotations

import contextvars
from typing import Any

# Each list entry is `{"agent": str, "edit": dict}` where `edit` is a
# `CodeEdit.model_dump()` (pydantic JSON-ready). Using a plain list (instead
# of a typed structure) keeps the tool free of cross-package imports.
EditSink = list[dict[str, Any]]

_CURRENT_SINK: contextvars.ContextVar[EditSink | None] = contextvars.ContextVar(
    "rubberduck_edit_sink", default=None
)


def bind_edit_sink(sink: EditSink) -> contextvars.Token[EditSink | None]:
    """Install `sink` as the current collector. Returns a token to pass to
    `_CURRENT_SINK.reset(...)` for cleanup — the endpoint wraps `ainvoke` in
    a `try/finally` so binding is always scoped to the one request."""
    return _CURRENT_SINK.set(sink)


def reset_edit_sink(token: contextvars.Token[EditSink | None]) -> None:
    _CURRENT_SINK.reset(token)


def current_sink() -> EditSink | None:
    return _CURRENT_SINK.get()


def append_entry(entry: dict[str, Any]) -> None:
    """Append one `{agent, edit}` entry to the active sink, if any.

    Silently no-ops when no sink is bound (e.g. unit test that runs the tool
    directly without going through the endpoint)."""
    sink = _CURRENT_SINK.get()
    if sink is not None:
        sink.append(entry)


def count_by_agent(sink: EditSink | None) -> dict[str, int]:
    """Helper: count sink entries grouped by `agent` key. Used by OrderingGate."""
    counts: dict[str, int] = {}
    if not sink:
        return counts
    for entry in sink:
        name = str(entry.get("agent", "unknown"))
        counts[name] = counts.get(name, 0) + 1
    return counts
