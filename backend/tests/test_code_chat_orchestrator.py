"""Orchestrator-path tests for POST /code-chat and POST /code-chat/stream.

All four tests run under DEMO_MOCK=1 (set in conftest.py before src import).
The streaming-failure test monkeypatches DEMO_MOCK off for its duration so
the live-path branch is exercised against a fake orchestrator.

Scope of assertions:

  * event sequence + payload shape for /code-chat/stream (mock)
  * `edits` population for /code-chat with allow_edits=True (mock)
  * legacy path untouched when allow_edits=False
  * graceful `event: error` when the orchestrator blows up mid-stream
"""

from __future__ import annotations

import json
from typing import Any

import pytest
from httpx import ASGITransport, AsyncClient

from src.core.mock_fixtures import MOCK_ORCHESTRATOR_ANSWER
from src.main import app


def _parse_sse(body: str) -> list[tuple[str, Any]]:
    """Parse the full SSE body into [(event_name, decoded_data), ...].

    Per the framing contract in `src.core.streaming.sse_event`, each frame
    is `event: <name>\\ndata: <json-on-one-line>\\n\\n`. Splitting on the
    double-newline recovers one block per frame; trailing empty block is
    dropped."""
    frames: list[tuple[str, Any]] = []
    for block in body.split("\n\n"):
        if not block.strip():
            continue
        event: str | None = None
        data: str | None = None
        for line in block.split("\n"):
            if line.startswith("event: "):
                event = line[len("event: ") :].strip()
            elif line.startswith("data: "):
                data = line[len("data: ") :]
        if event is None or data is None:
            continue
        try:
            frames.append((event, json.loads(data)))
        except json.JSONDecodeError:
            frames.append((event, data))
    return frames


@pytest.fixture
async def client():
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as ac:
        async with app.router.lifespan_context(app):
            yield ac


@pytest.mark.asyncio
async def test_code_chat_stream_emits_expected_event_sequence(
    client: AsyncClient,
) -> None:
    """Under DEMO_MOCK, /code-chat/stream must emit the full multi-agent
    trace: prose tokens → plan → (code-writer, test-writer, reviewer)
    subagent triples with 2 edits wedged in → terminal `done` carrying a
    CodeChatResponse whose `edits` length is 2."""
    resp = await client.post(
        "/api/v1/code-chat/stream",
        json={
            "conversation": [
                {"role": "user", "text": "build me a fizzbuzz CLI with tests", "edits": []}
            ],
            "current_code": "",
            "language": "python",
            "allow_edits": True,
            "context_files": [],
        },
    )
    assert resp.status_code == 200, resp.text
    assert "text/event-stream" in resp.headers.get("content-type", "")

    frames = _parse_sse(resp.text)
    names = [name for name, _ in frames]
    assert names, f"no SSE frames parsed from body: {resp.text[:200]!r}"

    # Leading prose: at least one `token` frame, all tokens come BEFORE any
    # structured frame (plan / subagent_* / edit / done).
    assert names[0] == "token", f"first frame must be token, got {names[0]!r}"
    first_structured = next(i for i, n in enumerate(names) if n != "token")
    assert first_structured >= 1
    assert all(n == "token" for n in names[:first_structured])

    # Exactly one `plan` frame with a 3-entry todo list.
    assert names.count("plan") == 1
    plan_idx = names.index("plan")
    plan_payload = frames[plan_idx][1]
    assert isinstance(plan_payload, dict)
    assert len(plan_payload.get("todos", [])) == 3

    # Three subagent_start frames in order: code-writer → test-writer → reviewer.
    starts = [payload for name, payload in frames if name == "subagent_start"]
    ends = [payload for name, payload in frames if name == "subagent_end"]
    assert len(starts) == 3, f"expected 3 subagent_start, got {len(starts)}"
    assert len(ends) == 3, f"expected 3 subagent_end, got {len(ends)}"
    assert [s["name"] for s in starts] == ["code-writer", "test-writer", "reviewer"]
    assert [e["name"] for e in ends] == ["code-writer", "test-writer", "reviewer"]

    # Exactly two `edit` frames — reviewer LGTMs without proposing a third.
    edits = [payload for name, payload in frames if name == "edit"]
    assert len(edits) == 2
    assert [e["file_path"] for e in edits] == ["fizzbuzz.py", "tests/test_fizzbuzz.py"]
    # Each edit carries a non-empty unified diff so the plugin can preview it.
    assert all(e.get("diff_unified", "") for e in edits)

    # Terminal: exactly one `done` frame, and it's last.
    assert names[-1] == "done", f"last frame must be done, got {names[-1]!r}"
    assert names.count("done") == 1
    done_payload = frames[-1][1]
    assert isinstance(done_payload, dict)
    assert done_payload["model"] == "mock-orchestrator"
    assert done_payload["answer"] == MOCK_ORCHESTRATOR_ANSWER
    assert len(done_payload["edits"]) == 2


@pytest.mark.asyncio
async def test_code_chat_allow_edits_populates_edits_field(
    client: AsyncClient,
) -> None:
    """Non-streaming /code-chat with allow_edits=True under DEMO_MOCK
    returns the canned orchestrator fixture: 2 edits, the orchestrator
    answer, `mock-orchestrator` model."""
    resp = await client.post(
        "/api/v1/code-chat",
        json={
            "conversation": [
                {"role": "user", "text": "build me a fizzbuzz CLI with tests", "edits": []}
            ],
            "current_code": "",
            "language": "python",
            "allow_edits": True,
            "context_files": [],
        },
    )
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert body["model"] == "mock-orchestrator"
    assert body["answer"] == MOCK_ORCHESTRATOR_ANSWER
    assert len(body["edits"]) == 2

    paths = {e["file_path"] for e in body["edits"]}
    assert paths == {"fizzbuzz.py", "tests/test_fizzbuzz.py"}
    for edit in body["edits"]:
        assert edit["diff_unified"].strip(), f"empty diff on {edit['file_path']}"
        assert edit["proposed_code"].strip()


@pytest.mark.asyncio
async def test_code_chat_without_allow_edits_does_not_use_orchestrator(
    client: AsyncClient,
) -> None:
    """With allow_edits defaulting to False, the legacy single-shot path
    is taken under DEMO_MOCK: the response carries the generic chat mock
    (model='mock') with no populated edits. Perf guard — we don't want a
    plain Q&A request spinning up the multi-agent machinery."""
    resp = await client.post(
        "/api/v1/code-chat",
        json={
            "conversation": [
                {"role": "user", "text": "What does this function do?", "edits": []}
            ],
            "current_code": (
                "def fizzbuzz(n):\n"
                "    return 'FizzBuzz' if n % 15 == 0 else str(n)\n"
            ),
            "language": "python",
            "file_path": "demo/fizz.py",
        },
    )
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert body["model"] == "mock"
    assert body["answer"].strip()
    assert body.get("edits", []) == []


@pytest.mark.asyncio
async def test_code_chat_stream_emits_error_on_subagent_failure(
    client: AsyncClient, monkeypatch: pytest.MonkeyPatch
) -> None:
    """Live-path failure mode: when the orchestrator's `astream` raises
    mid-iteration, the SSE stream must terminate with an `event: error`
    frame rather than crash the HTTP response.

    DEMO_MOCK short-circuits to the canned trace, so we flip it off on
    the `app.state.settings` singleton for this test and slot an
    exploding fake into `app.state.orchestrator`. Monkeypatch reverts
    both at teardown so this test doesn't bleed into others."""
    monkeypatch.setattr(app.state.settings, "DEMO_MOCK", False)

    class _ExplodingOrch:
        """Fake CompiledStateGraph whose `astream` raises on first iter."""

        def astream(self, *_args: Any, **_kwargs: Any):
            async def _gen():
                raise RuntimeError("sub-agent exploded mid-stream")
                yield  # pragma: no cover — forces this to be an async generator

            return _gen()

    monkeypatch.setattr(app.state, "orchestrator", _ExplodingOrch())

    resp = await client.post(
        "/api/v1/code-chat/stream",
        json={
            "conversation": [
                {"role": "user", "text": "do the thing", "edits": []}
            ],
            "current_code": "",
            "language": "python",
            "allow_edits": True,
            "context_files": [],
        },
    )
    assert resp.status_code == 200, resp.text
    frames = _parse_sse(resp.text)
    names = [name for name, _ in frames]
    assert "error" in names, f"no error frame emitted; saw {names!r}"
    # No `done` frame after an error — the stream terminates on the error.
    assert "done" not in names
    error_payload = next(payload for name, payload in frames if name == "error")
    assert isinstance(error_payload, dict)
    assert "exploded" in error_payload.get("error", "").lower()
    assert error_payload.get("step") in ("stream", "driver")
