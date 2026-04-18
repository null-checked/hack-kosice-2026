"""POST /complete endpoint — inline fill-in-the-middle completion."""

from __future__ import annotations

import logging

from fastapi import APIRouter, Depends, HTTPException, status

from src.api.v1.deps import get_llm, get_settings, require_optional_token
from src.core.completer import complete
from src.core.llm_client import BaseLLMClient, LLMUnavailableError
from src.models.api import CompleteRequest, CompleteResponse
from src.settings import Settings

logger = logging.getLogger(__name__)

router = APIRouter()


@router.post(
    "/complete",
    response_model=CompleteResponse,
    dependencies=[Depends(require_optional_token)],
)
async def complete_endpoint(
    req: CompleteRequest,
    settings: Settings = Depends(get_settings),
    llm: BaseLLMClient = Depends(get_llm),
) -> CompleteResponse:
    logger.debug(
        "complete: lang=%s file=%s prefix_chars=%d",
        req.language,
        req.file_path,
        len(req.prefix),
    )
    try:
        return await complete(req, llm, demo_mock=settings.DEMO_MOCK)
    except LLMUnavailableError as exc:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail={"error": {"code": "llm_unavailable", "message": str(exc)}},
        ) from exc
    except Exception as exc:  # noqa: BLE001
        logger.exception("complete failed")
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail={"error": {"code": "complete_failed", "message": str(exc)}},
        ) from exc
