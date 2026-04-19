"""Tests for `/api/v1/chat` with `mode="agent"` — the DeepAgents branch.

Four scenarios, all under DEMO_MOCK=true:

1. `test_chat_agent_mode_emits_expected_event_sequence` — the canned
   agent trace emits tokens → write_todos plan → three task dispatches
   (code-writer/test-writer/reviewer) → two code_edit frames → three
   task_return → done.

2. `test_chat_agent_mode_non_streaming_returns_content` — POST with
   `stream=false` still works: the collector consumes the same event
   stream server-side and returns the prose in `ChatResponse.content`.

3. `test_chat_mode_ask_does_not_invoke_orchestrator` — perf guard. With
   `mode="ask"`, NO tool_call or code_edit events; the MockLLMClient
   path is used instead.

4. `test_chat_agent_mode_surfaces_error_when_orchestrator_unavailable` —
   under DEMO_MOCK=False with no compiled orchestrator, the dispatcher
   emits a structured error event + done rather than raising mid-stream.
"""

from __future__ import annotations

import json
from typing import Any

import pytest
from httpx import ASGITransport, AsyncClient

from src.main import app


# ---------- SSE parser (reused shape from test_chat.py) ----------


def _parse_sse_events(body: str) -> list[dict[str, Any]]:
    events: list[dict[str, Any]] = []
    for line in body.splitlines():
        if not line.startswith("data:"):
            continue
        payload = line[5:].strip()
        if payload == "[DONE]" or not payload:
            continue
        try:
            events.append(json.loads(payload))
        except json.JSONDecodeError:
            pytest.fail(f"Non-JSON SSE payload: {payload!r}")
    return events


# ---------- Client fixture ----------


@pytest.fixture
async def client():
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as ac:
        async with app.router.lifespan_context(app):
            yield ac


# ---------- (1) Full event sequence ----------


@pytest.mark.asyncio
async def test_chat_agent_mode_emits_expected_event_sequence(client: AsyncClient) -> None:
    async with client.stream(
        "POST",
        "/api/v1/chat",
        json={
            "messages": [
                {
                    "role": "user",
                    "content": "Create fizzbuzz.py with the classic rule and a pytest.",
                }
            ],
            "mode": "agent",
            "stream": True,
        },
    ) as resp:
        assert resp.status_code == 200, await resp.aread()
        assert resp.headers["content-type"].startswith("text/event-stream")
        body = ""
        async for chunk in resp.aiter_text():
            body += chunk

    assert "data: [DONE]" in body, body
    events = _parse_sse_events(body)
    types = [e.get("type") for e in events]

    # Tokens dripped from the mock orchestrator answer.
    assert "token" in types, f"no token events: {types}"

    # Exactly one write_todos planning chip.
    tool_calls = [e for e in events if e.get("type") == "tool_call"]
    todo_calls = [t for t in tool_calls if t.get("name") == "write_todos"]
    assert len(todo_calls) == 1, f"expected one write_todos, got {len(todo_calls)}"
    assert isinstance(todo_calls[0].get("arguments", {}).get("todos"), list)

    # Three sub-agent dispatches in mandatory order.
    task_dispatches = [t for t in tool_calls if t.get("name") == "task"]
    assert len(task_dispatches) == 3, f"expected 3 task dispatches, got {len(task_dispatches)}"
    dispatched_names = [t["arguments"]["subagent"] for t in task_dispatches]
    assert dispatched_names == ["code-writer", "test-writer", "reviewer"]

    # Three task_return frames, one per dispatch.
    task_returns = [t for t in tool_calls if t.get("name") == "task_return"]
    assert len(task_returns) == 3, f"expected 3 task_returns, got {len(task_returns)}"

    # Exactly two code_edit events (code-writer + test-writer; reviewer LGTMs).
    code_edits = [e for e in events if e.get("type") == "code_edit"]
    assert len(code_edits) == 2, f"expected 2 code_edits, got {len(code_edits)}"
    edit_paths = {e["file_path"] for e in code_edits}
    assert edit_paths == {"fizzbuzz.py", "tests/test_fizzbuzz.py"}
    for e in code_edits:
        assert e["edit_id"]
        assert e["proposed_code"].strip()
        # mock_trace yields diff_unified empty → the sse.py helper populates
        # it server-side for live runs; the mock path keeps the empty string.

    # Terminal done, no errors.
    assert "error" not in types, f"unexpected error event in {types}"
    assert types[-1] == "done", f"last event must be done, got {types[-1]!r}"
    assert events[-1].get("finish_reason") == "stop"


# ---------- (2) Non-streaming path ----------


@pytest.mark.asyncio
async def test_chat_agent_mode_non_streaming_returns_content(client: AsyncClient) -> None:
    resp = await client.post(
        "/api/v1/chat",
        json={
            "messages": [
                {"role": "user", "content": "Anything that triggers the agent trace."}
            ],
            "mode": "agent",
            "stream": False,
        },
    )
    assert resp.status_code == 200, resp.text
    body = resp.json()
    # The non-streaming collector reassembles token deltas into `content`.
    # mock_trace drips MOCK_AGENT_ANSWER → content should be exactly that.
    from src.core.agents import MOCK_AGENT_ANSWER

    assert body["content"] == MOCK_AGENT_ANSWER
    assert body["finish_reason"] == "stop"


# ---------- (3) Ask mode is untouched (perf guard) ----------


@pytest.mark.asyncio
async def test_chat_mode_ask_does_not_invoke_orchestrator(client: AsyncClient) -> None:
    # Explicit mode="ask" — plain chat, no orchestrator, no code_edit.
    async with client.stream(
        "POST",
        "/api/v1/chat",
        json={
            "messages": [{"role": "user", "content": "Say hi."}],
            "mode": "ask",
            "stream": True,
        },
    ) as resp:
        assert resp.status_code == 200
        body = ""
        async for chunk in resp.aiter_text():
            body += chunk

    events = _parse_sse_events(body)
    types = [e.get("type") for e in events]
    assert "token" in types
    assert "code_edit" not in types, f"ask mode must not emit code_edit: {types}"
    assert "tool_call" not in types, f"ask mode must not emit tool_call: {types}"


# ---------- (4) Degradation when orchestrator is absent ----------


@pytest.mark.asyncio
async def test_chat_agent_mode_surfaces_error_when_orchestrator_unavailable(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Under DEMO_MOCK=False with `app.state.orchestrator is None`, agent
    mode must emit a structured `error` + `done` rather than raising."""
    from src.core import chat_stream

    # chat_stream imports settings as `app_settings`; toggle DEMO_MOCK off
    # for this test only so the agent-mode branch skips the demo trace.
    monkeypatch.setattr(chat_stream.app_settings, "DEMO_MOCK", False)

    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as ac:
        async with app.router.lifespan_context(app):
            # Force the orchestrator to None for this test.
            monkeypatch.setattr(app.state, "orchestrator", None)

            async with ac.stream(
                "POST",
                "/api/v1/chat",
                json={
                    "messages": [{"role": "user", "content": "build something"}],
                    "mode": "agent",
                    "stream": True,
                },
            ) as resp:
                assert resp.status_code == 200
                body = ""
                async for chunk in resp.aiter_text():
                    body += chunk

    events = _parse_sse_events(body)
    types = [e.get("type") for e in events]
    assert "error" in types, f"expected error event, got {types}"
    assert types[-1] == "done"
    # Error message mentions the orchestrator availability problem.
    err = next(e for e in events if e.get("type") == "error")
    assert "orchestrator" in (err.get("message") or "").lower()
