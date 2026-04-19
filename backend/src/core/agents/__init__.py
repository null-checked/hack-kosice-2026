"""Multi-agent orchestration layer for /code-chat + /code-chat/stream.

Public surface:

- `build_orchestrator(settings)` returns a compiled DeepAgents graph (or None
  when the orchestrator can't be constructed — DEMO_MOCK, no API key, or
  ORCHESTRATOR_ENABLED=False).
- `EditSink` — request-scoped list of emitted edits, populated by the
  `emit_edit` tool during `ainvoke` / `astream`.
- `RubberDuckContext` — TypedDict passed through `context_schema`.
- `orchestrator_event_stream(...)` — async generator yielding SSE frames.
- `iter_demo_trace(...)` — DEMO_MOCK SSE trace for /code-chat/stream.
"""

from __future__ import annotations

from .collector import EditSink, bind_edit_sink
from .context import RubberDuckContext
from .mock_trace import iter_demo_trace, mock_orchestrator_response
from .orchestrator import build_orchestrator
from .sse import orchestrator_event_stream

__all__ = [
    "EditSink",
    "RubberDuckContext",
    "bind_edit_sink",
    "build_orchestrator",
    "iter_demo_trace",
    "mock_orchestrator_response",
    "orchestrator_event_stream",
]
