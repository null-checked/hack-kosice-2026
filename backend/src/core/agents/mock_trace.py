"""DEMO_MOCK trace generator for `/code-chat/stream`.

Purpose: give the plugin + dashboard a plausible "this is what a real
multi-agent run looks like" SSE timeline without ever loading an LLM.
The sequence matches the real event shape so UI work against the mock
port is directly applicable to live runs.

Sequence emitted (in order):

    token*                 orchestrator prose (dripped)
    plan                   a 3-entry todo list
    subagent_start         code-writer
    edit                   fizzbuzz.py
    subagent_end           code-writer
    subagent_start         test-writer
    edit                   tests/test_fizzbuzz.py
    subagent_end           test-writer
    subagent_start         reviewer
    subagent_end           reviewer (LGTM, no edit)
    done                   CodeChatResponse

Token drip is real-time (20 Hz) to approximate an LLM typing.
"""

from __future__ import annotations

import asyncio
from collections.abc import AsyncIterator

from src.core.mock_fixtures import (
    MOCK_CODE_EDITS,
    MOCK_ORCHESTRATOR_ANSWER,
    drip_tokens,
)
from src.core.streaming import sse_event
from src.models.api import CodeChatResponse, CodeEdit

_TOKEN_DELAY_SEC = 0.05


def mock_orchestrator_response() -> CodeChatResponse:
    """Build the non-streaming `/code-chat` mock response with populated edits.

    Shared with `code_chat.py:_mock_answer` via the `allow_edits=True`
    branch so the SSE stream and the non-streaming endpoint return
    compatible data under DEMO_MOCK."""
    return CodeChatResponse(
        answer=MOCK_ORCHESTRATOR_ANSWER,
        model="mock-orchestrator",
        edits=[CodeEdit(**e) for e in MOCK_CODE_EDITS],
    )


async def iter_demo_trace() -> AsyncIterator[str]:
    """Yield SSE frames for the DEMO_MOCK `/code-chat/stream` path."""
    # 1. Prose tokens first — simulates the orchestrator warming up.
    for chunk in drip_tokens(MOCK_ORCHESTRATOR_ANSWER, chunk_size=7):
        yield sse_event("token", {"delta": chunk})
        await asyncio.sleep(_TOKEN_DELAY_SEC)

    # 2. The orchestrator's plan.
    yield sse_event(
        "plan",
        {
            "todos": [
                {"task": "Ask code-writer to produce fizzbuzz.py", "status": "in_progress"},
                {"task": "Ask test-writer to cover it with pytest", "status": "pending"},
                {"task": "Ask reviewer to skim and refine", "status": "pending"},
            ]
        },
    )

    # 3. code-writer.
    yield sse_event(
        "subagent_start",
        {"name": "code-writer", "task": "Write fizzbuzz.py implementing the classic rule."},
    )
    await asyncio.sleep(0.15)
    yield sse_event("edit", MOCK_CODE_EDITS[0])
    yield sse_event(
        "subagent_end",
        {"name": "code-writer", "summary": "Emitted 1 edit: fizzbuzz.py."},
    )

    # 4. test-writer.
    yield sse_event(
        "subagent_start",
        {
            "name": "test-writer",
            "task": "Add pytest coverage for the FizzBuzz mapping.",
        },
    )
    await asyncio.sleep(0.15)
    yield sse_event("edit", MOCK_CODE_EDITS[1])
    yield sse_event(
        "subagent_end",
        {"name": "test-writer", "summary": "Emitted 1 edit: tests/test_fizzbuzz.py."},
    )

    # 5. reviewer (LGTM, no edit).
    yield sse_event(
        "subagent_start",
        {"name": "reviewer", "task": "Review the code + tests for refinements."},
    )
    await asyncio.sleep(0.10)
    yield sse_event(
        "subagent_end",
        {
            "name": "reviewer",
            "summary": "LGTM — coverage includes 0, negatives, and the three branches.",
        },
    )

    # 6. Terminal `done` with the full response DTO.
    yield sse_event("done", mock_orchestrator_response().model_dump())


__all__ = ["iter_demo_trace", "mock_orchestrator_response"]
