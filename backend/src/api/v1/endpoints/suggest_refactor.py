"""POST /suggest-refactor endpoint."""

from __future__ import annotations

import logging

from fastapi import APIRouter, Depends, HTTPException, status

from src.api.v1.deps import get_llm, get_settings, require_optional_token
from src.core.llm_client import BaseLLMClient, LLMUnavailableError
from src.core.refactor_advisor import suggest_refactor
from src.models.api import SuggestRefactorRequest, SuggestRefactorResponse
from src.settings import Settings

logger = logging.getLogger(__name__)

router = APIRouter()


@router.post(
    "/suggest-refactor",
    response_model=SuggestRefactorResponse,
    dependencies=[Depends(require_optional_token)],
)
async def suggest_refactor_endpoint(
    req: SuggestRefactorRequest,
    settings: Settings = Depends(get_settings),
    llm: BaseLLMClient = Depends(get_llm),
) -> SuggestRefactorResponse:
    logger.debug("suggest-refactor: lang=%s file=%s", req.language, req.file_path)
    try:
        return await suggest_refactor(req, llm, demo_mock=settings.DEMO_MOCK)
    except LLMUnavailableError as exc:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail={"error": {"code": "llm_unavailable", "message": str(exc)}},
        ) from exc
    except Exception as exc:  # noqa: BLE001
        logger.exception("suggest-refactor failed")
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail={"error": {"code": "suggest_refactor_failed", "message": str(exc)}},
        ) from exc
