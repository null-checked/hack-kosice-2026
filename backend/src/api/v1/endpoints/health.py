"""GET /health — LLM availability with a 60 s cache.

Key design points:

- For the ``openai`` provider we deliberately *do not* hit the OpenAI API on
  every health call. We merely check that ``OPENAI_API_KEY`` is set and
  non-empty. This keeps /health free (no OpenAI spend, no rate-limit impact)
  while still giving the dashboard / plugin a meaningful status indicator.
- For ``ollama`` / ``openai_compatible`` we do a cheap local probe (localhost,
  2 s timeout) — also cached for 60 s so rapid polling from the plugin tool
  window can't thrash the loopback socket.
"""

from __future__ import annotations

import time

from fastapi import APIRouter, Depends, Request

from src.api.v1.deps import get_llm, get_settings
from src.core.llm_client import BaseLLMClient
from src.models.api import HealthResponse
from src.settings import Settings

router = APIRouter()

# Tiny in-process cache: (available, expires_at_epoch_seconds).
_AVAIL_CACHE: dict[str, tuple[bool, float]] = {}
_CACHE_TTL_SECONDS = 60.0


def _cache_key(settings: Settings) -> str:
    return f"{settings.LLM_PROVIDER}|{settings.active_llm_model}"


async def _probe_available(settings: Settings, llm: BaseLLMClient) -> bool:
    """Cheap availability check, specialised per provider.

    For OpenAI we never actually contact the API — presence of an API key is
    treated as "available" because the real failure mode (bad key, quota,
    network) will surface at first real request and the FallbackLLMClient
    will handle it.
    """
    provider = settings.LLM_PROVIDER.lower()
    if provider == "openai":
        return bool((settings.OPENAI_API_KEY or "").strip())
    # Ollama + openai-compatible: cheap localhost probe (≤ 2 s).
    try:
        return await llm.available()
    except Exception:  # noqa: BLE001
        return False


@router.get("/health", response_model=HealthResponse)
async def health(
    request: Request,
    settings: Settings = Depends(get_settings),
    llm: BaseLLMClient = Depends(get_llm),
) -> HealthResponse:
    orchestrator_available = bool(getattr(request.app.state, "orchestrator", None))

    if settings.DEMO_MOCK:
        return HealthResponse(
            llm_provider=settings.LLM_PROVIDER,
            llm_model=settings.active_llm_model,
            llm_available=True,
            demo_mock=True,
            version=settings.APP_VERSION,
            orchestrator_available=orchestrator_available,
        )

    key = _cache_key(settings)
    now = time.monotonic()
    cached = _AVAIL_CACHE.get(key)
    if cached is not None and cached[1] > now:
        available = cached[0]
    else:
        available = await _probe_available(settings, llm)
        _AVAIL_CACHE[key] = (available, now + _CACHE_TTL_SECONDS)

    return HealthResponse(
        llm_provider=settings.LLM_PROVIDER,
        llm_model=settings.active_llm_model,
        llm_available=available,
        demo_mock=False,
        version=settings.APP_VERSION,
        orchestrator_available=orchestrator_available,
    )
