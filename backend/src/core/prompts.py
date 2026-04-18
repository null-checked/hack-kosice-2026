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
