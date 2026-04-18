"""Smoke tests for the four HTTP endpoints under DEMO_MOCK=1.

These assert the **JSON contract** that the plugin and dashboard rely on:
the response shapes and required fields. They do not exercise the LLM path —
that's the point, since `DEMO_MOCK=1` returns canned fixtures.

The test sets `DEMO_MOCK=true` via `conftest.py` before any src-level import.
"""

from __future__ import annotations

import pytest
from httpx import ASGITransport, AsyncClient

from src.core.mock_fixtures import MOCK_GENERATE_TESTS, MOCK_SUGGEST_REFACTOR
from src.main import app


@pytest.fixture
async def client():
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as ac:
        # The lifespan handler wires `app.state.llm_client`; poke it once.
        async with app.router.lifespan_context(app):
            yield ac


@pytest.mark.asyncio
async def test_health_returns_canonical_shape(client: AsyncClient) -> None:
    resp = await client.get("/api/v1/health")
    assert resp.status_code == 200, resp.text
    body = resp.json()
    # Schema required by the plugin's `/health` poller.
    for required in ("status", "llm_provider", "llm_model", "llm_available", "demo_mock"):
        assert required in body, f"/health missing field {required!r}: {body}"
    assert body["demo_mock"] is True


@pytest.mark.asyncio
async def test_generate_tests_returns_mock_fixture(client: AsyncClient) -> None:
    req = {
        "language": "python",
        "code": "def fizzbuzz(n): ...",
        "function_name": "fizzbuzz",
        "file_path": "demo/fizz.py",
    }
    resp = await client.post("/api/v1/generate-tests", json=req)
    assert resp.status_code == 200, resp.text
    body = resp.json()
    # Shape required by GenerateTestsResponse — plugin writes `test_code` to disk
    # and displays `reasoning` in the tool window.
    for required in ("test_code", "test_file_name", "framework", "reasoning"):
        assert required in body, f"missing {required!r}: {body}"
    # DEMO_MOCK path returns the canned fixture verbatim.
    assert body["test_code"] == MOCK_GENERATE_TESTS["test_code"]
    assert body["framework"] == "pytest"


@pytest.mark.asyncio
async def test_suggest_refactor_returns_mock_fixture(client: AsyncClient) -> None:
    req = {
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
    }
    resp = await client.post("/api/v1/suggest-refactor", json=req)
    assert resp.status_code == 200, resp.text
    body = resp.json()
    for required in ("refactored_code", "diff_unified", "smells_detected", "reasoning"):
        assert required in body, f"missing {required!r}: {body}"
    assert body["refactored_code"] == MOCK_SUGGEST_REFACTOR["refactored_code"]
    assert isinstance(body["smells_detected"], list)
    assert len(body["smells_detected"]) >= 1


@pytest.mark.asyncio
async def test_complete_returns_mock_completion(client: AsyncClient) -> None:
    req = {
        "language": "python",
        "prefix": "def add(a, b):\n    ",
        "suffix": "",
        "file_path": "demo/math.py",
    }
    resp = await client.post("/api/v1/complete", json=req)
    assert resp.status_code == 200, resp.text
    body = resp.json()
    for required in ("completion", "stop_reason", "model"):
        assert required in body, f"missing {required!r}: {body}"
    # The canned mock for a "def add" prefix is "    return a + b".
    assert "a + b" in body["completion"], body["completion"]
    assert body["stop_reason"] == "eol"


@pytest.mark.asyncio
async def test_complete_falls_back_when_prefix_unmatched(client: AsyncClient) -> None:
    req = {
        "language": "python",
        "prefix": "# some unrecognised prefix\n",
        "suffix": "",
    }
    resp = await client.post("/api/v1/complete", json=req)
    assert resp.status_code == 200, resp.text
    body = resp.json()
    # Default canned completion is non-empty.
    assert body["completion"].strip()


@pytest.mark.asyncio
async def test_socratic_question_returns_undo_burst_fixture(client: AsyncClient) -> None:
    req = {
        "recent_events": [],
        "current_code": "def rolling_sum(xs):\n    total = 0\n    ...",
        "stuck_reason": "undo_burst",
    }
    resp = await client.post("/api/v1/socratic-question", json=req)
    assert resp.status_code == 200, resp.text
    body = resp.json()
    # Shape required by SocraticResponse — plugin shows `question`, follow_ups
    # appear in the tool window.
    for required in ("question", "follow_ups", "confidence"):
        assert required in body, f"missing {required!r}: {body}"
    # The undo_burst branch returns a non-empty question + at least one follow-up.
    assert body["question"].strip()
    assert len(body["follow_ups"]) >= 1
    assert 0.0 <= body["confidence"] <= 1.0


@pytest.mark.asyncio
async def test_socratic_question_routes_per_rule(client: AsyncClient) -> None:
    """Each StuckDetector rule must return a DISTINCT opening question — not
    the generic fallback. Regression test for the 'same question every time'
    bug where 4 of 5 rules fell through to MOCK_SOCRATIC_GENERIC."""
    rules = [
        "undo_burst",
        "repeated_failing_test",
        "long_idle_on_error",
        "rapid_file_switching",
        "edit_thrash",
    ]
    questions: set[str] = set()
    for rule in rules:
        resp = await client.post(
            "/api/v1/socratic-question",
            json={
                "recent_events": [],
                "current_code": "def demo():\n    return 0\n",
                "stuck_reason": rule,
            },
        )
        assert resp.status_code == 200, resp.text
        questions.add(resp.json()["question"].strip())
    # All five rules must produce different opening questions.
    assert len(questions) == len(rules), (
        f"expected {len(rules)} unique opening questions, got {len(questions)}: {questions}"
    )


@pytest.mark.asyncio
async def test_code_chat_returns_non_empty_answer(client: AsyncClient) -> None:
    """The new /code-chat endpoint should return a direct answer (not another
    question) with the same response shape every client expects."""
    resp = await client.post(
        "/api/v1/code-chat",
        json={
            "conversation": [
                {"role": "user", "text": "What does this function do?"},
            ],
            "current_code": "def fizzbuzz(n):\n    return 'FizzBuzz' if n % 15 == 0 else str(n)\n",
            "file_path": "demo/fizz.py",
            "language": "python",
        },
    )
    assert resp.status_code == 200, resp.text
    body = resp.json()
    for required in ("answer", "model"):
        assert required in body, f"missing {required!r}: {body}"
    assert body["answer"].strip(), "empty answer"


@pytest.mark.asyncio
async def test_socratic_question_returns_followup_after_reply(
    client: AsyncClient,
) -> None:
    """When the request carries a non-empty `conversation`, the backend must
    return a FOLLOW-UP coach turn, not the opening question."""
    opening = await client.post(
        "/api/v1/socratic-question",
        json={
            "recent_events": [],
            "current_code": "def demo():\n    return 0\n",
            "stuck_reason": "undo_burst",
        },
    )
    assert opening.status_code == 200
    opening_question = opening.json()["question"]

    followup = await client.post(
        "/api/v1/socratic-question",
        json={
            "recent_events": [],
            "current_code": "def demo():\n    return 0\n",
            "stuck_reason": "undo_burst",
            "conversation": [
                {"role": "coach", "text": opening_question},
                {"role": "developer", "text": "I think the invariant is that i is always in range."},
            ],
        },
    )
    assert followup.status_code == 200, followup.text
    followup_body = followup.json()
    assert followup_body["question"].strip()
    # The follow-up must NOT echo the opening turn.
    assert followup_body["question"] != opening_question
