"""Inline code completion — fill-in-the-middle style.

Takes a `prefix` (code before caret) and `suffix` (code after caret) and asks
the configured LLM for the text that should appear at the cursor. Multiline-
capable (up to `MAX_LINES` of output), with two safety nets:

- The **system prompt** tells the model "your output will be APPENDED at the
  caret; do NOT include the prefix." Tightened repeatedly because gpt-4o-mini
  in particular loves to echo the last few chars of the prefix.
- Runtime **prefix-overlap stripper**: if the model DOES echo, detect the
  longest suffix-of-prefix that matches the completion's head, and strip it
  so the Tab-accepted text doesn't duplicate what the user already typed.
  This is the belt to the prompt's suspenders.
"""

from __future__ import annotations

import logging

from src.core.llm_client import BaseLLMClient
from src.core.mock_fixtures import pick_completion_mock
from src.models.api import CompleteRequest, CompleteResponse

logger = logging.getLogger(__name__)

MAX_LINES = 5          # cap on multi-line output so completions don't run off
MAX_OVERLAP_CHARS = 128  # scan window for prefix/completion overlap


_SYSTEM_PROMPT_PREFIX_ONLY = (
    "You are a code-completion engine. The developer's caret is at the end "
    "of the shown PREFIX. YOUR OUTPUT WILL BE INSERTED VERBATIM IMMEDIATELY "
    "AFTER THE PREFIX — do NOT repeat any character that is already in the "
    "prefix, including the last partial word the developer was typing. "
    "Output ONLY the raw continuation text — no explanation, no fences, no "
    "repeat of the prefix. Up to 5 lines is fine when it completes an "
    "obvious block; otherwise keep it to one line. Prefer terse, idiomatic "
    "code in the language given."
)


_SYSTEM_PROMPT_FIM = (
    "You are a fill-in-the-middle code-completion engine. The developer's "
    "caret is between PREFIX and SUFFIX. YOUR OUTPUT WILL BE INSERTED "
    "VERBATIM AT THE CARET — do NOT repeat the last characters of the "
    "prefix, and do NOT repeat the first characters of the suffix. Output "
    "ONLY the insertion — no explanation, no fences. Up to 5 lines. The "
    "program should parse cleanly as PREFIX + YOUR_OUTPUT + SUFFIX in the "
    "given language."
)


def _user_prompt(req: CompleteRequest) -> str:
    return (
        f"Language: {req.language}\n"
        f"File: {req.file_path or '(unknown)'}\n"
        f"--- PREFIX (code BEFORE the caret, do NOT echo any of it) ---\n"
        f"{req.prefix}\n"
        f"--- SUFFIX (code AFTER the caret; may be empty) ---\n"
        f"{req.suffix}\n"
        f"--- END ---\n"
        f"Return ONLY the text to insert at the caret."
    )


def _strip_prefix_overlap(prefix: str, completion: str) -> str:
    """If `completion` begins with the last-K chars of `prefix`, drop the
    overlap. Protects against the model echoing the partial word the user
    was already typing (a frequent gpt-4o-mini failure mode)."""
    if not completion or not prefix:
        return completion
    max_k = min(len(prefix), len(completion), MAX_OVERLAP_CHARS)
    # Walk from the longest plausible overlap down to 1 char; stop at the
    # first match so we catch e.g. prefix="...\n    der" + completion="def …"
    # but also prefix="..a b " + completion=" == ...".
    for k in range(max_k, 0, -1):
        if prefix.endswith(completion[:k]):
            return completion[k:]
    return completion


def _clean_completion(raw: str, prefix: str) -> tuple[str, str]:
    """Strip wrappers, drop prefix overlap, cap at `MAX_LINES`. Returns
    `(text, stop_reason)`.

    `stop_reason` is one of:
      - "eol" — returned cleanly
      - "max_lines" — trimmed to MAX_LINES
      - "llm_truncated" — nothing usable in the LLM output
    """
    text = raw.strip()

    # Drop markdown fences if the model added them despite instructions.
    if text.startswith("```"):
        lines = text.splitlines()
        if lines:
            lines = lines[1:]
        if lines and lines[-1].strip().startswith("```"):
            lines = lines[:-1]
        text = "\n".join(lines)

    if not text.strip():
        return "", "llm_truncated"

    # Keep up to MAX_LINES of meaningful output. Include blank lines BETWEEN
    # content lines but stop trimming trailing blanks.
    raw_lines = text.splitlines()
    kept: list[str] = []
    stop = "eol"
    for line in raw_lines:
        if len(kept) >= MAX_LINES:
            stop = "max_lines"
            break
        kept.append(line)
    # Trim trailing blank lines so we don't emit a fat empty tail.
    while kept and not kept[-1].strip():
        kept.pop()
    if not kept:
        return "", "llm_truncated"

    combined = "\n".join(kept)
    # Safety net: strip any echo of the prefix.
    stripped = _strip_prefix_overlap(prefix, combined)
    if not stripped.strip():
        # Model returned nothing beyond the echo — treat as no-op.
        return "", "llm_truncated"
    return stripped, stop


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
    # actually has code after the caret.
    system_prompt = _SYSTEM_PROMPT_FIM if req.suffix.strip() else _SYSTEM_PROMPT_PREFIX_ONLY

    raw = await llm.complete(
        prompt=_user_prompt(req),
        system=system_prompt,
        temperature=0.05,
        max_tokens=max(req.max_tokens, 192),  # headroom for multiline
    )
    text, stop_reason = _clean_completion(raw, req.prefix)
    return CompleteResponse(
        completion=text,
        stop_reason=stop_reason,
        model=getattr(llm, "model", "unknown"),
    )
