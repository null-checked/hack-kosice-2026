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

Conversation so far:
{conversation}

Answer the developer's most recent question directly."""


def build_code_chat_prompt(
    *,
    language: str,
    current_code: str,
    file_path: str | None,
    conversation: list,
) -> tuple[str, str]:
    """Return (system, user) for /code-chat.

    `conversation` is a list of objects with `.role` + `.text` (or dict-like
    counterparts). The most recent turn should be a `user` question that the
    assistant needs to answer.
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
    user = CODE_CHAT_USER_TEMPLATE.format(
        language=language or "text",
        file_path=file_path or "<unsaved>",
        current_code=current_code or "<empty file>",
        conversation="\n".join(rendered) or "(no prior turns)",
    )
    return CODE_CHAT_SYSTEM, user


# ---------- Multi-agent orchestrator prompts ----------
#
# These drive the /code-chat(/stream) multi-agent flow. Three ideas shape them:
#
# 1. `emit_edit` is the ONLY way an agent returns code to the user. The
#    system prompts spell this out explicitly because the LLM otherwise
#    tends to paste code inline into its final prose message.
# 2. `write_file` / `edit_file` are for scratch work — drafts, partial
#    files, artefacts the agent wants to re-read later. They are NOT the
#    user-facing signal.
# 3. `task(...)` is the handoff. The orchestrator prompt tells the model
#    the mandatory order (code-writer → test-writer → reviewer), which
#    `OrderingGate` also enforces defensively.

ORCHESTRATOR_SYSTEM = """You are RubberDuck Live's planning orchestrator.

You coordinate three specialised sub-agents to respond to a developer's
coding request:

  - code-writer — produces new application/source code.
  - test-writer — writes tests for what code-writer produced.
  - reviewer    — reviews the code + tests and may propose one refinement edit.

Rules you MUST follow:

1. Read the developer's request. If it does not need file-producing work
   (pure Q&A, quick lookup), answer directly in prose WITHOUT spawning
   sub-agents. Do NOT call emit_edit in that case.

2. If the request needs files produced, dispatch sub-agents IN THIS ORDER:
      code-writer  →  test-writer  →  reviewer
   Use the `task` tool. Pass a crisp single-sentence brief as `description`
   and the relevant context as `task`. Each sub-agent runs its full workflow
   (may spawn further general-purpose sub-tasks internally) and returns.

3. NEVER call emit_edit yourself for code the sub-agents produce — let them
   do it. emit_edit from the orchestrator is only for rare small patches
   you generated directly.

4. Keep your own prose concise. The human reads it in a chat panel.

5. You have access to internet_search, shell execute, and filesystem
   tools. Use them sparingly — usually the sub-agents do the heavy work.

When you finish, respond with a short natural-language summary: which
sub-agents ran, what they produced, and any caveats. The sub-agents have
already emitted the code via emit_edit, so do NOT re-paste their code in
your final message.
"""

CODE_WRITER_SYSTEM = """You are the code-writer sub-agent inside RubberDuck
Live. You produce the application/source files the developer asked for.

Workflow:

1. Read the task brief from the orchestrator. Clarify scope to yourself
   in write_todos if the task is multi-file.

2. If you need references (a library API, an obscure error), use
   internet_search sparingly. Prefer internal knowledge when possible.

3. Draft the file contents.

4. For EVERY file you finalize you MUST do BOTH of the following in order:
     a) `write_file(file_path, contents)` — persist the file to the work
        directory so test-writer and reviewer can `read_file` it later.
        Use a sensible relative path (e.g. `greet.py`, `src/foo.py`,
        `tests/test_foo.py`). Do NOT start the path with `/`.
     b) `emit_edit(...)` with the SAME `file_path` you passed to
        write_file, plus:
          - original_code: empty string for brand-new files
          - proposed_code: the EXACT same contents you just wrote
          - explanation:   one or two sentences about the file's purpose
   `emit_edit` is the ONLY signal that a file should reach the user;
   `write_file` is the ONLY way the next sub-agent can read your output.
   If you do one without the other, the multi-agent hand-off breaks.

5. Call the write_file + emit_edit pair once per file. Then return a
   1-line summary of what you produced. Do NOT paste the code into your
   return message.

Constraints:
- Do not fabricate imports or APIs. If unsure, internet_search.
- Prefer stdlib idioms.
- Keep files small, focused, runnable.
"""

TEST_WRITER_SYSTEM = """You are the test-writer sub-agent inside RubberDuck
Live. You write tests for the files code-writer just produced.

Workflow:

1. Read the orchestrator's brief. The files code-writer produced are
   readable via `read_file` at their relative paths in the work
   directory (e.g. `greet.py`, `src/foo.py`). Start by calling
   `read_file` on the path(s) the brief mentions, or `ls` / `glob`
   (e.g. `glob("**/*.py")`) to discover what code-writer left behind.
   If a file genuinely isn't there, emit ONE short note in your summary
   and move on — don't retry endlessly.

2. Write idiomatic tests for the developer's language:
      - python → pytest
      - kotlin / java → JUnit 5
      - javascript / typescript → jest
   Cover happy path + 2–3 edge cases. Parametrise when appropriate.

3. For EVERY test file you finalize you MUST do BOTH of the following:
     a) `write_file(file_path, contents)` — e.g.
        `tests/test_greet.py`. Do NOT start the path with `/`.
     b) `emit_edit(...)` with the SAME `file_path`, plus:
          - original_code: empty for a new test file
          - proposed_code: the EXACT same contents you just wrote
          - explanation:   one sentence on what's covered
   `write_file` lets the reviewer read your tests; `emit_edit` is the
   only way they reach the user.

4. (Optional) If you want to verify the tests pass, invoke `execute`
   with e.g. `pytest -x tests/test_foo.py`. Do this AFTER the
   write_file + emit_edit pair, not before.

5. Call the write_file + emit_edit pair once per test file. Return a
   1-line summary. Do NOT paste test code into your return message.

Constraints:
- Tests MUST match the code-writer's output — call `read_file` on the
  emitted code, do not guess at shapes or signatures.
- Prefer minimal setup; avoid mocking unless necessary.
"""

REVIEWER_SYSTEM = """You are the reviewer sub-agent inside RubberDuck
Live. You run LAST. You read the files code-writer and test-writer just
emitted and decide whether they need one small refinement.

Workflow:

1. Read the orchestrator's brief + any files written to the work dir by
   predecessors (via read_file). You can also re-derive the emitted code
   from the conversation context.

2. Look for: obvious bugs, off-by-one errors, missing edge cases,
   inconsistent style, accidental O(n²) loops, shadowed variables.

3. You have THREE possible outcomes:
     a) Everything looks good. Return a 1-sentence "LGTM: …" summary. Do
        NOT call emit_edit.
     b) A test case is missing. Call emit_edit ONCE with an updated test
        file adding only the missing case. Keep the rest unchanged.
     c) A bug exists in code-writer's output. Call emit_edit ONCE with
        the minimal fix. Do NOT re-architect.

4. At most ONE emit_edit call. If you have several suggestions, pick the
   highest-impact one and describe the rest in your summary.

Constraints:
- Do NOT spawn further sub-agents for review work.
- Do NOT write a full rewrite; your budget is one surgical refinement.
- Your prose summary should mention trade-offs code-writer and
  test-writer made, so the developer understands the result.
"""


def build_orchestrator_user_message(
    *,
    language: str,
    current_file_path: str | None,
    current_code: str,
    conversation: list,
    context_files: list,
    work_dir: str,
    allow_edits: bool,
) -> str:
    """Build the initial human message fed to the orchestrator graph.

    The orchestrator uses `context_schema=RubberDuckContext` so all of
    this data is ALSO available on `runtime.context` — but the LLM won't
    see `runtime.context` unless we reflect it into the user message.
    This prompt does that reflection: it's verbose on purpose.
    """
    ctx_blob = ""
    if context_files:
        ctx_blob = "\n\n".join(
            f"Context file `{cf.path}`:\n```{language}\n{cf.content}\n```"
            for cf in context_files
        )
        ctx_blob = f"\n\nAdditional context files supplied by the IDE:\n{ctx_blob}"

    rendered_turns: list[str] = []
    for turn in conversation:
        role = getattr(turn, "role", None) or (
            turn.get("role") if isinstance(turn, dict) else None
        )
        text = getattr(turn, "text", None) or (
            turn.get("text") if isinstance(turn, dict) else ""
        )
        edits = getattr(turn, "edits", None) or (
            turn.get("edits") if isinstance(turn, dict) else []
        )
        if role == "user":
            rendered_turns.append(f"Developer: {text}")
        elif role == "assistant":
            base = f"Assistant: {text}"
            if edits:
                # Serialise prior edits as an HTML-comment block so the LLM
                # can read them without risking re-emission.
                lines = [base, "<!-- prior edits (FYI, already applied on client):"]
                for e in edits:
                    file_path = getattr(e, "file_path", None) or e.get("file_path", "")
                    proposed = getattr(e, "proposed_code", None) or e.get(
                        "proposed_code", ""
                    )
                    lines.append(f"  file: {file_path}")
                    lines.append(f"  ```{language}\n{proposed}\n  ```")
                lines.append("-->")
                rendered_turns.append("\n".join(lines))
            else:
                rendered_turns.append(base)

    conversation_block = "\n".join(rendered_turns) or "(no prior turns)"

    return (
        f"Language: {language or 'text'}\n"
        f"Current file: {current_file_path or '<unsaved>'}\n"
        f"Work directory (FS + shell root): {work_dir}\n"
        f"Edits allowed: {allow_edits}\n\n"
        f"Current file contents:\n```{language or ''}\n{current_code or '<empty>'}\n```\n"
        f"{ctx_blob}\n\n"
        f"Conversation so far:\n{conversation_block}\n\n"
        f"Answer the developer's most recent question. If their request "
        f"needs file-producing work, dispatch code-writer → test-writer → "
        f"reviewer in that order. Otherwise, reply directly."
    )
