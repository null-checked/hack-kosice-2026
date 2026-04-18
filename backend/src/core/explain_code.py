"""Explain legacy / unfamiliar code in plain language.

Backs the `POST /explain-code` endpoint which is consumed by the IntelliJ
`ExplainOldCodeAction` and the VSCode `rubberDuck.explainOldCode` command.
"""

from __future__ import annotations

import logging

from src.core.llm_client import BaseLLMClient, extract_first_json
from src.core.prompts import build_explain_prompt
from src.models.api import ExplainCodeRequest, ExplainCodeResponse

logger = logging.getLogger(__name__)


_MOCK_EXPLANATIONS: list[ExplainCodeResponse] = [
    ExplainCodeResponse(
        explanation=(
            "This function reads a list of numbers, filters out the negatives, "
            "and returns their sum. The loop body is straightforward but the "
            "absence of type hints makes the caller contract implicit."
        ),
        complexity="simple",
        suggestions=[
            "Add type hints: `def sum_positive(xs: list[int]) -> int`.",
            "Use `sum(x for x in xs if x > 0)` instead of the manual loop.",
        ],
        model="mock",
    ),
    ExplainCodeResponse(
        explanation=(
            "The class wraps a raw dict and exposes getters for each known key. "
            "The fall-through `__getattr__` silently returns `None` for unknown "
            "keys, which hides typos at the call site."
        ),
        complexity="moderate",
        suggestions=[
            "Replace the custom wrapper with a `dataclass` or Pydantic model.",
            "Raise `AttributeError` on unknown keys instead of returning `None`.",
        ],
        model="mock",
    ),
]


def _mock_explanation(req: ExplainCodeRequest) -> ExplainCodeResponse:
    idx = (hash(req.code) % len(_MOCK_EXPLANATIONS) + len(_MOCK_EXPLANATIONS)) % len(
        _MOCK_EXPLANATIONS,
    )
    return _MOCK_EXPLANATIONS[idx]


async def explain_code(
    req: ExplainCodeRequest,
    llm: BaseLLMClient,
    *,
    demo_mock: bool = False,
) -> ExplainCodeResponse:
    if demo_mock:
        logger.debug("explain-code: DEMO_MOCK short-circuit")
        return _mock_explanation(req)

    if not req.code.strip():
        return ExplainCodeResponse(
            explanation="(no code supplied)",
            complexity="unknown",
            suggestions=[],
            model=getattr(llm, "model", "unknown"),
        )

    system, user = build_explain_prompt(
        code=req.code,
        file_path=req.file_path or "<unknown>",
        context=req.context,
    )
    raw = await llm.complete(user, system=system, temperature=0.3, max_tokens=600)
    data = extract_first_json(raw) or {}

    explanation = str(data.get("explanation") or raw or "").strip()
    if not explanation:
        explanation = "(the assistant returned nothing — try selecting a smaller region)"

    complexity = str(data.get("complexity") or "unknown").strip().lower()
    if complexity not in {"simple", "moderate", "complex"}:
        complexity = "unknown"

    suggestions_raw = data.get("suggestions") or []
    suggestions: list[str] = []
    if isinstance(suggestions_raw, list):
        for item in suggestions_raw[:6]:
            if isinstance(item, str) and item.strip():
                suggestions.append(item.strip())

    return ExplainCodeResponse(
        explanation=explanation,
        complexity=complexity,
        suggestions=suggestions,
        model=getattr(llm, "model", "unknown"),
    )
