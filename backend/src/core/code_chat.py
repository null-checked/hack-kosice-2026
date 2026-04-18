"""Code Q&A assistant — answers the developer's questions about their file.

Separate from `socratic_coach.py`: this one **gives direct answers** instead
of returning more questions. It powers the tool-window chat panel after the
UX split where Socratic questions became passive balloons and the chat
became a real assistant.

When the caller sets ``allow_edits=True`` on the request, the prompt switches
to a structured-output variant that asks the LLM for a JSON envelope
``{answer, edits}``. The response's ``diff_unified`` field is then computed
server-side via ``difflib`` so the plugin never has to.
"""

from __future__ import annotations

import difflib
import json
import logging
import re

from pydantic import ValidationError

from src.core.llm_client import BaseLLMClient, extract_first_json
from src.core.mock_fixtures import mock_code_chat_response
from src.core.prompts import build_code_chat_prompt
from src.models.api import CodeChatRequest, CodeChatResponse, CodeEdit

logger = logging.getLogger(__name__)


_MOCK_ANSWERS: list[str] = [
    (
        "This function appears to return the input unchanged when the user "
        "hasn't actually asked a specific question. Try highlighting a "
        "method and asking 'what does this do?' for a more useful answer."
    ),
    (
        "Your code reads the file, transforms the rows, and writes them "
        "back. The main risk is that `line 3`'s map over `xs` is O(n²) "
        "because it calls `sum()` inside the loop — pull the sum out once "
        "and cap the inner call."
    ),
    (
        "I can see the function signature and a handful of branches, but "
        "I'm not sure what the caller expects on the empty-input path. "
        "What happens if `xs` is `[]`?"
    ),
    (
        "Short answer: yes, this loop is the hot spot. Long answer: the "
        "nested comprehension allocates a list per iteration. For inputs "
        "≥ 10 000 you'll want a generator and a running accumulator."
    ),
]


def _mock_answer(req: CodeChatRequest) -> str:
    last_question = ""
    for turn in reversed(req.conversation):
        if turn.role == "user":
            last_question = turn.text
            break
    idx = (hash(last_question) % len(_MOCK_ANSWERS) + len(_MOCK_ANSWERS)) % len(
        _MOCK_ANSWERS
    )
    return _MOCK_ANSWERS[idx]


# Strip ```json ... ``` fences (and plain ``` fences) that LLMs sometimes
# wrap JSON in despite being told not to.
_FENCE_RE = re.compile(r"^\s*```(?:json)?\s*|\s*```\s*$", re.IGNORECASE)


def _strip_fences(text: str) -> str:
    stripped = text.strip()
    # Remove a leading ```json / ``` fence and a trailing ``` fence if both
    # are present. Keep inner ``` intact.
    stripped = re.sub(r"^```(?:json)?\s*\n?", "", stripped, count=1, flags=re.IGNORECASE)
    stripped = re.sub(r"\n?```\s*$", "", stripped, count=1)
    return stripped.strip()


def _unified_diff_for_edit(edit: CodeEdit) -> str:
    """Return a unified diff for this edit, computed server-side.

    Mirrors ``refactor_advisor.suggest_refactor``'s conventions: ``a/<path>``
    / ``b/<path>`` headers, three lines of context.
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


def _parse_structured_response(
    raw: str,
) -> tuple[str | None, list[CodeEdit]]:
    """Parse an ``{answer, edits}`` JSON envelope from the LLM.

    Returns ``(answer, edits)`` on success, or ``(None, [])`` on any failure
    so the caller can fall back to treating the raw text as a plain answer.
    """
    if not raw.strip():
        return None, []

    data: dict | None = None
    try:
        data = json.loads(_strip_fences(raw))
    except json.JSONDecodeError:
        # Try the lenient balanced-braces extractor as a secondary path —
        # LLMs sometimes prepend a sentence before the JSON object.
        data = extract_first_json(raw)

    if not isinstance(data, dict):
        return None, []

    answer = data.get("answer")
    if not isinstance(answer, str):
        # Malformed envelope — let the caller decide whether to fall back.
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
            logger.warning("code-chat: dropping invalid CodeEdit: %s", exc)
            continue
        # Canonicalize the diff server-side — overwrite anything the LLM
        # tried to emit for this field.
        edit.diff_unified = _unified_diff_for_edit(edit)
        parsed_edits.append(edit)

    return answer, parsed_edits


async def answer_code_chat(
    req: CodeChatRequest,
    llm: BaseLLMClient,
    *,
    demo_mock: bool = False,
) -> CodeChatResponse:
    if demo_mock:
        logger.debug("code-chat: DEMO_MOCK short-circuit")
        return mock_code_chat_response(req, _mock_answer(req))

    if not req.conversation:
        # No user question yet → return a helpful hint instead of failing.
        return CodeChatResponse(
            answer="Ask me a question about your code — what does it do, where's the bug, how would you refactor it, …",
            model=getattr(llm, "model", "unknown"),
        )

    system, user = build_code_chat_prompt(
        language=req.language,
        current_code=req.current_code,
        file_path=req.file_path,
        conversation=req.conversation,
        context_files=[(c.path, c.content) for c in req.context_files],
        allow_edits=req.allow_edits,
    )

    # Structured-output runs may produce a larger envelope (prose answer +
    # one or more edits) so give them more headroom. Prose-only stays lean.
    max_tokens = 2048 if req.allow_edits else 500
    raw = await llm.complete(
        user,
        system=system,
        temperature=0.3,
        max_tokens=max_tokens,
        json_mode=req.allow_edits,
    )
    raw = (raw or "").strip()
    model_name = getattr(llm, "model", "unknown")

    if not req.allow_edits:
        answer = raw or "(the assistant returned nothing — try rephrasing)"
        return CodeChatResponse(answer=answer, model=model_name)

    answer, edits = _parse_structured_response(raw)
    if answer is None:
        # Defensive fallback: JSON parsing/validation failed. Preserve the
        # raw text as an answer so the user still sees something useful.
        logger.warning(
            "code-chat: failed to parse structured response; falling back to raw text"
        )
        fallback = raw or "(the assistant returned nothing — try rephrasing)"
        return CodeChatResponse(answer=fallback, model=model_name, edits=[])

    return CodeChatResponse(answer=answer, model=model_name, edits=edits)
