"""Generates a single Socratic question from recent events + current code."""

from __future__ import annotations

import logging

from src.core.llm_client import BaseLLMClient, extract_first_json
from src.core.mock_fixtures import pick_socratic_followup, pick_socratic_mock
from src.core.prompts import build_socratic_prompt
from src.models.api import SocraticRequest, SocraticResponse
from src.models.events import DeveloperEvent

logger = logging.getLogger(__name__)


def _summarize_events(events: list[DeveloperEvent], limit: int = 12) -> str:
    tail = events[-limit:]
    parts: list[str] = []
    for e in tail:
        bits = [e.type.value]
        if e.file:
            bits.append(e.file)
        if "line" in e.payload:
            bits.append(f"line={e.payload['line']}")
        if "outcome" in e.payload:
            bits.append(f"outcome={e.payload['outcome']}")
        parts.append("(" + ", ".join(bits) + ")")
    return " -> ".join(parts) if parts else "none"


def _clamp_words(text: str, max_words: int) -> str:
    words = text.split()
    if len(words) <= max_words:
        return text.strip()
    return " ".join(words[:max_words]).rstrip(",;:") + "…"


def _format_conversation(req: SocraticRequest) -> str:
    """Render the conversation-so-far for the live-LLM prompt."""
    if not req.conversation:
        return "(no prior turns)"
    lines: list[str] = []
    for turn in req.conversation:
        prefix = "Coach" if turn.role == "coach" else "Developer"
        lines.append(f"{prefix}: {turn.text}")
    return "\n".join(lines)


async def generate_socratic_question(
    req: SocraticRequest,
    llm: BaseLLMClient,
    *,
    demo_mock: bool = False,
) -> SocraticResponse:
    is_followup = bool(req.conversation)

    if demo_mock:
        logger.debug(
            "socratic: DEMO_MOCK short-circuit reason=%s is_followup=%s",
            req.stuck_reason,
            is_followup,
        )
        if is_followup:
            return SocraticResponse(**pick_socratic_followup(req.conversation))
        return SocraticResponse(
            **pick_socratic_mock(req.stuck_reason, req.current_code),
        )

    system, user = build_socratic_prompt(
        stuck_reason=req.stuck_reason,
        current_code=req.current_code,
        events_summary=_summarize_events(req.recent_events),
    )
    if is_followup:
        # Append the conversation-so-far and ask for the next coach turn.
        user = (
            f"{user}\n\nConversation so far:\n{_format_conversation(req)}\n\n"
            "Ask your NEXT Socratic question, building on the developer's "
            "most recent reply. Do not repeat questions you have already "
            "asked. Return ONLY the JSON object."
        )
    raw = await llm.complete(user, system=system, temperature=0.4, max_tokens=400)
    data = extract_first_json(raw) or {}

    question = _clamp_words(str(data.get("question") or raw).strip(), 25)
    follow_ups_raw = data.get("follow_ups") or []
    follow_ups = [_clamp_words(str(f), 15) for f in follow_ups_raw[:2]]
    try:
        confidence = float(data.get("confidence", 0.7))
    except (TypeError, ValueError):
        confidence = 0.7
    confidence = max(0.0, min(1.0, confidence))

    return SocraticResponse(question=question, follow_ups=follow_ups, confidence=confidence)
