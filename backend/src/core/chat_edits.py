"""Shared parser + diff helper for structured chat edit envelopes.

Two endpoints consume the `{answer, edits}` JSON envelope an LLM emits when
`allow_edits=True`:

  * `POST /api/v1/code-chat` — legacy non-streaming Q&A. Parses the whole
    response after it arrives.
  * `POST /api/v1/chat` — new streaming endpoint. Buffers tokens and runs
    the same parser once `done` is about to be sent, then emits one
    `code_edit` event per edit.

Both paths call into this module so the parsing behaviour, fence stripping,
and server-computed `diff_unified` stay identical.

Origin: ported from `origin/edits-in-kotlin:backend/src/core/code_chat.py`
(_parse_structured_response + _unified_diff_for_edit) and hoisted up so the
streaming path can reuse it without cyclic imports.
"""

from __future__ import annotations

import difflib
import json
import logging
import re

from pydantic import ValidationError

from src.core.llm_client import extract_first_json
from src.models.api import CodeEdit

logger = logging.getLogger(__name__)


_FENCE_OPEN_RE = re.compile(r"^```(?:json)?\s*\n?", re.IGNORECASE)
_FENCE_CLOSE_RE = re.compile(r"\n?```\s*$")


def strip_fences(text: str) -> str:
    """Strip a leading ```json / ``` fence and trailing ``` if present.

    LLMs sometimes wrap JSON in fenced code blocks despite the prompt
    telling them not to. Keeps inner ``` sequences intact.
    """
    stripped = text.strip()
    stripped = _FENCE_OPEN_RE.sub("", stripped, count=1)
    stripped = _FENCE_CLOSE_RE.sub("", stripped, count=1)
    return stripped.strip()


def unified_diff_for_edit(edit: CodeEdit) -> str:
    """Return a unified diff for this edit, computed server-side.

    Mirrors `refactor_advisor.suggest_refactor`'s conventions: `a/<path>` /
    `b/<path>` headers, three lines of context.
    """
    label = edit.file_path or "code"
    return "".join(
        difflib.unified_diff(
            edit.original_code.splitlines(keepends=True),
            edit.proposed_code.splitlines(keepends=True),
            fromfile=f"a/{label}",
            tofile=f"b/{label}",
            n=3,
        )
    )


def parse_structured_response(raw: str) -> tuple[str | None, list[CodeEdit]]:
    """Parse an `{answer, edits}` JSON envelope from the LLM.

    Returns `(answer, edits)` on success, or `(None, [])` on any failure so
    the caller can fall back to treating the raw text as a plain answer.
    Each returned `CodeEdit` has `diff_unified` populated server-side.
    """
    if not raw.strip():
        return None, []

    data: dict | None = None
    try:
        data = json.loads(strip_fences(raw))
    except json.JSONDecodeError:
        # Lenient secondary path — LLMs sometimes prepend a sentence before
        # the JSON object.
        data = extract_first_json(raw)

    if not isinstance(data, dict):
        return None, []

    answer = data.get("answer")
    if not isinstance(answer, str):
        return None, []

    raw_edits = data.get("edits") or []
    if not isinstance(raw_edits, list):
        raw_edits = []

    parsed_edits: list[CodeEdit] = []
    for entry in raw_edits:
        if not isinstance(entry, dict):
            continue
        try:
            edit = CodeEdit(**entry)
        except ValidationError as exc:
            logger.warning("chat-edits: dropping invalid CodeEdit: %s", exc)
            continue
        edit.diff_unified = unified_diff_for_edit(edit)
        parsed_edits.append(edit)

    return answer, parsed_edits
