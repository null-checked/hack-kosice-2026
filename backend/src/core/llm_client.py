"""Unified async LLM client (Ollama / OpenAI-compatible / OpenAI) via httpx.

No langchain, no openai SDK — just HTTP. Provides:
  - `BaseLLMClient` ABC with `complete()` and `available()`.
  - Three implementations.
  - `create_llm_client(settings)` factory with graceful fallback chain.
"""

from __future__ import annotations

import logging
from abc import ABC, abstractmethod
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


def create_llm_client(settings: Settings) -> BaseLLMClient:
    """Build the active LLM client based on settings, with optional fallback chain."""

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

    provider = settings.LLM_PROVIDER.lower()
    primary: BaseLLMClient
    if provider == "openai_compatible":
        candidate = _openai_compat()
        if candidate is None:
            raise ValueError("LLM_PROVIDER=openai_compatible requires OPENAI_BASE_URL")
        primary = candidate
    elif provider == "openai":
        candidate = _openai()
        if candidate is None:
            raise ValueError("LLM_PROVIDER=openai requires OPENAI_API_KEY")
        primary = candidate
    else:
        primary = _ollama()

    if not settings.LLM_FALLBACK:
        return primary

    # Build ordered fallback list, skipping the primary and any unconfigured ones.
    fallbacks: list[BaseLLMClient] = []
    seen = {primary.name}
    for maker in (_ollama, _openai_compat, _openai):
        candidate = maker()
        if candidate is None or candidate.name in seen:
            continue
        fallbacks.append(candidate)
        seen.add(candidate.name)
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
