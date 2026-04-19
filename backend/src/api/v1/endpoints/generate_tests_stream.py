"""POST /generate-tests/stream — SSE sibling of /generate-tests.

Streams the LLM's reasoning + JSON token-by-token, then emits the
parsed `GenerateTestsResponse` on `event: done`. DEMO_MOCK drips the
canned reasoning prose from `MOCK_GENERATE_TESTS["reasoning"]` and
ships the full mock DTO on `done`.

Why keep a final DTO? The plugin already handles rich
GenerateTestsResponse display (framework badge, file-name field);
streaming only the raw text would regress that UX. So: stream for
typing feel, ship structured data at the end.
"""

from __future__ import annotations

import logging

from fastapi import APIRouter, Depends
from fastapi.responses import StreamingResponse

from src.api.v1.deps import get_llm, get_settings, require_optional_token
from src.core.llm_client import BaseLLMClient, extract_first_json
from src.core.mock_fixtures import MOCK_GENERATE_TESTS, drip_tokens
from src.core.prompts import build_tests_prompt
from src.core.streaming import sse_headers, stream_prose_with_final
from src.core.test_generator import _FRAMEWORK_BY_LANG, _default_test_filename  # noqa: PLC2701
from src.models.api import GenerateTestsRequest, GenerateTestsResponse
from src.settings import Settings

logger = logging.getLogger(__name__)

router = APIRouter()


@router.post(
    "/generate-tests/stream",
    dependencies=[Depends(require_optional_token)],
)
async def generate_tests_stream_endpoint(
    req: GenerateTestsRequest,
    settings: Settings = Depends(get_settings),
    llm: BaseLLMClient = Depends(get_llm),
) -> StreamingResponse:
    system, user = build_tests_prompt(
        language=req.language,
        code=req.code,
        function_name=req.function_name,
        file_path=req.file_path,
        context_files=[(c.path, c.content) for c in req.context_files],
    )

    def _final_builder(full_text: str) -> GenerateTestsResponse:
        data = extract_first_json(full_text) or {}
        framework = str(
            data.get("framework") or _FRAMEWORK_BY_LANG.get(req.language, "pytest")
        )
        test_code = str(data.get("test_code") or full_text).strip()
        test_file_name = str(
            data.get("test_file_name")
            or _default_test_filename(req.language, req.function_name, req.file_path)
        )
        reasoning = str(
            data.get("reasoning") or "LLM produced tests; JSON parse was lenient."
        )
        return GenerateTestsResponse(
            test_code=test_code,
            test_file_name=test_file_name,
            framework=framework,
            reasoning=reasoning,
        )

    if settings.DEMO_MOCK:
        logger.debug("generate-tests/stream: DEMO_MOCK drip")
        mock_tokens = drip_tokens(
            str(MOCK_GENERATE_TESTS["reasoning"]), chunk_size=6
        )

        def _mock_final(_text: str) -> GenerateTestsResponse:
            return GenerateTestsResponse(**MOCK_GENERATE_TESTS)

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
            max_tokens=2048,
        )

    return StreamingResponse(
        stream,
        media_type="text/event-stream; charset=utf-8",
        headers=sse_headers(),
    )


__all__ = ["router"]
