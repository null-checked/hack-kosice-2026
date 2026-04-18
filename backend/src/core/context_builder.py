"""Assemble the final LLM message list from a `ChatRequest`.

This is the heart of context handling for /api/v1/chat:

  1. Build a system prompt that fuses the RubberDuck persona, an optional skill
     overlay (from the request's `skill_body` or a skills directory on disk),
     and lightweight awareness hints (open files, attached-file summary).
  2. Render the IDE `context` block (selection + attached files + diagnostics)
     as a single `[Context]`-prefixed user message — but only if any section
     is actually populated.
  3. If a `slash_command` is selected, inject a templated instruction so the
     model knows what kind of answer to produce.
  4. Append the raw `messages` list from the request unchanged.
  5. Apply a very rough token-budget guard: if the estimated prompt size
     exceeds `max_tokens_hint`, drop older assistant turns first, then older
     user turns, NEVER dropping the system message or the final user turn.

We intentionally avoid adding `tiktoken` as a dependency — `len(text) // 4`
is plenty accurate for a hackathon token guard and keeps installs lean.
"""

from __future__ import annotations

import logging
from pathlib import Path

from src.models.api import (
    ChatAttachedFile,
    ChatContext,
    ChatDiagnostic,
    ChatMessage,
    ChatRequest,
    ChatSelection,
)

logger = logging.getLogger(__name__)


# Base persona. Kept short — a long system prompt hurts small local models.
BASE_SYSTEM_PROMPT = (
    "You are RubberDuck, a friendly and concise coding assistant embedded in "
    "the developer's IDE. Give direct, useful answers; cite file paths and "
    "line numbers when referring to code; prefer short prose with inline "
    "`code` over large code blocks unless the user asks for a full example. "
    "Never invent APIs that aren't visible in the provided context. If you "
    "don't know something, say so briefly."
)


# Chat-style slash-command overlays. These are INTENTIONALLY different from
# the JSON-output prompts in `prompts.py` (which force a strict JSON schema
# — that's for the dedicated /generate-tests, /suggest-refactor, /explain-code
# endpoints). The chat versions return prose so they render nicely in a chat
# bubble.
SLASH_COMMAND_TEMPLATES: dict[str, str] = {
    "explain": (
        "The user wants you to EXPLAIN the code in the attached selection or "
        "files. Describe in plain language what it does, walk through the "
        "non-obvious parts, call out any complexity hotspots or code smells, "
        "and end with 1-3 concrete improvement suggestions."
    ),
    "tests": (
        "The user wants UNIT TESTS for the attached selection or files. "
        "Produce idiomatic tests for the detected language (Python → pytest, "
        "Kotlin/Java → JUnit5, JS/TS → Jest). Cover the happy path and at "
        "least two edge cases (empty input, boundary values, invalid types). "
        "Return a runnable test file as a fenced code block, then a one-line "
        "summary of what is covered."
    ),
    "refactor": (
        "The user wants you to REFACTOR the attached selection or files for "
        "clarity and performance. Preserve public behavior and signatures "
        "unless strictly necessary. Prefer stdlib idioms; do not add new "
        "third-party dependencies. Return the refactored code as a fenced "
        "code block followed by a short bullet list explaining each change."
    ),
    "fix": (
        "The user wants you to FIX the reported diagnostics in the attached "
        "selection or files. Address the root cause, not the symptom. Return "
        "the fixed code as a fenced code block followed by a short "
        "explanation of WHY the change resolves each diagnostic."
    ),
    "doc": (
        "The user wants DOCSTRINGS/COMMENTS added to the attached selection. "
        "Use the idiomatic docstring style for the language (Python → PEP 257 "
        "triple-quoted, Kotlin/Java → KDoc/Javadoc, JS/TS → JSDoc). Describe "
        "parameters, return value, and any non-obvious behavior. Do not "
        "modify the runtime logic itself — only add or improve documentation."
    ),
}


def estimate_tokens(text: str) -> int:
    """Cheap token estimator — `len(text) // 4` is close enough for a budget guard.

    Avoids the `tiktoken` install (multi-megabyte wheels + rustc for some
    platforms); the 4-chars-per-token heuristic is within ~20% for English
    prose and code and is fine for deciding when to drop history.
    """
    return max(1, len(text) // 4)


def _render_selection(sel: ChatSelection) -> str:
    header = "### Active selection"
    if sel.file_path:
        header += f" — `{sel.file_path}`"
    lang = sel.language or ""
    return f"{header}\n```{lang}\n{sel.code}\n```"


def _render_attached_file(f: ChatAttachedFile) -> str:
    lang = f.language or ""
    return f"### Attached file — `{f.file_path}`\n```{lang}\n{f.content}\n```"


def _render_diagnostics(diags: list[ChatDiagnostic]) -> str:
    lines = ["### Diagnostics"]
    for d in diags:
        loc = f"{d.file_path}:{d.line}" if d.line is not None else d.file_path
        lines.append(f"- [{d.severity}] `{loc}` — {d.message}")
    return "\n".join(lines)


def _render_context_block(ctx: ChatContext) -> str | None:
    """Render the IDE context sections present in `ctx`. Returns None if empty."""
    parts: list[str] = []
    if ctx.selection is not None and ctx.selection.code.strip():
        parts.append(_render_selection(ctx.selection))
    for af in ctx.attached_files:
        parts.append(_render_attached_file(af))
    if ctx.diagnostics:
        parts.append(_render_diagnostics(ctx.diagnostics))
    if not parts:
        return None
    return "[Context]\n" + "\n\n".join(parts)


def _load_skill_body(skill_name: str, skills_dir: Path) -> str | None:
    """Look for `<skills_dir>/<name>/SKILL.md`, return its body (or None)."""
    candidate = skills_dir / skill_name / "SKILL.md"
    if not candidate.is_file():
        return None
    try:
        return candidate.read_text(encoding="utf-8")
    except OSError as exc:
        logger.warning("Failed to read skill %s: %s", candidate, exc)
        return None


def _build_system_prompt(
    req: ChatRequest,
    skills_dir: Path | None,
) -> str:
    """Compose the RubberDuck persona + optional skill overlay + awareness hints."""
    parts: list[str] = [BASE_SYSTEM_PROMPT]

    # Skill overlay: inline `skill_body` wins, then fall back to on-disk lookup.
    skill_text: str | None = None
    if req.skill_body and req.skill_body.strip():
        skill_text = req.skill_body.strip()
    elif req.skill_name and skills_dir is not None:
        skill_text = _load_skill_body(req.skill_name, skills_dir)
    if skill_text:
        parts.append(f"--- Skill overlay ---\n{skill_text}")

    # Awareness hints — keep them terse; they're just nudges for the model.
    hints: list[str] = []
    if req.context.open_files:
        # Cap the list so a user with 200 open files doesn't blow the prompt.
        shown = req.context.open_files[:20]
        overflow = len(req.context.open_files) - len(shown)
        tail = f" (+{overflow} more)" if overflow > 0 else ""
        hints.append("Open files: " + ", ".join(shown) + tail)
    if req.context.attached_files:
        total_bytes = sum(len(f.content) for f in req.context.attached_files)
        hints.append(
            f"{len(req.context.attached_files)} file(s) attached, "
            f"{total_bytes} bytes total."
        )
    if hints:
        parts.append("--- Awareness ---\n" + "\n".join(hints))

    return "\n\n".join(parts)


def _truncate_messages(
    messages: list[dict],
    max_tokens_hint: int,
) -> list[dict]:
    """Drop older turns until the prompt fits under `max_tokens_hint`.

    Strategy:
      - NEVER drop the system message (index 0 if role='system').
      - NEVER drop the last message (usually the user's latest question).
      - Drop older assistant turns first; then older user turns.
    """
    total = sum(estimate_tokens(m.get("content", "")) for m in messages)
    if total <= max_tokens_hint:
        return messages

    # Partition indices. Keep sys + last; candidates to drop are everything
    # else, and we drop older-first (low index first).
    keep_flags = [True] * len(messages)
    sys_idx = 0 if messages and messages[0].get("role") == "system" else None
    last_idx = len(messages) - 1

    def _drop_by_role(target_role: str) -> None:
        nonlocal total
        for i in range(len(messages)):
            if total <= max_tokens_hint:
                return
            if i == sys_idx or i == last_idx:
                continue
            if not keep_flags[i]:
                continue
            if messages[i].get("role") != target_role:
                continue
            total -= estimate_tokens(messages[i].get("content", ""))
            keep_flags[i] = False

    _drop_by_role("assistant")
    _drop_by_role("user")

    dropped = sum(1 for f in keep_flags if not f)
    if dropped:
        logger.info(
            "Truncated chat prompt: dropped %d messages to fit %d-token budget",
            dropped,
            max_tokens_hint,
        )
    return [m for m, keep in zip(messages, keep_flags) if keep]


def build_chat_prompt(
    req: ChatRequest,
    skills_dir: Path | None = None,
    *,
    max_tokens_hint: int = 16000,
) -> list[dict]:
    """Turn a `ChatRequest` into a final `[{role, content}, ...]` message list.

    `skills_dir` is optional and only consulted if `req.skill_name` is set
    AND `req.skill_body` is not. The endpoint passes `None` by default since
    the server doesn't know the IDE project root — the client is expected to
    send the skill body inline via `skill_body`.
    """
    result: list[dict] = []

    # 1. System prompt (persona + skill + awareness).
    system_content = _build_system_prompt(req, skills_dir)
    result.append({"role": "system", "content": system_content})

    # 2. Context block — only when any IDE context is present.
    context_block = _render_context_block(req.context)
    if context_block:
        result.append({"role": "user", "content": context_block})

    # 3. Slash-command instruction.
    if req.slash_command:
        template = SLASH_COMMAND_TEMPLATES.get(req.slash_command)
        if template:
            result.append({"role": "user", "content": f"[Command: /{req.slash_command}]\n{template}"})

    # 4. Original conversation turns, verbatim.
    for m in req.messages:
        result.append({"role": m.role, "content": m.content})

    # 5. Token-budget truncation.
    return _truncate_messages(result, max_tokens_hint)


__all__ = [
    "BASE_SYSTEM_PROMPT",
    "SLASH_COMMAND_TEMPLATES",
    "build_chat_prompt",
    "estimate_tokens",
]
