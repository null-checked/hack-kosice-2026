"""POST /complete/stream — SSE sibling of /complete.

Inline completions are the oddball of the streaming set: the final
payload is a short string, not a JSON doc, and the prefix-overlap
stripper runs after the whole thing is received. We still stream the
raw model output so the UI can paint partial completions while the
network round-trip finishes.
"""

from __future__ import annotations

import logging

from fastapi import APIRouter, Depends
from fastapi.responses import StreamingResponse

from src.api.v1.deps import get_llm, get_settings, require_optional_token
from src.core.completer import (  # noqa: PLC2701
    _SYSTEM_PROMPT_FIM,
    _SYSTEM_PROMPT_PREFIX_ONLY,
    _clean_completion,
    _user_prompt,
)
from src.core.llm_client import BaseLLMClient
from src.core.mock_fixtures import drip_tokens, pick_completion_mock
from src.core.streaming import sse_headers, stream_prose_with_final
from src.models.api import CompleteRequest, CompleteResponse
from src.settings import Settings

logger = logging.getLogger(__name__)

router = APIRouter()


@router.post(
    "/complete/stream",
    dependencies=[Depends(require_optional_token)],
)
async def complete_stream_endpoint(
    req: CompleteRequest,
    settings: Settings = Depends(get_settings),
    llm: BaseLLMClient = Depends(get_llm),
) -> StreamingResponse:
    system_prompt = (
        _SYSTEM_PROMPT_FIM if req.suffix.strip() else _SYSTEM_PROMPT_PREFIX_ONLY
    )
    user = _user_prompt(req)

    def _final_builder(full_text: str) -> CompleteResponse:
        text, stop_reason = _clean_completion(full_text, req.prefix)
        return CompleteResponse(
            completion=text,
            stop_reason=stop_reason,
            model=getattr(llm, "model", "unknown"),
        )

    if settings.DEMO_MOCK:
        mock_completion = pick_completion_mock(req.prefix)
        mock_tokens = drip_tokens(mock_completion, chunk_size=3)

        def _mock_final(_text: str) -> CompleteResponse:
            return CompleteResponse(
                completion=mock_completion, stop_reason="eol", model="mock"
            )

        stream = stream_prose_with_final(
            llm,
            prompt=user,
            system=system_prompt,
            final_builder=_mock_final,
            mock_tokens=mock_tokens,
            token_delay=0.03,
        )
    else:
        stream = stream_prose_with_final(
            llm,
            prompt=user,
            system=system_prompt,
            final_builder=_final_builder,
            temperature=0.05,
            max_tokens=max(req.max_tokens, 192),
        )

    return StreamingResponse(
        stream,
        media_type="text/event-stream; charset=utf-8",
        headers=sse_headers(),
    )


__all__ = ["router"]
