"""Generates a single Socratic question from recent events + current code."""

from __future__ import annotations

import logging

from src.core.llm_client import BaseLLMClient, extract_first_json
from src.core.mock_fixtures import pick_socratic_mock
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


async def generate_socratic_question(
    req: SocraticRequest,
    llm: BaseLLMClient,
    *,
    demo_mock: bool = False,
) -> SocraticResponse:
    if demo_mock:
        logger.debug("socratic: DEMO_MOCK short-circuit reason=%s", req.stuck_reason)
        return SocraticResponse(**pick_socratic_mock(req.stuck_reason))

    system, user = build_socratic_prompt(
        stuck_reason=req.stuck_reason,
        current_code=req.current_code,
        events_summary=_summarize_events(req.recent_events),
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
