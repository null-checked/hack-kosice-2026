"""DEMO_MOCK trace generator for `/chat` with `mode="agent"`.

Purpose: give the plugin + dashboard a plausible "this is what a real
multi-agent run looks like" event timeline without ever loading an LLM.
The sequence matches the live `orchestrator_event_stream_as_chat_events`
event vocabulary (ChatXXXEvent dicts) so UI work against the mock is
directly applicable to live runs.

Sequence emitted (in order):

    token*                 orchestrator prose (dripped)
    tool_call:write_todos  a 3-entry planning todo list
    tool_call:task         dispatch code-writer
    code_edit              fizzbuzz.py
    tool_call:task_return  code-writer summary
    tool_call:task         dispatch test-writer
    code_edit              tests/test_fizzbuzz.py
    tool_call:task_return  test-writer summary
    tool_call:task         dispatch reviewer
    tool_call:task_return  reviewer summary (LGTM)
    done                   finish_reason=stop

Token drip is fast (~50 ms/chunk) to keep the tests snappy while still
exercising multiple frames.
"""

from __future__ import annotations

import asyncio
from collections.abc import AsyncIterator
from typing import Any

# Self-contained fixtures — we don't reach into `mock_fixtures.py` because
# this trace is orchestrator-specific and those fixtures already have a
# narrower purpose (the envelope-mode mock uses them).

MOCK_AGENT_ANSWER = (
    "Ran code-writer, then test-writer, then reviewer. "
    "Produced fizzbuzz.py with the classic rule and tests/test_fizzbuzz.py "
    "covering 0, positives, and the three FizzBuzz branches. Reviewer said LGTM."
)

MOCK_AGENT_EDITS: list[dict[str, Any]] = [
    {
        "file_path": "fizzbuzz.py",
        "original_code": "",
        "proposed_code": (
            "def fizzbuzz(n: int) -> str:\n"
            '    if n % 15 == 0:\n'
            '        return "FizzBuzz"\n'
            '    if n % 3 == 0:\n'
            '        return "Fizz"\n'
            '    if n % 5 == 0:\n'
            '        return "Buzz"\n'
            '    return str(n)\n'
        ),
        "explanation": "Classic FizzBuzz with divisor-first branching.",
        "start_line": None,
        "end_line": None,
        "diff_unified": "",
    },
    {
        "file_path": "tests/test_fizzbuzz.py",
        "original_code": "",
        "proposed_code": (
            "import pytest\n\n"
            "from fizzbuzz import fizzbuzz\n\n\n"
            "@pytest.mark.parametrize(\n"
            '    ("n", "expected"),\n'
            "    [\n"
            '        (1, "1"),\n'
            '        (3, "Fizz"),\n'
            '        (5, "Buzz"),\n'
            '        (15, "FizzBuzz"),\n'
            '        (0, "FizzBuzz"),\n'
            "    ],\n"
            ")\n"
            "def test_fizzbuzz(n, expected):\n"
            "    assert fizzbuzz(n) == expected\n"
        ),
        "explanation": "Parametrized pytest covering the three branches + 0 edge case.",
        "start_line": None,
        "end_line": None,
        "diff_unified": "",
    },
]


_TOKEN_DELAY_SEC = 0.05


def _drip(text: str, chunk_size: int = 7) -> list[str]:
    """Split `text` into fixed-width chunks to simulate an LLM typing."""
    if chunk_size <= 0:
        return [text]
    return [text[i : i + chunk_size] for i in range(0, len(text), chunk_size)]


async def iter_demo_agent_trace() -> AsyncIterator[dict[str, Any]]:
    """Yield ChatXXXEvent dicts for DEMO_MOCK `/chat` with `mode="agent"`.

    Shape matches `orchestrator_event_stream_as_chat_events` so the
    downstream `_event_to_json` + Pydantic validation path in
    `chat_stream.py` treats the mock and the live stream identically.
    """
    # 1. Prose tokens first — simulates the orchestrator warming up.
    for chunk in _drip(MOCK_AGENT_ANSWER, chunk_size=7):
        yield {"type": "token", "delta": chunk}
        await asyncio.sleep(_TOKEN_DELAY_SEC)

    # 2. Planning — surfaces in the UI as a todo-list chip.
    yield {
        "type": "tool_call",
        "name": "write_todos",
        "arguments": {
            "todos": [
                {"task": "Ask code-writer to produce fizzbuzz.py", "status": "in_progress"},
                {"task": "Ask test-writer to cover it with pytest", "status": "pending"},
                {"task": "Ask reviewer to skim and refine", "status": "pending"},
            ]
        },
    }

    # 3. code-writer dispatch + edit + return.
    yield {
        "type": "tool_call",
        "name": "task",
        "arguments": {
            "subagent": "code-writer",
            "brief": "Write fizzbuzz.py implementing the classic rule.",
        },
    }
    await asyncio.sleep(0.05)
    yield {
        "type": "code_edit",
        "edit_id": "mock-edit-code-writer",
        **MOCK_AGENT_EDITS[0],
    }
    yield {
        "type": "tool_call",
        "name": "task_return",
        "arguments": {"summary": "Emitted 1 edit: fizzbuzz.py."},
    }

    # 4. test-writer dispatch + edit + return.
    yield {
        "type": "tool_call",
        "name": "task",
        "arguments": {
            "subagent": "test-writer",
            "brief": "Add pytest coverage for the FizzBuzz mapping.",
        },
    }
    await asyncio.sleep(0.05)
    yield {
        "type": "code_edit",
        "edit_id": "mock-edit-test-writer",
        **MOCK_AGENT_EDITS[1],
    }
    yield {
        "type": "tool_call",
        "name": "task_return",
        "arguments": {"summary": "Emitted 1 edit: tests/test_fizzbuzz.py."},
    }

    # 5. reviewer — runs last, LGTM (no edit).
    yield {
        "type": "tool_call",
        "name": "task",
        "arguments": {
            "subagent": "reviewer",
            "brief": "Review the code + tests for refinements.",
        },
    }
    await asyncio.sleep(0.05)
    yield {
        "type": "tool_call",
        "name": "task_return",
        "arguments": {
            "summary": "LGTM — coverage includes 0 and the three branches.",
        },
    }

    # 6. Terminal done.
    yield {"type": "done", "finish_reason": "stop"}


__all__ = ["iter_demo_agent_trace", "MOCK_AGENT_ANSWER", "MOCK_AGENT_EDITS"]
