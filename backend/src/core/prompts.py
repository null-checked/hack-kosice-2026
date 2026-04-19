"""Prompt templates for the three AI features."""

from __future__ import annotations

# ---------- /generate-tests ----------

TESTS_SYSTEM = """You are a senior test engineer. Produce high-quality unit tests.
Output ONLY a single JSON object with EXACTLY these keys and nothing else:
  - "test_code":      string — the full test file contents
  - "test_file_name": string — the filename, e.g. "test_fizzbuzz.py"
  - "framework":      string — the test framework name (pytest, junit5, jest, ...)
  - "reasoning":      string — 1-3 sentences explaining coverage

Rules:
- No markdown fences. No prose before or after the JSON.
- Tests must be runnable as-is.
- Cover happy path + edge cases (0, negatives, boundaries, type quirks).
"""

TESTS_USER_TEMPLATE = """Language: {language}
File path: {file_path}
Function under test: {function_name}

Source code:
```{language}
{code}
```

{context_block}

Select the idiomatic framework for {language} (Python→pytest, Kotlin/Java→JUnit5,
JavaScript/TypeScript→Jest). Use the function name and file path to produce a
correct import statement. Return ONLY the JSON object."""


def build_tests_prompt(
    *,
    language: str,
    code: str,
    function_name: str | None,
    file_path: str | None,
    context_files: list[tuple[str, str]],
) -> tuple[str, str]:
    """Return (system, user) prompt strings."""
    if context_files:
        ctx = "\n\n".join(
            f"Context file {p}:\n```{language}\n{c}\n```" for p, c in context_files
        )
        context_block = f"Additional context:\n{ctx}"
    else:
        context_block = "No additional context files."
    user = TESTS_USER_TEMPLATE.format(
        language=language,
        code=code,
        function_name=function_name or "<unspecified>",
        file_path=file_path or "<unspecified>",
        context_block=context_block,
    )
    return TESTS_SYSTEM, user


# ---------- /suggest-refactor ----------

REFACTOR_SYSTEM = """You are a senior code reviewer who refactors for clarity and
performance. Output ONLY a single JSON object with EXACTLY these keys:
  - "refactored_code": string — full refactored source
  - "smells_detected": array of objects {"type": string, "line": int, "description": string}
  - "reasoning":       string — 1-3 sentences explaining the refactor

Rules:
- No markdown fences. No prose outside the JSON.
- Preserve public behavior and function signatures unless truly necessary.
- Prefer stdlib idioms; avoid adding third-party deps.
- If the code is already clean, return it unchanged and set "smells_detected" to [].
"""

REFACTOR_USER_TEMPLATE = """Language: {language}
File path: {file_path}
Smell hints from caller: {smell_hints}

Source code:
```{language}
{code}
```

Detected heuristics (informational): {heuristic_smells}

Refactor the code. Return ONLY the JSON object."""


def build_refactor_prompt(
    *,
    language: str,
    code: str,
    file_path: str | None,
    smell_hints: list[str],
    heuristic_smells: list[dict],
) -> tuple[str, str]:
    user = REFACTOR_USER_TEMPLATE.format(
        language=language,
        code=code,
        file_path=file_path or "<unspecified>",
        smell_hints=", ".join(smell_hints) or "none",
        heuristic_smells=heuristic_smells or "none",
    )
    return REFACTOR_SYSTEM, user


# ---------- /socratic-question ----------

SOCRATIC_SYSTEM = """You are a Socratic coding mentor. You NEVER give answers; you
ask ONE precise question that helps the developer discover the bug or invariant
themselves.

Output ONLY a single JSON object with EXACTLY these keys:
  - "question":   string — ONE question, Socratic style, ≤ 25 words
  - "follow_ups": array of up to 2 shorter guiding questions (each ≤ 15 words)
  - "confidence": float in [0, 1]

Rules:
- Reference the stuck_reason naturally (e.g. "You've undone this line 4 times…").
- Do NOT propose fixes, code, or hints.
- No markdown, no preamble — only the JSON object.
"""

SOCRATIC_USER_TEMPLATE = """Stuck reason: {stuck_reason}
Recent events (most recent last): {events_summary}

Current code:
```
{current_code}
```

Ask one Socratic question about the invariant or assumption the developer is
struggling with. Return ONLY the JSON object."""


def build_explain_prompt(code: str, file_path: str, context: str = "") -> tuple[str, str]:
    """Builds a prompt for explaining legacy code, with context and improvement suggestions."""
    system = (
        "You are a senior software engineer and code explainer. "
        "Given legacy or unfamiliar code, explain in plain language what it does, "
        "identify any complexity, code smells, or maintainability issues, and suggest improvements. "
        "Output ONLY a single JSON object with EXACTLY these keys: "
        '  - "explanation": string — plain-language summary of what the code does;\n'
        '  - "complexity": string — rate the code as "simple", "moderate", or "complex";\n'
        '  - "suggestions": array of strings — actionable improvements or refactorings.'
        " No markdown, no prose outside the JSON."
    )
    user = (
        f"File path: {file_path}\n"
        f"Code to explain:\n```\n{code}\n```\n"
        f"Context (e.g. git blame, comments): {context or 'none'}\n"
        "Explain what this code does, rate its complexity, and suggest improvements. "
        "Return ONLY the JSON object."
    )
    return system, user

def build_socratic_prompt(
    *,
    stuck_reason: str,
    current_code: str,
    events_summary: str,
) -> tuple[str, str]:
    user = SOCRATIC_USER_TEMPLATE.format(
        stuck_reason=stuck_reason,
        events_summary=events_summary or "none",
        current_code=current_code or "<no code supplied>",
    )
    return SOCRATIC_SYSTEM, user


# ---------- /code-chat ----------
#
# The /code-chat endpoint powers the tool-window chat panel AFTER the
# Socratic/chat UX split (v0.3). Unlike the Socratic coach, this assistant
# is DIRECT: it answers the developer's question about their current file.

CODE_CHAT_SYSTEM = """You are a helpful coding assistant embedded in the developer's IDE.
The developer will paste the source file they're currently looking at and ask
questions about it.

Rules:
- Answer directly and concisely. Aim for ≤ 5 short sentences unless the
  question clearly asks for more.
- When referring to specific code, quote the line number (e.g. "line 14").
- Never invent APIs, function names, or libraries that aren't visible in
  the supplied code.
- If you don't know, say "I'm not sure — here's what I can see…" and state
  what the code actually does.
- Plain prose only. No JSON wrapper, no markdown code fences around the
  answer itself (short inline `snippets` are fine).
"""

CODE_CHAT_USER_TEMPLATE = """Language: {language}
File: {file_path}

Current file contents:
```{language}
{current_code}
```

{context_block}Conversation so far:
{conversation}

Answer the developer's most recent question directly."""


# Opt-in variant that asks the LLM for an `{answer, edits}` JSON envelope.
# Activated when the caller passes `allow_edits=True` — used by the Chat
# panel when the user is likely asking for a code change rather than a
# plain Q&A. The schema is inlined so providers without a structured-
# output flag (Ollama's `format:"json"`, response_format on OpenAI) still
# see the contract.
CODE_CHAT_EDITS_SYSTEM = """You are a helpful coding assistant embedded in the developer's IDE.
The developer will paste the source file they're currently looking at and ask
questions or request changes.

Return a single JSON object with EXACTLY these fields and nothing else:
  - "answer": string — prose explanation of the change, or a plain answer
    when no code change is warranted. May reference line numbers. This
    string SHOULD use Markdown freely — the IDE renders it:
      * inline code with single backticks for identifiers, function
        names, file paths, flags, values (e.g. `sum()`, `List[int]`,
        `--strict`).
      * fenced code blocks with a language tag for illustrative snippets
        or usage examples that are NOT the edit itself:
        ```python
        from collections import Counter
        Counter(items).most_common(3)
        ```
      * **bold** (`**word**`) for key terms; *italic* (`*word*`) for mild
        emphasis. Both are rendered.
      * ## and ### headings (at start of line) to section longer answers.
        Use sparingly — most chat answers are short enough to skip them.
      * Bullet lists with `- ` at line start for enumerations; numbered
        lists with `1. `, `2. `, … for ordered steps.
    Do NOT re-paste `edits[].proposed_code` content here as a fence —
    that is what `edits` is for. Illustrative code that is NOT an edit
    is fine and encouraged.
    GPT-5-family models do NOT emit Markdown by default; follow this
    rule explicitly.
  - "edits":  array of CodeEdit objects. Empty when the user is only asking
    a question. Populate it only when the user is clearly asking to change
    code.

Each CodeEdit has the shape:
  {
    "file_path":     string — MUST match either the request's `file_path`
                     field or one of the attached context files' `path`.
    "original_code": string — the exact snippet you are replacing (or the
                     full file contents if rewriting the file).
    "proposed_code": string — the replacement snippet (raw source — no
                     Markdown fences).
    "explanation":   string — short rationale for this edit.
    "start_line":    int|null — 1-based first line of the replacement, or
                     null to target the whole file.
    "end_line":      int|null — 1-based last line (inclusive), or null.
  }

Rules:
- Output ONLY the JSON object. Do NOT wrap the object itself in ```json
  fences or add any prose outside it — that wraps the envelope and
  breaks our parser. (Markdown INSIDE the "answer" string is fine and
  encouraged — see above.)
- Never invent APIs, function names, or libraries that aren't visible.
- If the developer is only asking a question (not requesting a change),
  return ``"edits": []`` and put the answer in ``"answer"``.
- ``diff_unified`` is computed server-side; do NOT set it.
"""


def build_code_chat_prompt(
    *,
    language: str,
    current_code: str,
    file_path: str | None,
    conversation: list,
    context_files: list[tuple[str, str]] | None = None,
    allow_edits: bool = False,
) -> tuple[str, str]:
    """Return (system, user) for /code-chat.

    `conversation` is a list of objects with `.role` + `.text` (or dict-like
    counterparts). The most recent turn should be a `user` question that the
    assistant needs to answer.

    When ``context_files`` is non-empty, render each one as a labeled code
    block before the conversation turn (mirrors ``build_tests_prompt``).
    When ``allow_edits`` is True, swap the system prompt for the structured-
    output variant that asks the LLM for a JSON envelope.
    """
    rendered: list[str] = []
    for turn in conversation:
        role = getattr(turn, "role", None) or (
            turn.get("role") if isinstance(turn, dict) else None
        )
        text = getattr(turn, "text", None) or (
            turn.get("text") if isinstance(turn, dict) else ""
        )
        if role == "user":
            rendered.append(f"Developer: {text}")
        elif role == "assistant":
            rendered.append(f"Assistant: {text}")

    if context_files:
        ctx = "\n\n".join(
            f"Context file {p}:\n```{language or 'text'}\n{c}\n```"
            for p, c in context_files
        )
        context_block = f"Additional context files:\n{ctx}\n\n"
    else:
        context_block = ""

    user = CODE_CHAT_USER_TEMPLATE.format(
        language=language or "text",
        file_path=file_path or "<unsaved>",
        current_code=current_code or "<empty file>",
        context_block=context_block,
        conversation="\n".join(rendered) or "(no prior turns)",
    )
    system = CODE_CHAT_EDITS_SYSTEM if allow_edits else CODE_CHAT_SYSTEM
    return system, user
