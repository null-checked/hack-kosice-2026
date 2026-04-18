"""POST /code-chat endpoint — general-purpose code Q&A for the tool-window chat."""

from __future__ import annotations

import logging

from fastapi import APIRouter, Depends, HTTPException, status

from src.api.v1.deps import get_llm, get_settings, require_optional_token
from src.core.code_chat import answer_code_chat
from src.core.llm_client import BaseLLMClient, LLMUnavailableError
from src.models.api import CodeChatRequest, CodeChatResponse
from src.settings import Settings

logger = logging.getLogger(__name__)

router = APIRouter()


@router.post(
    "/code-chat",
    response_model=CodeChatResponse,
    dependencies=[Depends(require_optional_token)],
)
async def code_chat_endpoint(
    req: CodeChatRequest,
    settings: Settings = Depends(get_settings),
    llm: BaseLLMClient = Depends(get_llm),
) -> CodeChatResponse:
    logger.debug(
        "code-chat: turns=%d lang=%s file=%s",
        len(req.conversation),
        req.language,
        req.file_path,
    )
    try:
        return await answer_code_chat(req, llm, demo_mock=settings.DEMO_MOCK)
    except LLMUnavailableError as exc:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail={"error": {"code": "llm_unavailable", "message": str(exc)}},
        ) from exc
    except Exception as exc:  # noqa: BLE001
        logger.exception("code-chat failed")
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail={"error": {"code": "code_chat_failed", "message": str(exc)}},
        ) from exc
