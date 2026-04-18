"""POST /explain-code — plain-language explanation of a code block."""

from __future__ import annotations

import logging

from fastapi import APIRouter, Depends, HTTPException, status

from src.api.v1.deps import get_llm, get_settings, require_optional_token
from src.core.explain_code import explain_code
from src.core.llm_client import BaseLLMClient, LLMUnavailableError
from src.models.api import ExplainCodeRequest, ExplainCodeResponse
from src.settings import Settings

logger = logging.getLogger(__name__)

router = APIRouter()


@router.post(
    "/explain-code",
    response_model=ExplainCodeResponse,
    dependencies=[Depends(require_optional_token)],
)
async def explain_code_endpoint(
    req: ExplainCodeRequest,
    settings: Settings = Depends(get_settings),
    llm: BaseLLMClient = Depends(get_llm),
) -> ExplainCodeResponse:
    logger.debug("explain-code: lang=%s file=%s bytes=%d", req.language, req.file_path, len(req.code))
    try:
        return await explain_code(req, llm, demo_mock=settings.DEMO_MOCK)
    except LLMUnavailableError as exc:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail={"error": {"code": "llm_unavailable", "message": str(exc)}},
        ) from exc
    except Exception as exc:  # noqa: BLE001
        logger.exception("explain-code failed")
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail={"error": {"code": "explain_code_failed", "message": str(exc)}},
        ) from exc
