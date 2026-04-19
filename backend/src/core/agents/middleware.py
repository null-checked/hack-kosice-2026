"""`OrderingGate` — enforces code-writer → test-writer → reviewer ordering.

Background: the orchestrator can call `task(subagent_type="...")` to hand
off to one of its sub-agents. Without a gate, the LLM often tries to run
the reviewer before there are any edits to review, or launches the
test-writer before any code exists. We solve this at middleware level
rather than via prompt wishful-thinking.

Implementation notes (Langchain agents 1.x changed the API; plan was
written assuming older `before_tool` + `ToolInterrupt`):

-   The current hook is `wrap_tool_call(request, handler)`. It's called
    ONCE per tool-call the agent wants to execute, wrapping the normal
    handler. To intercept: inspect `request.tool_call`, and if we want
    to block, return a synthetic `ToolMessage` instead of delegating to
    `handler(request)`. The LLM sees that message as the tool's
    response and can re-plan.
-   `request.tool_call` is a `ToolCall` TypedDict with keys `name`,
    `args`, `id`. The `task` tool is what DeepAgents injects for
    sub-agent handoff; its args include `subagent_type` (or `name`,
    depending on version), and we inspect both.
-   Gating counts come from the request-scoped `EditSink` collector,
    not from LangGraph state. This is consistent with `tools.py`'s
    decision to use a ContextVar instead of a state-schema channel.
-   Nested sub-sub-agents (general-purpose tasks spawned BY a sub-agent)
    are not gated. Only the orchestrator's top-level `task` calls go
    through this middleware instance.
"""

from __future__ import annotations

import logging
from collections.abc import Awaitable, Callable
from typing import Any

from langchain.agents.middleware import AgentMiddleware
from langchain.agents.middleware.types import ToolCallRequest
from langchain_core.messages import ToolMessage
from langgraph.types import Command

from .collector import count_by_agent, current_sink

logger = logging.getLogger(__name__)


# Target-name aliases: deepagents versions have flipped between
# `subagent_type` and `name` on the `task` tool. We probe both.
_TARGET_KEYS = ("subagent_type", "name")

_CODE_WRITER = "code-writer"
_TEST_WRITER = "test-writer"
_REVIEWER = "reviewer"


def _extract_target(tool_call_args: dict[str, Any]) -> str | None:
    """Return the sub-agent name the `task` call is trying to invoke, or
    None if we can't find one (in which case we let the call through —
    gating is opt-in, not fail-closed)."""
    for key in _TARGET_KEYS:
        val = tool_call_args.get(key)
        if isinstance(val, str) and val:
            return val
    return None


def _blocked_message(tool_call_id: str, reason: str) -> ToolMessage:
    """Produce the ToolMessage the LLM will see in place of a real tool
    result. Phrased as a directive so the LLM re-plans instead of
    retrying the same call."""
    return ToolMessage(
        content=f"BLOCKED: {reason} Invoke a different sub-agent first.",
        tool_call_id=tool_call_id,
        status="error",
    )


class OrderingGate(AgentMiddleware):
    """Block out-of-order sub-agent handoffs at the orchestrator level.

    Rules:
      - `task(test-writer)` blocked until `code-writer` has emitted ≥ 1 edit.
      - `task(reviewer)` blocked until both `code-writer` AND `test-writer`
         have emitted ≥ 1 edit.
      - Any other `task(...)` call passes through unchanged.
      - Any non-`task` tool call passes through unchanged.

    The "has emitted" check reads the request-scoped `EditSink` via
    `count_by_agent` — same source of truth the final response builder
    uses, so the gate and the response can never disagree."""

    name = "orchestrator_ordering_gate"

    def _maybe_block(self, request: ToolCallRequest) -> ToolMessage | None:
        tool_call = request.tool_call
        if tool_call.get("name") != "task":
            return None
        args = tool_call.get("args") or {}
        target = _extract_target(args)
        if target is None:
            return None

        counts = count_by_agent(current_sink())

        if target == _TEST_WRITER and counts.get(_CODE_WRITER, 0) == 0:
            logger.info("OrderingGate: blocking test-writer (code-writer has no edits yet)")
            return _blocked_message(
                tool_call["id"],
                "test-writer must run AFTER code-writer; no code-writer edits yet.",
            )
        if target == _REVIEWER and (
            counts.get(_CODE_WRITER, 0) == 0 or counts.get(_TEST_WRITER, 0) == 0
        ):
            logger.info("OrderingGate: blocking reviewer (predecessors not complete)")
            return _blocked_message(
                tool_call["id"],
                "reviewer must run LAST, after both code-writer AND test-writer have emitted.",
            )
        return None

    def wrap_tool_call(
        self,
        request: ToolCallRequest,
        handler: Callable[[ToolCallRequest], "ToolMessage | Command[Any]"],
    ) -> "ToolMessage | Command[Any]":
        blocked = self._maybe_block(request)
        if blocked is not None:
            return blocked
        return handler(request)

    async def awrap_tool_call(
        self,
        request: ToolCallRequest,
        handler: Callable[[ToolCallRequest], Awaitable["ToolMessage | Command[Any]"]],
    ) -> "ToolMessage | Command[Any]":
        blocked = self._maybe_block(request)
        if blocked is not None:
            return blocked
        return await handler(request)


__all__ = ["OrderingGate"]
