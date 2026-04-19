"""SSE contract tests for the five legacy streaming-sibling endpoints.

Each streaming endpoint under DEMO_MOCK must:
  * respond with Content-Type `text/event-stream`
  * emit at least one `event: token` frame (the dripped canned prose)
  * terminate with exactly one `event: done` carrying the endpoint's DTO
  * never emit an `event: error` under the mock path

The mock `token` drip comes from `drip_tokens(...)` in `mock_fixtures.py`.
All token deltas concatenated must equal the canned reasoning/prose field
for the matching `MOCK_*` fixture. The `done` payload must validate the
same response DTO the non-streaming sibling returns."""

from __future__ import annotations

import json
from typing import Any

import pytest
from httpx import ASGITransport, AsyncClient

from src.core.mock_fixtures import (
    MOCK_COMPLETIONS,
    MOCK_GENERATE_TESTS,
    MOCK_SUGGEST_REFACTOR,
)
from src.main import app


def _parse_sse(body: str) -> list[tuple[str, Any]]:
    """Split SSE body into [(event, decoded_json), ...]; see
    test_code_chat_orchestrator.py for the framing rationale."""
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


def _collect_token_deltas(frames: list[tuple[str, Any]]) -> str:
    """Reassemble the dripped prose from its `token` frames."""
    return "".join(
        payload["delta"]
        for name, payload in frames
        if name == "token" and isinstance(payload, dict) and "delta" in payload
    )


def _only_done(frames: list[tuple[str, Any]]) -> dict[str, Any]:
    """Assert exactly one `done` frame, last, and return its payload."""
    names = [n for n, _ in frames]
    assert names, "no SSE frames parsed"
    assert "error" not in names, f"unexpected error frame: {frames}"
    assert names[-1] == "done", f"last frame must be done, got {names[-1]!r}"
    assert names.count("done") == 1
    payload = frames[-1][1]
    assert isinstance(payload, dict)
    return payload


@pytest.fixture
async def client():
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as ac:
        async with app.router.lifespan_context(app):
            yield ac


@pytest.mark.asyncio
async def test_generate_tests_stream_emits_tokens_and_done(
    client: AsyncClient,
) -> None:
    resp = await client.post(
        "/api/v1/generate-tests/stream",
        json={
            "language": "python",
            "code": "def fizzbuzz(n): ...",
            "function_name": "fizzbuzz",
            "file_path": "demo/fizz.py",
        },
    )
    assert resp.status_code == 200, resp.text
    assert "text/event-stream" in resp.headers.get("content-type", "")

    frames = _parse_sse(resp.text)
    # Dripped prose reassembles to the canned reasoning verbatim.
    assert _collect_token_deltas(frames) == MOCK_GENERATE_TESTS["reasoning"]

    done = _only_done(frames)
    # Same DTO shape the non-streaming sibling returns.
    assert done["test_code"] == MOCK_GENERATE_TESTS["test_code"]
    assert done["test_file_name"] == MOCK_GENERATE_TESTS["test_file_name"]
    assert done["framework"] == "pytest"
    assert done["reasoning"] == MOCK_GENERATE_TESTS["reasoning"]


@pytest.mark.asyncio
async def test_suggest_refactor_stream_emits_tokens_and_done(
    client: AsyncClient,
) -> None:
    resp = await client.post(
        "/api/v1/suggest-refactor/stream",
        json={
            "language": "python",
            "code": (
                "def analyze(xs):\n"
                "    results = []\n"
                "    for i in range(len(xs)):\n"
                "        for j in range(len(xs)):\n"
                "            for k in range(len(xs)):\n"
                "                if xs[i] + xs[j] + xs[k] == 86400:\n"
                "                    results.append((xs[i], xs[j], xs[k]))\n"
                "    return results\n"
            ),
            "file_path": "src/analyze.py",
        },
    )
    assert resp.status_code == 200, resp.text
    frames = _parse_sse(resp.text)
    assert _collect_token_deltas(frames) == MOCK_SUGGEST_REFACTOR["reasoning"]

    done = _only_done(frames)
    assert done["refactored_code"] == MOCK_SUGGEST_REFACTOR["refactored_code"]
    assert done["diff_unified"] == MOCK_SUGGEST_REFACTOR["diff_unified"]
    assert isinstance(done["smells_detected"], list)
    assert len(done["smells_detected"]) >= 1


@pytest.mark.asyncio
async def test_socratic_question_stream_emits_tokens_and_done(
    client: AsyncClient,
) -> None:
    resp = await client.post(
        "/api/v1/socratic-question/stream",
        json={
            "recent_events": [],
            "current_code": "def rolling_sum(xs):\n    total = 0\n    ...",
            "stuck_reason": "undo_burst",
        },
    )
    assert resp.status_code == 200, resp.text
    frames = _parse_sse(resp.text)

    # Dripped prose must be the same string returned as `question` in the DTO.
    done = _only_done(frames)
    assert done["question"].strip()
    assert _collect_token_deltas(frames) == done["question"]
    assert isinstance(done["follow_ups"], list)
    assert len(done["follow_ups"]) >= 1
    assert 0.0 <= done["confidence"] <= 1.0


@pytest.mark.asyncio
async def test_complete_stream_emits_tokens_and_done(client: AsyncClient) -> None:
    resp = await client.post(
        "/api/v1/complete/stream",
        json={
            "language": "python",
            "prefix": "def add(a, b):\n    ",
            "suffix": "",
            "file_path": "demo/math.py",
        },
    )
    assert resp.status_code == 200, resp.text
    frames = _parse_sse(resp.text)

    # For "def add" prefix, the mock returns "    return a + b".
    expected = MOCK_COMPLETIONS["def add"]
    assert _collect_token_deltas(frames) == expected

    done = _only_done(frames)
    assert done["completion"] == expected
    assert done["stop_reason"] == "eol"
    assert done["model"] == "mock"


@pytest.mark.asyncio
async def test_explain_code_stream_emits_tokens_and_done(
    client: AsyncClient,
) -> None:
    resp = await client.post(
        "/api/v1/explain-code/stream",
        json={
            "code": "def fizzbuzz(n):\n    return 'FizzBuzz' if n % 15 == 0 else str(n)\n",
            "file_path": "demo/fizz.py",
            "language": "python",
            "context": "",
        },
    )
    assert resp.status_code == 200, resp.text
    frames = _parse_sse(resp.text)

    done = _only_done(frames)
    assert done["explanation"].strip()
    # Complexity is constrained to an enum-ish vocabulary.
    assert done["complexity"] in {"simple", "moderate", "complex", "unknown"}
    assert isinstance(done["suggestions"], list)

    # Dripped prose must reassemble to the explanation string.
    assert _collect_token_deltas(frames) == done["explanation"]


# ---- Non-streaming regression: reach each legacy endpoint once to ensure
# the streaming additions didn't silently break any of them. Asserts shape
# only; detailed contracts live in test_endpoints_mock.py.


@pytest.mark.asyncio
async def test_non_streaming_endpoints_still_respond(client: AsyncClient) -> None:
    # /health
    h = await client.get("/api/v1/health")
    assert h.status_code == 200
    assert h.json()["demo_mock"] is True

    # /generate-tests
    gt = await client.post(
        "/api/v1/generate-tests",
        json={"language": "python", "code": "def f(): ..."},
    )
    assert gt.status_code == 200
    assert "test_code" in gt.json()

    # /suggest-refactor
    sr = await client.post(
        "/api/v1/suggest-refactor",
        json={"language": "python", "code": "def f():\n    return 1\n"},
    )
    assert sr.status_code == 200
    assert "refactored_code" in sr.json()

    # /socratic-question
    sq = await client.post(
        "/api/v1/socratic-question",
        json={
            "recent_events": [],
            "current_code": "def f(): ...",
            "stuck_reason": "undo_burst",
        },
    )
    assert sq.status_code == 200
    assert sq.json()["question"].strip()

    # /complete
    cp = await client.post(
        "/api/v1/complete",
        json={"language": "python", "prefix": "def add(a, b):\n    ", "suffix": ""},
    )
    assert cp.status_code == 200
    assert "completion" in cp.json()

    # /explain-code
    ec = await client.post(
        "/api/v1/explain-code",
        json={"code": "print(1)", "language": "python"},
    )
    assert ec.status_code == 200
    assert ec.json()["explanation"].strip()

    # /code-chat (legacy path)
    cc = await client.post(
        "/api/v1/code-chat",
        json={
            "conversation": [{"role": "user", "text": "what?", "edits": []}],
            "current_code": "",
            "language": "python",
        },
    )
    assert cc.status_code == 200
    assert cc.json()["answer"].strip()
