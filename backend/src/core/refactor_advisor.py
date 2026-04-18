"""Suggests refactors: heuristic smell detection + LLM-driven rewrite + unified diff."""

from __future__ import annotations

import ast
import difflib
import logging
import re

from src.core.llm_client import BaseLLMClient, extract_first_json
from src.core.mock_fixtures import MOCK_SUGGEST_REFACTOR
from src.core.prompts import build_refactor_prompt
from src.models.api import DetectedSmell, SuggestRefactorRequest, SuggestRefactorResponse

logger = logging.getLogger(__name__)


# ---------- heuristic smell detection ----------

_CONTROL_KEYWORDS_RE = re.compile(r"\b(if|for|while|elif|case|when)\b")
_MAGIC_NUMBER_RE = re.compile(r"(?<![\w.])(\d{3,})(?![\w.])")


def _python_max_nesting(code: str) -> int:
    try:
        tree = ast.parse(code)
    except SyntaxError:
        return 0

    max_depth = 0

    class _Walker(ast.NodeVisitor):
        def __init__(self) -> None:
            self.depth = 0

        def _enter(self, node: ast.AST) -> None:
            self.depth += 1
            nonlocal max_depth
            max_depth = max(max_depth, self.depth)
            self.generic_visit(node)
            self.depth -= 1

        visit_For = _enter
        visit_While = _enter
        visit_AsyncFor = _enter

    _Walker().visit(tree)
    return max_depth


def detect_smells(language: str, code: str) -> list[DetectedSmell]:
    """Return lightweight heuristic smells that supplement the LLM's suggestions."""
    smells: list[DetectedSmell] = []
    lines = code.splitlines()

    # Long function
    if len(lines) > 40:
        smells.append(
            DetectedSmell(
                type="long_function",
                line=1,
                description=f"Block is {len(lines)} lines long; consider splitting.",
            )
        )

    # Cyclomatic-ish hint
    ctrl_count = sum(len(_CONTROL_KEYWORDS_RE.findall(l)) for l in lines)
    if ctrl_count > 10:
        smells.append(
            DetectedSmell(
                type="high_cyclomatic_complexity",
                line=1,
                description=f"{ctrl_count} control-flow keywords; cognitive load is high.",
            )
        )

    # Nested loops (Python-accurate; rough elsewhere)
    if language == "python":
        depth = _python_max_nesting(code)
        if depth >= 3:
            smells.append(
                DetectedSmell(
                    type="nested_loops",
                    line=1,
                    description=f"Loop nesting depth {depth}; O(n^{depth}) hot path.",
                )
            )

    # Magic numbers
    for idx, line in enumerate(lines, start=1):
        for m in _MAGIC_NUMBER_RE.finditer(line):
            val = m.group(1)
            if val in {"100", "1000"}:
                continue
            smells.append(
                DetectedSmell(
                    type="magic_number",
                    line=idx,
                    description=f"Literal {val} on line {idx}; extract a named constant.",
                )
            )
            break  # one per line is enough

    return smells


# ---------- main entry point ----------


async def suggest_refactor(
    req: SuggestRefactorRequest,
    llm: BaseLLMClient,
    *,
    demo_mock: bool = False,
) -> SuggestRefactorResponse:
    if demo_mock:
        logger.debug("suggest_refactor: DEMO_MOCK short-circuit")
        return SuggestRefactorResponse(**MOCK_SUGGEST_REFACTOR)

    heuristic = detect_smells(req.language, req.code)

    system, user = build_refactor_prompt(
        language=req.language,
        code=req.code,
        file_path=req.file_path,
        smell_hints=req.smell_hints,
        heuristic_smells=[s.model_dump() for s in heuristic],
    )
    raw = await llm.complete(user, system=system, temperature=0.2, max_tokens=3072)
    data = extract_first_json(raw) or {}

    refactored_code = str(data.get("refactored_code") or req.code)
    reasoning = str(data.get("reasoning") or "LLM response parsed leniently.")

    llm_smells_raw = data.get("smells_detected") or []
    llm_smells: list[DetectedSmell] = []
    for s in llm_smells_raw:
        try:
            llm_smells.append(DetectedSmell(**s))
        except Exception:  # noqa: BLE001
            continue

    # Merge heuristic + LLM smells, de-duped by (type, line).
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
