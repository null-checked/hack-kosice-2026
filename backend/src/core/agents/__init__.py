"""Multi-agent orchestration layer for `/chat` with `mode="agent"`.

Public surface:

- `build_orchestrator(settings)` returns a compiled DeepAgents graph (or
  None when the orchestrator can't be constructed — DEMO_MOCK, no API
  key, non-OpenAI provider, or `ORCHESTRATOR_ENABLED=False`).
- `orchestrator_event_stream_as_chat_events(orchestrator, *, initial_state,
  context, settings)` — async generator yielding ChatXXXEvent dicts that
  `chat_stream.py` pipes through `_event_to_json` + SSE framing.
- `iter_demo_agent_trace()` — DEMO_MOCK ChatXXXEvent trace for the
  agent-mode branch when no LLM is available.
- `EditSink`, `bind_edit_sink`, `reset_edit_sink` — request-scoped
  collector for `emit_edit` side effects (used by non-streaming callers).
- `RubberDuckContext` — TypedDict passed through `context_schema`.
"""

from __future__ import annotations

from .collector import EditSink, bind_edit_sink, reset_edit_sink
from .context import RubberDuckContext
from .mock_trace import MOCK_AGENT_ANSWER, MOCK_AGENT_EDITS, iter_demo_agent_trace
from .orchestrator import build_orchestrator
from .sse import orchestrator_event_stream_as_chat_events

__all__ = [
    "MOCK_AGENT_ANSWER",
    "MOCK_AGENT_EDITS",
    "EditSink",
    "RubberDuckContext",
    "bind_edit_sink",
    "build_orchestrator",
    "iter_demo_agent_trace",
    "orchestrator_event_stream_as_chat_events",
    "reset_edit_sink",
]
