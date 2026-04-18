"""Inline code completion — fill-in-the-middle style.

Takes a `prefix` (code before caret) and `suffix` (code after caret) and asks
the configured LLM for the single line that should appear at the cursor.
"""

from __future__ import annotations

import logging

from src.core.llm_client import BaseLLMClient
from src.core.mock_fixtures import pick_completion_mock
from src.models.api import CompleteRequest, CompleteResponse

logger = logging.getLogger(__name__)

_SYSTEM_PROMPT_PREFIX_ONLY = (
    "You are a code-completion engine. The developer's caret is at the end "
    "of the shown text and there is NOTHING after it. Continue the line "
    "with the MOST LIKELY next tokens. Output ONLY the raw completion text "
    "that should be inserted after the caret — no explanation, no "
    "commentary, no markdown fences, no repeat of the prefix. Keep the "
    "completion to ONE SINGLE LINE. Prefer terse, idiomatic code in the "
    "language given."
)


_SYSTEM_PROMPT_FIM = (
    "You are a fill-in-the-middle code-completion engine. The developer's "
    "caret is marked <CURSOR>. Emit ONLY the text that should be inserted "
    "at <CURSOR> so that <PREFIX><YOUR_COMPLETION><SUFFIX> parses as a "
    "single coherent program in the given language. Output ONLY the "
    "insertion — no explanation, no fences, no repeat of either side. "
    "Keep it to ONE SINGLE LINE."
)


def _user_prompt(req: CompleteRequest) -> str:
    return (
        f"Language: {req.language}\n"
        f"File: {req.file_path or '(unknown)'}\n"
        f"--- PREFIX (code BEFORE the caret) ---\n"
        f"{req.prefix}\n"
        f"--- SUFFIX (code AFTER the caret; may be empty) ---\n"
        f"{req.suffix}\n"
        f"--- END ---\n"
        f"Return ONLY the single-line text that should be inserted at the caret."
    )


def _clean_completion(raw: str) -> tuple[str, str]:
    """Trim the LLM output to a single line. Returns (text, stop_reason)."""
    # Strip common LLM wrappers.
    text = raw.strip()
    if text.startswith("```"):
        # Drop the opening fence line and the trailing one if present.
        lines = text.splitlines()
        if lines:
            lines = lines[1:]
        if lines and lines[-1].strip().startswith("```"):
            lines = lines[:-1]
        text = "\n".join(lines)
    # Take the first non-empty line only (single-line MVP).
    for line in text.splitlines():
        if line.strip():
            return line.rstrip(), "eol"
    return "", "llm_truncated"


async def complete(
    req: CompleteRequest,
    llm: BaseLLMClient,
    demo_mock: bool,
) -> CompleteResponse:
    if demo_mock:
        return CompleteResponse(
            completion=pick_completion_mock(req.prefix),
            stop_reason="eol",
            model="mock",
        )

    # Pick FIM vs prefix-only system prompt based on whether the file
    # actually has code after the caret. This matters: FIM responses tend
    # to be shorter and more precise when there's a suffix to anchor to.
    system_prompt = _SYSTEM_PROMPT_FIM if req.suffix.strip() else _SYSTEM_PROMPT_PREFIX_ONLY

    raw = await llm.complete(
        prompt=_user_prompt(req),
        system=system_prompt,
        temperature=0.05,
        max_tokens=req.max_tokens,
    )
    text, stop_reason = _clean_completion(raw)
    return CompleteResponse(
        completion=text,
        stop_reason=stop_reason,
        model=getattr(llm, "model", "unknown"),
    )
