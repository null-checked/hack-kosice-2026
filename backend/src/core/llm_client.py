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
        json_mode: bool = False,
    ) -> str:
        """Return the raw text completion.

        When ``json_mode`` is True, request provider-enforced JSON output if the
        backend supports it. Callers must still defensively parse the result —
        providers that don't support the flag just ignore it and rely on the
        system prompt to ask for JSON.
        """

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
        json_mode: bool = False,
    ) -> str:
        body: dict[str, Any] = {
            "model": self.model,
            "prompt": prompt,
            "stream": False,
            "options": {"temperature": temperature, "num_predict": max_tokens},
        }
        if system:
            body["system"] = system
        if json_mode:
            # Ollama's /api/generate accepts `format: "json"` to constrain
            # output to valid JSON.
            body["format"] = "json"
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

    def _uses_max_completion_tokens(self) -> bool:
        """True for OpenAI models that require ``max_completion_tokens``.

        Covers the GPT-5 family (gpt-5, gpt-5.1, gpt-5.2, gpt-5.4, …) and the
        o-series reasoning models (o1, o3, o4-mini, …). Older models — gpt-4*,
        gpt-3.5-* — still use ``max_tokens``.
        """
        m = self.model.lower().lstrip("ft:")  # allow fine-tuned variants
        return (
            m.startswith("gpt-5")
            or m.startswith("o1")
            or m.startswith("o3")
            or m.startswith("o4")
        )

    async def complete(
        self,
        prompt: str,
        system: str | None = None,
        temperature: float = 0.2,
        max_tokens: int = 2048,
        json_mode: bool = False,
    ) -> str:
        messages: list[dict[str, str]] = []
        if system:
            messages.append({"role": "system", "content": system})
        messages.append({"role": "user", "content": prompt})
        body: dict[str, Any] = {
            "model": self.model,
            "messages": messages,
            "temperature": temperature,
        }
        # OpenAI's GPT-5 family and the o-series reasoning models replaced
        # ``max_tokens`` with ``max_completion_tokens`` and return 400 when
        # the old param is used. Older models (gpt-4*, 3.5*) still accept
        # only ``max_tokens``. Detect by model-name prefix and pick the
        # right key — same numeric value either way.
        if self._uses_max_completion_tokens():
            body["max_completion_tokens"] = max_tokens
        else:
            body["max_tokens"] = max_tokens
        if json_mode:
            # OpenAI and most compatible providers (LM Studio, vLLM, Groq,
            # Together) accept ``response_format={"type": "json_object"}`` to
            # force valid JSON output.
            body["response_format"] = {"type": "json_object"}
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
        json_mode: bool = False,
    ) -> str:
        raise LLMUnavailableError(
            f"No LLM provider configured. Reason: {self.reason}"
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
        json_mode: bool = False,
    ) -> str:
        last_err: Exception | None = None
        for client in self.chain:
            try:
                logger.debug("Trying LLM provider %s (%s)", client.name, client.model)
                return await client.complete(
                    prompt,
                    system,
                    temperature,
                    max_tokens,
                    json_mode=json_mode,
                )
            except Exception as exc:  # noqa: BLE001
                logger.warning("LLM provider %s failed: %s", client.name, exc)
                last_err = exc
                continue
        raise LLMUnavailableError(
            f"All LLM providers failed. Last error: {last_err}"
        )


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
