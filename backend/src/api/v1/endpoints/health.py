"""GET /health — LLM probe with 2 s timeout."""

from __future__ import annotations

from fastapi import APIRouter, Depends

from src.api.v1.deps import get_llm, get_settings
from src.core.llm_client import BaseLLMClient
from src.models.api import HealthResponse
from src.settings import Settings

router = APIRouter()


@router.get("/health", response_model=HealthResponse)
async def health(
    settings: Settings = Depends(get_settings),
    llm: BaseLLMClient = Depends(get_llm),
) -> HealthResponse:
    if settings.DEMO_MOCK:
        return HealthResponse(
            llm_provider=settings.LLM_PROVIDER,
            llm_model=settings.active_llm_model,
            llm_available=True,
            demo_mock=True,
            version=settings.APP_VERSION,
        )
    available = await llm.available()
    return HealthResponse(
        llm_provider=settings.LLM_PROVIDER,
        llm_model=settings.active_llm_model,
        llm_available=available,
        demo_mock=False,
        version=settings.APP_VERSION,
    )
