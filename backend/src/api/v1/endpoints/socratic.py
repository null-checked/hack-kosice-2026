"""POST /socratic-question endpoint."""

from __future__ import annotations

import logging

from fastapi import APIRouter, Depends, HTTPException, status

from src.api.v1.deps import get_llm, get_settings, require_optional_token
from src.core.llm_client import BaseLLMClient, LLMUnavailableError
from src.core.socratic_coach import generate_socratic_question
from src.models.api import SocraticRequest, SocraticResponse
from src.settings import Settings

logger = logging.getLogger(__name__)

router = APIRouter()


@router.post(
    "/socratic-question",
    response_model=SocraticResponse,
    dependencies=[Depends(require_optional_token)],
)
async def socratic_endpoint(
    req: SocraticRequest,
    settings: Settings = Depends(get_settings),
    llm: BaseLLMClient = Depends(get_llm),
) -> SocraticResponse:
    logger.debug("socratic: reason=%s events=%d", req.stuck_reason, len(req.recent_events))
    try:
        return await generate_socratic_question(req, llm, demo_mock=settings.DEMO_MOCK)
    except LLMUnavailableError as exc:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail={"error": {"code": "llm_unavailable", "message": str(exc)}},
        ) from exc
    except Exception as exc:  # noqa: BLE001
        logger.exception("socratic failed")
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail={"error": {"code": "socratic_failed", "message": str(exc)}},
        ) from exc
