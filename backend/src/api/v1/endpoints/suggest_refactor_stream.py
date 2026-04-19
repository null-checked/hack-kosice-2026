"""POST /suggest-refactor/stream — SSE sibling of /suggest-refactor.

Streams the LLM's refactor reasoning, then emits a full
`SuggestRefactorResponse` (including unified diff + merged smell list)
on `event: done`. The smell-detection heuristics run the same way they
do in the non-streaming path — we want the two paths to produce the
same DTO on identical inputs."""

from __future__ import annotations

import difflib
import logging

from fastapi import APIRouter, Depends
from fastapi.responses import StreamingResponse

from src.api.v1.deps import get_llm, get_settings, require_optional_token
from src.core.llm_client import BaseLLMClient, extract_first_json
from src.core.mock_fixtures import MOCK_SUGGEST_REFACTOR, drip_tokens
from src.core.prompts import build_refactor_prompt
from src.core.refactor_advisor import detect_smells
from src.core.streaming import sse_headers, stream_prose_with_final
from src.models.api import DetectedSmell, SuggestRefactorRequest, SuggestRefactorResponse
from src.settings import Settings

logger = logging.getLogger(__name__)

router = APIRouter()


@router.post(
    "/suggest-refactor/stream",
    dependencies=[Depends(require_optional_token)],
)
async def suggest_refactor_stream_endpoint(
    req: SuggestRefactorRequest,
    settings: Settings = Depends(get_settings),
    llm: BaseLLMClient = Depends(get_llm),
) -> StreamingResponse:
    heuristic = detect_smells(req.language, req.code)
    system, user = build_refactor_prompt(
        language=req.language,
        code=req.code,
        file_path=req.file_path,
        smell_hints=req.smell_hints,
        heuristic_smells=[s.model_dump() for s in heuristic],
    )

    def _final_builder(full_text: str) -> SuggestRefactorResponse:
        data = extract_first_json(full_text) or {}
        refactored_code = str(data.get("refactored_code") or req.code)
        reasoning = str(data.get("reasoning") or "LLM response parsed leniently.")

        llm_smells: list[DetectedSmell] = []
        for s in data.get("smells_detected") or []:
            try:
                llm_smells.append(DetectedSmell(**s))
            except Exception:  # noqa: BLE001
                continue

        merged: dict[tuple[str, int], DetectedSmell] = {}
        for s in (*heuristic, *llm_smells):
            merged.setdefault((s.type, s.line), s)

        file_label = req.file_path or "code"
        diff = "".join(
            difflib.unified_diff(
                req.code.splitlines(keepends=True),
                refactored_code.splitlines(keepends=True),
                fromfile=f"a/{file_label}",
                tofile=f"b/{file_label}",
                n=3,
            )
        )
        return SuggestRefactorResponse(
            refactored_code=refactored_code,
            diff_unified=diff,
            smells_detected=list(merged.values()),
            reasoning=reasoning,
        )

    if settings.DEMO_MOCK:
        mock_tokens = drip_tokens(
            str(MOCK_SUGGEST_REFACTOR["reasoning"]), chunk_size=6
        )

        def _mock_final(_text: str) -> SuggestRefactorResponse:
            return SuggestRefactorResponse(**MOCK_SUGGEST_REFACTOR)

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
            max_tokens=3072,
        )

    return StreamingResponse(
        stream,
        media_type="text/event-stream; charset=utf-8",
        headers=sse_headers(),
    )


__all__ = ["router"]
