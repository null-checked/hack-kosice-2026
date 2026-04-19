"""POST /explain-code/stream — SSE sibling of /explain-code.

Streams the plain-language explanation as it's generated, then emits a
structured `ExplainCodeResponse` (explanation + complexity + suggestions)
on `event: done`. For the "explain old code" UX this is the highest
perceived-latency wins — explanations are long prose and a typing-style
render masks round-trip time well."""

from __future__ import annotations

import logging

from fastapi import APIRouter, Depends
from fastapi.responses import StreamingResponse

from src.api.v1.deps import get_llm, get_settings, require_optional_token
from src.core.explain_code import _mock_explanation  # noqa: PLC2701
from src.core.llm_client import BaseLLMClient, extract_first_json
from src.core.mock_fixtures import drip_tokens
from src.core.prompts import build_explain_prompt
from src.core.streaming import sse_headers, stream_prose_with_final
from src.models.api import ExplainCodeRequest, ExplainCodeResponse
from src.settings import Settings

logger = logging.getLogger(__name__)

router = APIRouter()


@router.post(
    "/explain-code/stream",
    dependencies=[Depends(require_optional_token)],
)
async def explain_code_stream_endpoint(
    req: ExplainCodeRequest,
    settings: Settings = Depends(get_settings),
    llm: BaseLLMClient = Depends(get_llm),
) -> StreamingResponse:
    system, user = build_explain_prompt(
        code=req.code,
        file_path=req.file_path or "<unknown>",
        context=req.context,
    )

    def _final_builder(full_text: str) -> ExplainCodeResponse:
        data = extract_first_json(full_text) or {}
        explanation = str(data.get("explanation") or full_text or "").strip()
        if not explanation:
            explanation = "(the assistant returned nothing — try selecting a smaller region)"
        complexity = str(data.get("complexity") or "unknown").strip().lower()
        if complexity not in {"simple", "moderate", "complex"}:
            complexity = "unknown"
        suggestions: list[str] = []
        raw_suggs = data.get("suggestions") or []
        if isinstance(raw_suggs, list):
            for item in raw_suggs[:6]:
                if isinstance(item, str) and item.strip():
                    suggestions.append(item.strip())
        return ExplainCodeResponse(
            explanation=explanation,
            complexity=complexity,
            suggestions=suggestions,
            model=getattr(llm, "model", "unknown"),
        )

    if settings.DEMO_MOCK:
        mock = _mock_explanation(req)
        mock_tokens = drip_tokens(mock.explanation, chunk_size=7)

        def _mock_final(_text: str) -> ExplainCodeResponse:
            return mock

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
            temperature=0.3,
            max_tokens=600,
        )

    return StreamingResponse(
        stream,
        media_type="text/event-stream; charset=utf-8",
        headers=sse_headers(),
    )


__all__ = ["router"]
