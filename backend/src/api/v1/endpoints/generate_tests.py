"""POST /generate-tests endpoint."""

from __future__ import annotations

import logging

from fastapi import APIRouter, Depends, HTTPException, status

from src.api.v1.deps import get_llm, get_settings, require_optional_token
from src.core.llm_client import BaseLLMClient, LLMUnavailableError
from src.core.test_generator import generate_tests
from src.models.api import GenerateTestsRequest, GenerateTestsResponse
from src.settings import Settings

logger = logging.getLogger(__name__)

router = APIRouter()


@router.post(
    "/generate-tests",
    response_model=GenerateTestsResponse,
    dependencies=[Depends(require_optional_token)],
)
async def generate_tests_endpoint(
    req: GenerateTestsRequest,
    settings: Settings = Depends(get_settings),
    llm: BaseLLMClient = Depends(get_llm),
) -> GenerateTestsResponse:
    logger.debug("generate-tests: lang=%s fn=%s", req.language, req.function_name)
    try:
        return await generate_tests(req, llm, demo_mock=settings.DEMO_MOCK)
    except LLMUnavailableError as exc:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail={"error": {"code": "llm_unavailable", "message": str(exc)}},
        ) from exc
    except Exception as exc:  # noqa: BLE001
        logger.exception("generate-tests failed")
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail={"error": {"code": "generate_tests_failed", "message": str(exc)}},
        ) from exc
