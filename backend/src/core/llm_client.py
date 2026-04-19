"""Unified async LLM client on top of LangChain.

Three concrete implementations:
  - `LangChainOpenAIClient`     — wraps `langchain_openai.ChatOpenAI` (handles
                                  both official OpenAI and any
                                  OpenAI-compatible server via `base_url`).
  - `LangChainOllamaClient`     — wraps `langchain_ollama.ChatOllama`.
  - `UnavailableLLMClient`      — placeholder used when no provider can be
                                  configured at startup; keeps /health and
                                  DEMO_MOCK=1 working even without an API key.

Plus `FallbackLLMClient`, which walks a provider chain on failure. Its
`stream()` method only falls back BEFORE the first chunk; once a provider has
started emitting tokens it commits — partial streams are preferable to
silently restarting mid-answer with a different model.
"""

from __future__ import annotations

import logging
from abc import ABC, abstractmethod
from collections.abc import AsyncIterator
from typing import Any

import httpx
from langchain_core.messages import AIMessageChunk, HumanMessage, SystemMessage

from src.settings import Settings

logger = logging.getLogger(__name__)


class LLMUnavailableError(RuntimeError):
    """Raised when no configured LLM provider can serve a request."""


def _extract_chunk_text(chunk: Any) -> str:
    """Normalise an `AIMessageChunk.content` into a plain string.

    Some providers (Anthropic, OpenAI with tool calls) return content as a
    list of typed blocks instead of a string. For raw prose streaming we want
    just the `text` pieces concatenated.
    """
    content = getattr(chunk, "content", None)
    if content is None:
        return ""
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        parts: list[str] = []
        for block in content:
            if isinstance(block, dict):
                if block.get("type") == "text":
                    parts.append(str(block.get("text", "")))
            elif isinstance(block, str):
                parts.append(block)
        return "".join(parts)
    return str(content)


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
    def stream(
        self,
        prompt: str,
        system: str | None = None,
        temperature: float = 0.2,
        max_tokens: int = 2048,
    ) -> AsyncIterator[str]:
        """Stream text chunks as they arrive from the provider."""

    @abstractmethod
    async def available(self) -> bool:
        """Cheap reachability check (≤ 2 s)."""


class _LangChainChatClient(BaseLLMClient):
    """Common scaffolding for clients backed by a LangChain `BaseChatModel`.

    Subclasses only need to build the underlying chat model and implement
    `available()`.
    """

    def __init__(self, chat_model: Any, *, model: str) -> None:
        self._chat = chat_model
        self.model = model

    @staticmethod
    def _messages(prompt: str, system: str | None) -> list[Any]:
        msgs: list[Any] = []
        if system:
            msgs.append(SystemMessage(content=system))
        msgs.append(HumanMessage(content=prompt))
        return msgs

    async def complete(
        self,
        prompt: str,
        system: str | None = None,
        temperature: float = 0.2,
        max_tokens: int = 2048,
    ) -> str:
        bound = self._chat.bind(temperature=temperature, max_tokens=max_tokens)
        result = await bound.ainvoke(self._messages(prompt, system))
        return _extract_chunk_text(result)

    async def stream(  # type: ignore[override]
        self,
        prompt: str,
        system: str | None = None,
        temperature: float = 0.2,
        max_tokens: int = 2048,
    ) -> AsyncIterator[str]:
        bound = self._chat.bind(temperature=temperature, max_tokens=max_tokens)
        async for chunk in bound.astream(self._messages(prompt, system)):
            text = _extract_chunk_text(chunk)
            if text:
                yield text


class LangChainOpenAIClient(_LangChainChatClient):
    """Talks to OpenAI (official) or any OpenAI-compatible server.

    When `base_url` is None we hit api.openai.com; when set we target the
    compatible server (LM Studio / vLLM / Groq / Together / …). The
    `langchain_openai.ChatOpenAI` constructor supports both shapes natively.
    """

    def __init__(
        self,
        *,
        api_key: str,
        model: str,
        base_url: str | None = None,
        timeout: float = 60.0,
    ) -> None:
        from langchain_openai import ChatOpenAI

        self.api_key = api_key
        self.base_url = base_url
        self.timeout = timeout

        kwargs: dict[str, Any] = {
            "model": model,
            "api_key": api_key,
            "timeout": timeout,
            "max_retries": 2,
            "temperature": 0.2,
        }
        if base_url:
            kwargs["base_url"] = base_url
        super().__init__(ChatOpenAI(**kwargs), model=model)
        self.name = "openai_compatible" if base_url else "openai"

    async def available(self) -> bool:
        # For the official OpenAI endpoint we never probe the network — the
        # presence of an API key is treated as "available" (same policy as
        # the existing /health contract). For custom `base_url` servers we
        # do a cheap GET on /models.
        if not self.base_url:
            return bool((self.api_key or "").strip())
        try:
            async with httpx.AsyncClient(timeout=2.0) as client:
                headers = {"Authorization": f"Bearer {self.api_key or 'not-needed'}"}
                r = await client.get(
                    f"{self.base_url.rstrip('/')}/models", headers=headers
                )
                return r.status_code in (200, 401)
        except Exception as exc:  # noqa: BLE001
            logger.debug("OpenAI-compatible unreachable: %s", exc)
            return False


class LangChainOllamaClient(_LangChainChatClient):
    """Talks to a local Ollama daemon via `langchain_ollama.ChatOllama`."""

    name = "ollama"

    def __init__(
        self,
        *,
        base_url: str,
        model: str,
        timeout: float = 60.0,
    ) -> None:
        from langchain_ollama import ChatOllama

        self.base_url = base_url.rstrip("/")
        self.timeout = timeout
        super().__init__(
            ChatOllama(
                base_url=self.base_url,
                model=model,
                temperature=0.2,
            ),
            model=model,
        )

    async def available(self) -> bool:
        try:
            async with httpx.AsyncClient(timeout=2.0) as client:
                r = await client.get(f"{self.base_url}/api/tags")
                return r.status_code == 200
        except Exception as exc:  # noqa: BLE001
            logger.debug("Ollama unreachable: %s", exc)
            return False


class UnavailableLLMClient(BaseLLMClient):
    """Placeholder client used when no provider can be configured at startup.

    Reports `available() -> False` and raises `LLMUnavailableError` on any
    real call. The backend still boots cleanly so `DEMO_MOCK=1` and /health
    continue to work.
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

    async def stream(  # type: ignore[override]
        self,
        prompt: str,
        system: str | None = None,
        temperature: float = 0.2,
        max_tokens: int = 2048,
    ) -> AsyncIterator[str]:
        raise LLMUnavailableError(
            f"No LLM provider configured. Reason: {self.reason}"
        )
        # Unreachable — makes the function a valid async generator per typing.
        if False:  # pragma: no cover
            yield ""


class FallbackLLMClient(BaseLLMClient):
    """Tries `primary` first; on failure walks `fallbacks` in order.

    Streaming semantics: fallback is ONLY applied before the first chunk has
    been yielded. Once streaming has started, a mid-stream exception bubbles
    up — partial output is preferred to silently switching providers.
    """

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

    async def stream(  # type: ignore[override]
        self,
        prompt: str,
        system: str | None = None,
        temperature: float = 0.2,
        max_tokens: int = 2048,
    ) -> AsyncIterator[str]:
        last_err: Exception | None = None
        for client in self.chain:
            try:
                it = client.stream(prompt, system, temperature, max_tokens)
                first = await it.__anext__()
            except StopAsyncIteration:
                # Empty stream — treat as success-with-no-content, exit.
                return
            except Exception as exc:  # noqa: BLE001
                logger.warning(
                    "LLM provider %s stream failed pre-first-chunk: %s",
                    client.name,
                    exc,
                )
                last_err = exc
                continue

            # First chunk arrived — we're committed to this provider.
            yield first
            async for chunk in it:
                yield chunk
            return
        raise LLMUnavailableError(
            f"All LLM providers failed before first chunk. Last error: {last_err}"
        )


def create_llm_client(settings: Settings) -> BaseLLMClient:
    """Build the active LLM client based on settings, with optional fallback chain.

    Priority:
      1. The provider named by `settings.LLM_PROVIDER` is the primary.
      2. If `LLM_FALLBACK` is true, additional providers are tried in the
         order listed by `settings.LLM_FALLBACK_CHAIN` (primary is skipped).
      3. If the primary can't be constructed (e.g. `openai` selected but
         `OPENAI_API_KEY` missing), log a WARN and fall through to the first
         configurable provider in the fallback chain. If none is
         configurable, return an `UnavailableLLMClient` so the server still
         boots.
    """

    def _ollama() -> LangChainOllamaClient:
        return LangChainOllamaClient(
            base_url=settings.OLLAMA_BASE_URL,
            model=settings.OLLAMA_MODEL,
            timeout=settings.LLM_TIMEOUT_SECONDS,
        )

    def _openai_compat() -> LangChainOpenAIClient | None:
        if not settings.OPENAI_BASE_URL:
            return None
        return LangChainOpenAIClient(
            api_key=settings.OPENAI_API_KEY or "not-needed",
            model=settings.OPENAI_MODEL,
            base_url=settings.OPENAI_BASE_URL,
            timeout=settings.LLM_TIMEOUT_SECONDS,
        )

    def _openai() -> LangChainOpenAIClient | None:
        if not settings.OPENAI_API_KEY:
            return None
        return LangChainOpenAIClient(
            api_key=settings.OPENAI_API_KEY,
            model=settings.OPENAI_MODEL,
            base_url=None,
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
