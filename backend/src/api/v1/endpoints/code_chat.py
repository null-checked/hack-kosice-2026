"""POST /code-chat endpoint — general-purpose code Q&A for the tool-window chat.

Two code paths converge here:

  * `allow_edits=False` (default) — the historical single-shot Q&A via
    `answer_code_chat`. One LLM call, plain prose answer, no edits.
  * `allow_edits=True` AND orchestrator is live AND not DEMO_MOCK —
    routes through the multi-agent orchestrator which dispatches
    code-writer → test-writer → reviewer and returns a `CodeChatResponse`
    with populated `edits`.

DEMO_MOCK always short-circuits via `answer_code_chat`, which itself has
a branch for `allow_edits=True` returning the mock orchestrator fixture.
"""

from __future__ import annotations

import logging
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, status

from src.api.v1.deps import (
    get_llm,
    get_orchestrator,
    get_settings,
    require_optional_token,
)
from src.core.code_chat import answer_code_chat, run_orchestrator_nonstreaming
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
    orchestrator: Any = Depends(get_orchestrator),
) -> CodeChatResponse:
    logger.debug(
        "code-chat: turns=%d lang=%s file=%s allow_edits=%s orchestrator=%s",
        len(req.conversation),
        req.language,
        req.file_path,
        req.allow_edits,
        orchestrator is not None,
    )
    try:
        if (
            req.allow_edits
            and orchestrator is not None
            and not settings.DEMO_MOCK
        ):
            return await run_orchestrator_nonstreaming(orchestrator, req, settings)
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
