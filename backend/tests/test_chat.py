"""Tests for the new streaming /api/v1/chat endpoint.

Under `DEMO_MOCK=true` (set in conftest.py), `app.state.llm_client` is a
`MockLLMClient` whose `stream_complete` slices `canned_response` into small
chunks. We poke `canned_response` / `raise_mid_stream` on that instance to
drive each scenario.
"""

from __future__ import annotations

import json
import re

import pytest
from httpx import ASGITransport, AsyncClient

from src.core.llm_client import MockLLMClient
from src.main import app


@pytest.fixture
async def client_and_mock():
    """Yield an AsyncClient plus the live MockLLMClient installed on the app."""
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as ac:
        async with app.router.lifespan_context(app):
            mock = app.state.llm_client
            assert isinstance(mock, MockLLMClient), (
                "DEMO_MOCK=true should install a MockLLMClient; got "
                f"{type(mock).__name__}"
            )
            # Reset between tests to avoid bleed-through.
            mock.canned_response = (
                "Hello from the mock duck. Streaming is working."
            )
            mock.raise_mid_stream = None
            mock.last_messages = None
            yield ac, mock


def _parse_sse_events(body: str) -> list[dict]:
    """Extract JSON payloads from SSE `data:` frames, ignoring `[DONE]`."""
    events: list[dict] = []
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


@pytest.mark.asyncio
async def test_chat_streaming_basic(client_and_mock) -> None:
    client, mock = client_and_mock
    mock.canned_response = "Hi there, developer!"
    async with client.stream(
        "POST",
        "/api/v1/chat",
        json={
            "messages": [{"role": "user", "content": "Say hi"}],
            "stream": True,
        },
    ) as resp:
        assert resp.status_code == 200
        assert resp.headers["content-type"].startswith("text/event-stream")
        body = ""
        async for chunk in resp.aiter_text():
            body += chunk

    # Must end with the [DONE] sentinel.
    assert "data: [DONE]" in body, body

    events = _parse_sse_events(body)
    types = [e.get("type") for e in events]
    assert "token" in types, f"no token events in {types}"
    assert "done" in types, f"no done event in {types}"

    # Token deltas should reconstruct the canned response.
    reassembled = "".join(e["delta"] for e in events if e.get("type") == "token")
    assert reassembled == mock.canned_response


@pytest.mark.asyncio
async def test_chat_non_streaming(client_and_mock) -> None:
    client, mock = client_and_mock
    mock.canned_response = "Non-streaming answer."
    resp = await client.post(
        "/api/v1/chat",
        json={
            "messages": [{"role": "user", "content": "Anything"}],
            "stream": False,
        },
    )
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert body["content"] == "Non-streaming answer."
    # Usage block should be populated by the mock's synthetic counter.
    assert body["usage"] is not None
    assert body["usage"]["completion_tokens"] is not None
    assert body["finish_reason"] == "stop"


@pytest.mark.asyncio
async def test_chat_slash_command_injection(client_and_mock) -> None:
    client, mock = client_and_mock
    resp = await client.post(
        "/api/v1/chat",
        json={
            "messages": [{"role": "user", "content": "please"}],
            "slash_command": "explain",
            "context": {
                "selection": {
                    "file_path": "demo/foo.py",
                    "language": "python",
                    "code": "def foo():\n    return 1\n",
                }
            },
            "stream": False,
        },
    )
    assert resp.status_code == 200, resp.text
    assert mock.last_messages is not None
    full_prompt = "\n".join(m["content"] for m in mock.last_messages)
    # Slash-command template body should have been injected.
    assert "EXPLAIN" in full_prompt
    assert "improvement suggestions" in full_prompt
    # Selection should render with its file path.
    assert "demo/foo.py" in full_prompt
    assert "def foo()" in full_prompt


@pytest.mark.asyncio
async def test_chat_context_truncation(client_and_mock) -> None:
    client, mock = client_and_mock
    # ~40 KB of attached file content — well above the 16k-token budget
    # (≈64 KB raw) once the persona + context framing is added.
    big_blob = "x = 1\n" * 10000  # ~60 KB
    resp = await client.post(
        "/api/v1/chat",
        json={
            "messages": [
                {"role": "user", "content": "first question"},
                {"role": "assistant", "content": "assistant reply " * 500},
                {"role": "user", "content": "second question"},
                {"role": "assistant", "content": "another reply " * 500},
                {"role": "user", "content": "final question"},
            ],
            "context": {
                "attached_files": [
                    {
                        "file_path": "a.py",
                        "content": big_blob,
                        "language": "python",
                    }
                ]
            },
            "stream": False,
        },
    )
    assert resp.status_code == 200, resp.text
    assert mock.last_messages is not None
    # The system + last user turn must survive. Older assistant turns must
    # be dropped first when the budget is exceeded.
    roles = [m["role"] for m in mock.last_messages]
    contents = [m["content"] for m in mock.last_messages]
    assert roles[0] == "system"
    assert contents[-1].endswith("final question") or "final question" in contents[-1]
    # We don't enforce an absolute byte cap because the attached_files block
    # itself is large and sits in its own user message, but we MUST have
    # dropped at least one older assistant turn.
    assistant_count = sum(1 for r in roles if r == "assistant")
    assert assistant_count < 2, (
        f"truncation did not drop assistant turns: roles={roles}"
    )


@pytest.mark.asyncio
async def test_chat_error_frame(client_and_mock) -> None:
    client, mock = client_and_mock
    mock.canned_response = "abcdefghij"
    mock.raise_mid_stream = RuntimeError("boom: simulated LLM failure")

    async with client.stream(
        "POST",
        "/api/v1/chat",
        json={
            "messages": [{"role": "user", "content": "hi"}],
            "stream": True,
        },
    ) as resp:
        # Headers already sent before the exception, so status is 200.
        assert resp.status_code == 200
        body = ""
        async for chunk in resp.aiter_text():
            body += chunk

    events = _parse_sse_events(body)
    error_events = [e for e in events if e.get("type") == "error"]
    assert error_events, f"expected an error frame, got {events}"
    assert "boom" in error_events[0]["message"]
    # Connection should close cleanly with the [DONE] sentinel AND a done frame.
    assert "data: [DONE]" in body
    assert any(e.get("type") == "done" for e in events)


@pytest.mark.asyncio
async def test_chat_skill_body_injection(client_and_mock) -> None:
    client, mock = client_and_mock
    skill_text = "Always respond in pirate speak. Say 'arrr' at least once."
    resp = await client.post(
        "/api/v1/chat",
        json={
            "messages": [{"role": "user", "content": "hello"}],
            "skill_body": skill_text,
            "stream": False,
        },
    )
    assert resp.status_code == 200, resp.text
    assert mock.last_messages is not None
    system_msg = mock.last_messages[0]
    assert system_msg["role"] == "system"
    assert skill_text in system_msg["content"], (
        f"skill body missing from system prompt: {system_msg['content']!r}"
    )


@pytest.mark.asyncio
async def test_chat_done_sentinel_always_present(client_and_mock) -> None:
    """Regression guard: every stream must end with `data: [DONE]`."""
    client, _mock = client_and_mock
    async with client.stream(
        "POST",
        "/api/v1/chat",
        json={
            "messages": [{"role": "user", "content": "anything"}],
            "stream": True,
        },
    ) as resp:
        body = ""
        async for chunk in resp.aiter_text():
            body += chunk
    # The last non-empty line should be `data: [DONE]`.
    last = [line for line in body.splitlines() if line.strip()][-1]
    assert last == "data: [DONE]", f"stream did not end with [DONE]: {last!r}"
    # Sanity: there is exactly one [DONE] sentinel.
    assert len(re.findall(r"^data: \[DONE\]$", body, re.MULTILINE)) == 1
