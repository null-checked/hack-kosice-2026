"""Generates unit tests via the configured LLM."""

from __future__ import annotations

import logging
import os

from src.core.llm_client import BaseLLMClient, extract_first_json
from src.core.mock_fixtures import MOCK_GENERATE_TESTS
from src.core.prompts import build_tests_prompt
from src.models.api import GenerateTestsRequest, GenerateTestsResponse

logger = logging.getLogger(__name__)


_FRAMEWORK_BY_LANG = {
    "python": "pytest",
    "kotlin": "junit5",
    "java": "junit5",
    "javascript": "jest",
    "typescript": "jest",
}


def _default_test_filename(language: str, function_name: str | None, file_path: str | None) -> str:
    stem = None
    if file_path:
        base = os.path.basename(file_path)
        stem, _ = os.path.splitext(base)
    if not stem and function_name:
        stem = function_name
    if not stem:
        stem = "generated"
    stem = stem.replace("-", "_")
    if language == "python":
        return f"test_{stem}.py"
    if language in {"kotlin", "java"}:
        cls = "".join(p.capitalize() for p in stem.split("_")) or "Generated"
        ext = "kt" if language == "kotlin" else "java"
        return f"{cls}Test.{ext}"
    if language in {"javascript", "typescript"}:
        ext = "ts" if language == "typescript" else "js"
        return f"{stem}.test.{ext}"
    return f"test_{stem}.txt"


async def generate_tests(
    req: GenerateTestsRequest,
    llm: BaseLLMClient,
    *,
    demo_mock: bool = False,
) -> GenerateTestsResponse:
    """Produce tests for the given code. Returns a validated response DTO."""
    if demo_mock:
        logger.debug("generate_tests: DEMO_MOCK short-circuit")
        return GenerateTestsResponse(**MOCK_GENERATE_TESTS)

    system, user = build_tests_prompt(
        language=req.language,
        code=req.code,
        function_name=req.function_name,
        file_path=req.file_path,
        context_files=[(c.path, c.content) for c in req.context_files],
    )
    raw = await llm.complete(user, system=system, temperature=0.2, max_tokens=2048)
    data = extract_first_json(raw) or {}

    framework = str(data.get("framework") or _FRAMEWORK_BY_LANG.get(req.language, "pytest"))
    test_code = str(data.get("test_code") or raw).strip()
    test_file_name = str(
        data.get("test_file_name")
        or _default_test_filename(req.language, req.function_name, req.file_path)
    )
    reasoning = str(data.get("reasoning") or "LLM produced tests; JSON parse was lenient.")

    return GenerateTestsResponse(
        test_code=test_code,
        test_file_name=test_file_name,
        framework=framework,
        reasoning=reasoning,
    )
