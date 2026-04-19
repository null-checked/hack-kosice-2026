"""POST /socratic-question/stream — SSE sibling of /socratic-question.

The question is short (≤ 25 words), so the UX gain from streaming is
modest. We still ship this sibling because the plugin wants consistent
event-stream wiring across all five legacy endpoints.

DEMO_MOCK: picks the same mock bank as the non-streaming path, drips
the question as tokens, and emits the full SocraticResponse on `done`."""

from __future__ import annotations

import logging

from fastapi import APIRouter, Depends
from fastapi.responses import StreamingResponse

from src.api.v1.deps import get_llm, get_settings, require_optional_token
from src.core.llm_client import BaseLLMClient, extract_first_json
from src.core.mock_fixtures import (
    drip_tokens,
    pick_socratic_followup,
    pick_socratic_mock,
)
from src.core.prompts import build_socratic_prompt
from src.core.socratic_coach import (  # noqa: PLC2701
    _clamp_words,
    _format_conversation,
    _summarize_events,
)
from src.core.streaming import sse_headers, stream_prose_with_final
from src.models.api import SocraticRequest, SocraticResponse
from src.settings import Settings

logger = logging.getLogger(__name__)

router = APIRouter()


@router.post(
    "/socratic-question/stream",
    dependencies=[Depends(require_optional_token)],
)
async def socratic_stream_endpoint(
    req: SocraticRequest,
    settings: Settings = Depends(get_settings),
    llm: BaseLLMClient = Depends(get_llm),
) -> StreamingResponse:
    is_followup = bool(req.conversation)

    system, user = build_socratic_prompt(
        stuck_reason=req.stuck_reason,
        current_code=req.current_code,
        events_summary=_summarize_events(req.recent_events),
    )
    if is_followup:
        user = (
            f"{user}\n\nConversation so far:\n{_format_conversation(req)}\n\n"
            "Ask your NEXT Socratic question, building on the developer's "
            "most recent reply. Do not repeat questions you have already "
            "asked. Return ONLY the JSON object."
        )

    def _final_builder(full_text: str) -> SocraticResponse:
        data = extract_first_json(full_text) or {}
        question = _clamp_words(str(data.get("question") or full_text).strip(), 25)
        follow_ups = [
            _clamp_words(str(f), 15)
            for f in (data.get("follow_ups") or [])[:2]
        ]
        try:
            confidence = float(data.get("confidence", 0.7))
        except (TypeError, ValueError):
            confidence = 0.7
        confidence = max(0.0, min(1.0, confidence))
        return SocraticResponse(
            question=question, follow_ups=follow_ups, confidence=confidence
        )

    if settings.DEMO_MOCK:
        if is_followup:
            mock = pick_socratic_followup(req.conversation)
        else:
            mock = pick_socratic_mock(req.stuck_reason, req.current_code)
        mock_tokens = drip_tokens(str(mock["question"]), chunk_size=5)

        def _mock_final(_text: str) -> SocraticResponse:
            return SocraticResponse(**mock)

        stream = stream_prose_with_final(
            llm,
            prompt=user,
            system=system,
            final_builder=_mock_final,
            mock_tokens=mock_tokens,
        )
    else:
        stream = stream_prose_with_final(
            llm,
            prompt=user,
            system=system,
            final_builder=_final_builder,
            temperature=0.4,
            max_tokens=400,
        )

    return StreamingResponse(
        stream,
        media_type="text/event-stream; charset=utf-8",
        headers=sse_headers(),
    )


__all__ = ["router"]
