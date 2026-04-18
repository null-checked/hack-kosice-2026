"""Unified async LLM client (Ollama / OpenAI-compatible / OpenAI) via httpx.

No langchain, no openai SDK — just HTTP. Provides:
  - `BaseLLMClient` ABC with `complete()` and `available()`.
  - Three implementations.
  - `create_llm_client(settings)` factory with graceful fallback chain.
"""

from __future__ import annotations

import asyncio
import json
import logging
from abc import ABC, abstractmethod
from collections.abc import AsyncIterator
from typing import Any

import httpx

from src.settings import Settings

logger = logging.getLogger(__name__)


class LLMUnavailableError(RuntimeError):
    """Raised when no configured LLM provider can serve a request."""


class BaseLLMClient(ABC):
    """Abstract async LLM client."""

    name: str = "base"
    model: str = ""

    @abstractmethod
    async def complete(
        self,
        prompt: str,
        system: str | None = None,
        temperature: float = 0.2,
        max_tokens: int = 2048,
    ) -> str:
        """Return the raw text completion."""

    @abstractmethod
    async def available(self) -> bool:
        """Cheap reachability check (≤ 2 s)."""

    async def stream_complete(
        self,
        messages: list[dict[str, str]],
        *,
        model: str | None = None,
        temperature: float | None = None,
        max_tokens: int | None = None,
    ) -> AsyncIterator[dict[str, Any]]:
        """Yield typed event dicts for a streaming chat completion.

        Each yielded dict has a `type` key — one of:
            {"type": "token",     "delta": "..."}
            {"type": "tool_call", "name": "...", "arguments": {...}}
            {"type": "usage",     "prompt_tokens": int|None, ...}
            {"type": "done",      "finish_reason": "stop"|...}

        The default implementation falls back to the non-streaming `complete()`
        and emits a single `token` event followed by `done`. Concrete clients
        override this to stream real token-by-token output.
        """
        # Build a single prompt+system string from the message list so we can
        # fall back onto the non-streaming API without losing turn info.
        system_parts: list[str] = []
        prompt_parts: list[str] = []
        for m in messages:
            role = m.get("role")
            content = m.get("content", "")
            if role == "system":
                system_parts.append(content)
            elif role == "user":
                prompt_parts.append(f"User: {content}")
            elif role == "assistant":
                prompt_parts.append(f"Assistant: {content}")
        system = "\n\n".join(system_parts) if system_parts else None
        prompt = "\n\n".join(prompt_parts) or "(no user turn)"
        text = await self.complete(
            prompt,
            system=system,
            temperature=0.2 if temperature is None else temperature,
            max_tokens=2048 if max_tokens is None else max_tokens,
        )
        if text:
            yield {"type": "token", "delta": text}
        yield {"type": "done", "finish_reason": "stop"}


class OllamaClient(BaseLLMClient):
    """Talks to local Ollama via `/api/generate` (streaming disabled)."""

    name = "ollama"

    def __init__(self, base_url: str, model: str, timeout: float = 60.0) -> None:
        self.base_url = base_url.rstrip("/")
        self.model = model
        self.timeout = timeout

    async def available(self) -> bool:
        try:
            async with httpx.AsyncClient(timeout=2.0) as client:
                r = await client.get(f"{self.base_url}/api/tags")
                return r.status_code == 200
        except Exception as exc:  # noqa: BLE001
            logger.debug("Ollama unreachable: %s", exc)
            return False

    async def complete(
        self,
        prompt: str,
        system: str | None = None,
        temperature: float = 0.2,
        max_tokens: int = 2048,
    ) -> str:
        body: dict[str, Any] = {
            "model": self.model,
            "prompt": prompt,
            "stream": False,
            "options": {"temperature": temperature, "num_predict": max_tokens},
        }
        if system:
            body["system"] = system
        async with httpx.AsyncClient(timeout=self.timeout) as client:
            r = await client.post(f"{self.base_url}/api/generate", json=body)
            r.raise_for_status()
            data = r.json()
            return str(data.get("response", ""))

    async def stream_complete(
        self,
        messages: list[dict[str, str]],
        *,
        model: str | None = None,
        temperature: float | None = None,
        max_tokens: int | None = None,
    ) -> AsyncIterator[dict[str, Any]]:
        """Stream tokens via Ollama's `/api/chat` NDJSON streaming endpoint."""
        body: dict[str, Any] = {
            "model": model or self.model,
            "messages": messages,
            "stream": True,
            "options": {
                "temperature": 0.2 if temperature is None else temperature,
                "num_predict": 2048 if max_tokens is None else max_tokens,
            },
        }
        finish_reason = "stop"
        prompt_tokens: int | None = None
        completion_tokens: int | None = None
        async with httpx.AsyncClient(timeout=self.timeout) as client:
            async with client.stream(
                "POST", f"{self.base_url}/api/chat", json=body
            ) as r:
                r.raise_for_status()
                async for line in r.aiter_lines():
                    if not line:
                        continue
                    try:
                        chunk = json.loads(line)
                    except json.JSONDecodeError:
                        logger.debug("Ollama: non-JSON line skipped: %r", line[:80])
                        continue
                    # Ollama chat chunks: {"message":{"role":"assistant","content":"..."},"done":bool, ...}
                    msg = chunk.get("message") or {}
                    delta = msg.get("content", "")
                    if delta:
                        yield {"type": "token", "delta": delta}
                    if chunk.get("done"):
                        finish_reason = chunk.get("done_reason") or "stop"
                        prompt_tokens = chunk.get("prompt_eval_count")
                        completion_tokens = chunk.get("eval_count")
                        break
        if prompt_tokens is not None or completion_tokens is not None:
            total = (prompt_tokens or 0) + (completion_tokens or 0) or None
            yield {
                "type": "usage",
                "prompt_tokens": prompt_tokens,
                "completion_tokens": completion_tokens,
                "total_tokens": total,
            }
        yield {"type": "done", "finish_reason": finish_reason}


class OpenAICompatibleClient(BaseLLMClient):
    """Any OpenAI-compatible chat completions endpoint (LM Studio, vLLM, ...)."""

    name = "openai_compatible"

    def __init__(
        self,
        base_url: str,
        api_key: str | None,
        model: str,
        timeout: float = 60.0,
    ) -> None:
        self.base_url = base_url.rstrip("/")
        self.api_key = api_key or "not-needed"
        self.model = model
        self.timeout = timeout

    async def available(self) -> bool:
        try:
            async with httpx.AsyncClient(timeout=2.0) as client:
                headers = {"Authorization": f"Bearer {self.api_key}"}
                r = await client.get(f"{self.base_url}/models", headers=headers)
                return r.status_code in (200, 401)  # 401 still means endpoint is up
        except Exception as exc:  # noqa: BLE001
            logger.debug("OpenAI-compatible unreachable: %s", exc)
            return False

    async def complete(
        self,
        prompt: str,
        system: str | None = None,
        temperature: float = 0.2,
        max_tokens: int = 2048,
    ) -> str:
        messages: list[dict[str, str]] = []
        if system:
            messages.append({"role": "system", "content": system})
        messages.append({"role": "user", "content": prompt})
        body = {
            "model": self.model,
            "messages": messages,
            "temperature": temperature,
            "max_tokens": max_tokens,
        }
        headers = {"Authorization": f"Bearer {self.api_key}"}
        async with httpx.AsyncClient(timeout=self.timeout) as client:
            r = await client.post(
                f"{self.base_url}/chat/completions",
                json=body,
                headers=headers,
            )
            r.raise_for_status()
            data = r.json()
            return str(data["choices"][0]["message"]["content"])

    async def stream_complete(
        self,
        messages: list[dict[str, str]],
        *,
        model: str | None = None,
        temperature: float | None = None,
        max_tokens: int | None = None,
    ) -> AsyncIterator[dict[str, Any]]:
        """Stream tokens via the OpenAI-compatible SSE chat-completions API."""
        body: dict[str, Any] = {
            "model": model or self.model,
            "messages": messages,
            "stream": True,
        }
        if temperature is not None:
            body["temperature"] = temperature
        if max_tokens is not None:
            body["max_tokens"] = max_tokens
        # Ask for usage on the final chunk (OpenAI-specific; other servers ignore).
        body["stream_options"] = {"include_usage": True}
        headers = {
            "Authorization": f"Bearer {self.api_key}",
            "Accept": "text/event-stream",
        }
        finish_reason: str | None = None
        usage: dict[str, Any] | None = None
        async with httpx.AsyncClient(timeout=self.timeout) as client:
            async with client.stream(
                "POST",
                f"{self.base_url}/chat/completions",
                json=body,
                headers=headers,
            ) as r:
                r.raise_for_status()
                async for line in r.aiter_lines():
                    if not line or not line.startswith("data:"):
                        continue
                    payload = line[5:].strip()
                    if payload == "[DONE]":
                        break
                    try:
                        chunk = json.loads(payload)
                    except json.JSONDecodeError:
                        logger.debug("OpenAI SSE: non-JSON data skipped: %r", payload[:80])
                        continue
                    # Usage-only final chunk has empty `choices` on OpenAI.
                    if chunk.get("usage"):
                        usage = chunk["usage"]
                    choices = chunk.get("choices") or []
                    if not choices:
                        continue
                    choice = choices[0]
                    delta = (choice.get("delta") or {}).get("content") or ""
                    if delta:
                        yield {"type": "token", "delta": delta}
                    # Tool-calls passthrough (best-effort; most hackathon paths won't use this).
                    tool_calls = (choice.get("delta") or {}).get("tool_calls") or []
                    for tc in tool_calls:
                        fn = tc.get("function") or {}
                        name = fn.get("name") or tc.get("name") or ""
                        raw_args = fn.get("arguments")
                        args: dict[str, Any] = {}
                        if isinstance(raw_args, str):
                            try:
                                args = json.loads(raw_args) if raw_args.strip() else {}
                            except json.JSONDecodeError:
                                args = {"_raw": raw_args}
                        elif isinstance(raw_args, dict):
                            args = raw_args
                        if name:
                            yield {"type": "tool_call", "name": name, "arguments": args}
                    if choice.get("finish_reason"):
                        finish_reason = choice["finish_reason"]
        if usage:
            yield {
                "type": "usage",
                "prompt_tokens": usage.get("prompt_tokens"),
                "completion_tokens": usage.get("completion_tokens"),
                "total_tokens": usage.get("total_tokens"),
            }
        yield {"type": "done", "finish_reason": finish_reason or "stop"}


class OpenAIClient(OpenAICompatibleClient):
    """Official OpenAI endpoint — thin subclass with the canonical base URL."""

    name = "openai"

    def __init__(self, api_key: str, model: str, timeout: float = 60.0) -> None:
        super().__init__(
            base_url="https://api.openai.com/v1",
            api_key=api_key,
            model=model,
            timeout=timeout,
        )


class UnavailableLLMClient(BaseLLMClient):
    """Placeholder client used when no provider can be configured at startup.

    Reports `available() -> False` and raises `LLMUnavailableError` if someone
    actually tries to `complete()`. The backend still boots cleanly (so that
    `DEMO_MOCK=1` and the `/health` endpoint work), but live LLM calls fail
    fast with a readable error instead of crashing the process.
    """

    name = "unavailable"

    def __init__(self, reason: str, model: str = "(none)") -> None:
        self.reason = reason
        self.model = model

    async def available(self) -> bool:
        return False

    async def complete(
        self,
        prompt: str,
        system: str | None = None,
        temperature: float = 0.2,
        max_tokens: int = 2048,
    ) -> str:
        raise LLMUnavailableError(
            f"No LLM provider configured. Reason: {self.reason}"
        )

    async def stream_complete(
        self,
        messages: list[dict[str, str]],
        *,
        model: str | None = None,
        temperature: float | None = None,
        max_tokens: int | None = None,
    ) -> AsyncIterator[dict[str, Any]]:
        raise LLMUnavailableError(
            f"No LLM provider configured. Reason: {self.reason}"
        )
        # Make this an async generator for typing consistency.
        yield {"type": "done"}  # pragma: no cover


class FallbackLLMClient(BaseLLMClient):
    """Tries `primary` first; on failure, walks through `fallbacks` in order."""

    name = "fallback"

    def __init__(self, primary: BaseLLMClient, fallbacks: list[BaseLLMClient]) -> None:
        self.primary = primary
        self.fallbacks = fallbacks
        self.model = primary.model

    @property
    def chain(self) -> list[BaseLLMClient]:
        return [self.primary, *self.fallbacks]

    async def available(self) -> bool:
        for client in self.chain:
            if await client.available():
                return True
        return False

    async def complete(
        self,
        prompt: str,
        system: str | None = None,
        temperature: float = 0.2,
        max_tokens: int = 2048,
    ) -> str:
        last_err: Exception | None = None
        for client in self.chain:
            try:
                logger.debug("Trying LLM provider %s (%s)", client.name, client.model)
                return await client.complete(prompt, system, temperature, max_tokens)
            except Exception as exc:  # noqa: BLE001
                logger.warning("LLM provider %s failed: %s", client.name, exc)
                last_err = exc
                continue
        raise LLMUnavailableError(
            f"All LLM providers failed. Last error: {last_err}"
        )

    async def stream_complete(
        self,
        messages: list[dict[str, str]],
        *,
        model: str | None = None,
        temperature: float | None = None,
        max_tokens: int | None = None,
    ) -> AsyncIterator[dict[str, Any]]:
        """Try providers in order; the FIRST one to yield a token wins.

        If a provider raises before emitting anything, walk to the next. Once
        tokens have started flowing, errors are re-raised (we can't
        transparently resume mid-stream on a different provider).
        """
        last_err: Exception | None = None
        for client in self.chain:
            try:
                logger.debug(
                    "Streaming via LLM provider %s (%s)", client.name, client.model
                )
                started = False
                async for event in client.stream_complete(
                    messages,
                    model=model,
                    temperature=temperature,
                    max_tokens=max_tokens,
                ):
                    started = True
                    yield event
                if started:
                    return
            except Exception as exc:  # noqa: BLE001
                if "started" in locals() and started:  # type: ignore[has-type]
                    raise
                logger.warning(
                    "LLM provider %s stream failed before first token: %s",
                    client.name,
                    exc,
                )
                last_err = exc
                continue
        raise LLMUnavailableError(
            f"All LLM providers failed to stream. Last error: {last_err}"
        )


class MockLLMClient(BaseLLMClient):
    """Deterministic in-memory client used under `DEMO_MOCK=1` and in tests.

    `complete()` returns `canned_response` verbatim. `stream_complete()` slices
    the response into small chunks with tiny async sleeps so the SSE pipeline
    behaves realistically without a real LLM. The last prompt passed to
    `stream_complete` is captured on `self.last_messages` for test assertions.
    """

    name = "mock"

    def __init__(
        self,
        canned_response: str = (
            "Hi! I'm RubberDuck (mock mode). I'm not calling a real LLM right "
            "now, but the streaming pipeline is wired up correctly."
        ),
        *,
        model: str = "mock-duck-1",
        chunk_size: int = 4,
        delay_seconds: float = 0.0,
    ) -> None:
        self.canned_response = canned_response
        self.model = model
        self.chunk_size = max(1, chunk_size)
        self.delay_seconds = delay_seconds
        self.last_messages: list[dict[str, str]] | None = None
        # Optional override: set to an Exception instance to force
        # `stream_complete` to raise AFTER yielding a first token. Used by
        # tests to exercise the mid-stream error frame path.
        self.raise_mid_stream: Exception | None = None

    async def available(self) -> bool:
        return True

    async def complete(
        self,
        prompt: str,
        system: str | None = None,
        temperature: float = 0.2,
        max_tokens: int = 2048,
    ) -> str:
        return self.canned_response

    async def stream_complete(
        self,
        messages: list[dict[str, str]],
        *,
        model: str | None = None,
        temperature: float | None = None,
        max_tokens: int | None = None,
    ) -> AsyncIterator[dict[str, Any]]:
        self.last_messages = list(messages)
        text = self.canned_response
        step = self.chunk_size
        first = True
        for i in range(0, len(text), step):
            chunk = text[i : i + step]
            yield {"type": "token", "delta": chunk}
            if not first and self.raise_mid_stream is not None:
                raise self.raise_mid_stream
            first = False
            if self.delay_seconds:
                await asyncio.sleep(self.delay_seconds)
        yield {
            "type": "usage",
            "prompt_tokens": sum(len(m.get("content", "")) for m in messages) // 4,
            "completion_tokens": max(1, len(text) // 4),
            "total_tokens": (
                sum(len(m.get("content", "")) for m in messages) // 4
                + max(1, len(text) // 4)
            ),
        }
        yield {"type": "done", "finish_reason": "stop"}


def create_llm_client(settings: Settings) -> BaseLLMClient:
    """Build the active LLM client based on settings, with optional fallback chain.

    Priority:
      1. The provider named by `settings.LLM_PROVIDER` is the primary.
      2. If `LLM_FALLBACK` is true, additional providers are tried in the
         order listed by `settings.LLM_FALLBACK_CHAIN` (primary is skipped).
      3. If the primary can't even be constructed (e.g. `openai` selected but
         `OPENAI_API_KEY` missing), log a loud WARN and fall through to the
         first configurable provider in the fallback chain. If no provider is
         configurable, return an `UnavailableLLMClient` so the server still
         boots (DEMO_MOCK + /health keep working).
    """

    def _ollama() -> OllamaClient:
        return OllamaClient(
            base_url=settings.OLLAMA_BASE_URL,
            model=settings.OLLAMA_MODEL,
            timeout=settings.LLM_TIMEOUT_SECONDS,
        )

    def _openai_compat() -> OpenAICompatibleClient | None:
        if not settings.OPENAI_BASE_URL:
            return None
        return OpenAICompatibleClient(
            base_url=settings.OPENAI_BASE_URL,
            api_key=settings.OPENAI_API_KEY,
            model=settings.OPENAI_MODEL,
            timeout=settings.LLM_TIMEOUT_SECONDS,
        )

    def _openai() -> OpenAIClient | None:
        if not settings.OPENAI_API_KEY:
            return None
        return OpenAIClient(
            api_key=settings.OPENAI_API_KEY,
            model=settings.OPENAI_MODEL,
            timeout=settings.LLM_TIMEOUT_SECONDS,
        )

    makers: dict[str, Any] = {
        "ollama": _ollama,
        "openai_compatible": _openai_compat,
        "openai": _openai,
    }

    def _build(name: str) -> BaseLLMClient | None:
        maker = makers.get(name.lower())
        if maker is None:
            return None
        try:
            return maker()
        except Exception as exc:  # noqa: BLE001
            logger.warning("Failed to build LLM client %r: %s", name, exc)
            return None

    provider = settings.LLM_PROVIDER.lower()
    primary = _build(provider)

    if primary is None:
        # Graceful degradation: pick the first configurable provider from the
        # declared fallback chain. Emit a clear WARN explaining why.
        reason = {
            "openai": "OPENAI_API_KEY is not set",
            "openai_compatible": "OPENAI_BASE_URL is not set",
            "ollama": "ollama client failed to initialise",
        }.get(provider, f"provider {provider!r} is not configured")
        logger.warning(
            "LLM primary provider %r is unavailable (%s). Searching fallback chain %s…",
            provider,
            reason,
            settings.LLM_FALLBACK_CHAIN,
        )
        for candidate_name in settings.LLM_FALLBACK_CHAIN:
            if candidate_name.lower() == provider:
                continue
            candidate = _build(candidate_name)
            if candidate is not None:
                logger.warning(
                    "Using fallback LLM provider %r (%s) as primary.",
                    candidate.name,
                    candidate.model,
                )
                primary = candidate
                break
        if primary is None:
            logger.warning(
                "No LLM provider could be configured. Backend will boot in a "
                "degraded state — /health will report llm_available=false and "
                "live LLM calls will raise. DEMO_MOCK=1 still works."
            )
            return UnavailableLLMClient(reason=reason)

    if not settings.LLM_FALLBACK:
        return primary

    # Build ordered fallback list from LLM_FALLBACK_CHAIN, skipping the primary
    # and any unconfigurable providers.
    fallbacks: list[BaseLLMClient] = []
    seen = {primary.name}
    for name in settings.LLM_FALLBACK_CHAIN:
        candidate = _build(name)
        if candidate is None or candidate.name in seen:
            continue
        fallbacks.append(candidate)
        seen.add(candidate.name)
    if not fallbacks:
        return primary
    return FallbackLLMClient(primary=primary, fallbacks=fallbacks)


def extract_first_json(text: str) -> dict[str, Any] | None:
    """Find the first balanced `{...}` substring and parse it as JSON. Robust to
    LLMs wrapping JSON in prose or markdown fences."""
    import json

    start = text.find("{")
    while start != -1:
        depth = 0
        in_string = False
        escape = False
        for i in range(start, len(text)):
            ch = text[i]
            if escape:
                escape = False
                continue
            if ch == "\\":
                escape = True
                continue
            if ch == '"':
                in_string = not in_string
                continue
            if in_string:
                continue
            if ch == "{":
                depth += 1
            elif ch == "}":
                depth -= 1
                if depth == 0:
                    candidate = text[start : i + 1]
                    try:
                        return json.loads(candidate)
                    except json.JSONDecodeError:
                        break
        start = text.find("{", start + 1)
    return None
