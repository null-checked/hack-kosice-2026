"""Code Q&A assistant — answers the developer's questions about their file.

Separate from `socratic_coach.py`: this one **gives direct answers** instead
of returning more questions. It powers the tool-window chat panel after the
UX split where Socratic questions became passive balloons and the chat
became a real assistant.

When the caller sets ``allow_edits=True`` on the request, the prompt switches
to a structured-output variant that asks the LLM for a JSON envelope
``{answer, edits}``. The response's ``diff_unified`` field is then computed
server-side via ``difflib`` so the plugin never has to (see
``chat_edits.parse_structured_response``).
"""

from __future__ import annotations

import logging

from src.core.chat_edits import parse_structured_response
from src.core.llm_client import BaseLLMClient
from src.core.prompts import build_code_chat_prompt
from src.models.api import CodeChatRequest, CodeChatResponse

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


async def answer_code_chat(
    req: CodeChatRequest,
    llm: BaseLLMClient,
    *,
    demo_mock: bool = False,
) -> CodeChatResponse:
    if demo_mock:
        logger.debug("code-chat: DEMO_MOCK short-circuit")
        # Lazy import to avoid a hard dep when mocks aren't used.
        from src.core.mock_fixtures import mock_code_chat_response

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

    answer, edits = parse_structured_response(raw)
    if answer is None:
        # Defensive fallback: JSON parsing/validation failed. Preserve the
        # raw text as an answer so the user still sees something useful.
        logger.warning(
            "code-chat: failed to parse structured response; falling back to raw text"
        )
        fallback = raw or "(the assistant returned nothing — try rephrasing)"
        return CodeChatResponse(answer=fallback, model=model_name, edits=[])

    return CodeChatResponse(answer=answer, model=model_name, edits=edits)
