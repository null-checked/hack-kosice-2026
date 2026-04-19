"""Factories for the three sub-agents.

Each sub-agent is a **leaf** `create_agent(...)` graph wrapped in a
`CompiledSubAgent` TypedDict. Originally these were full DeepAgents, but
that design had a pathological flaw:

  `create_deep_agent` unconditionally wires in `SubAgentMiddleware` plus
  a `general-purpose` sub-sub-agent. The general-purpose sub-sub-agent
  is itself a DeepAgent with its own `SubAgentMiddleware` + another
  `general-purpose` → infinite `task(...)` recursion was possible, and
  a single live run burned 365 OpenAI calls with zero filesystem output
  before being killed.

So v2 of this file makes each sub-agent a leaf:

  - `create_agent` from `langchain.agents` (NOT `create_deep_agent`),
  - no `SubAgentMiddleware`, therefore no `task` tool, therefore no
    recursion vector,
  - `FilesystemMiddleware(backend=...)` gives us ls / read_file /
    write_file / edit_file / glob / grep / execute (shell) — the same
    seven tools DeepAgents registers,
  - `TodoListMiddleware()` keeps the per-sub-agent write_todos planner,
  - `ModelCallLimitMiddleware(run_limit=SUBAGENT_CALL_LIMIT)` is a hard
    belt-and-suspenders cap so any remaining pathology (prompt-engineered
    loops, provider flakiness) still terminates gracefully.

Sharing the `LocalShellBackend` instance across the whole tree (same
instance handed to orchestrator + all three sub-agents) means filesystem
edits made by code-writer are visible to test-writer and reviewer. That
is the "they pass files to each other via disk" contract.

The sub-agent prompts in `src/core/prompts.py` already say "Do NOT spawn
further sub-agents" (reviewer explicitly) — leaving them task-free matches
stated intent.
"""

from __future__ import annotations

from collections.abc import Sequence
from typing import Any

from deepagents import CompiledSubAgent
from deepagents.backends import LocalShellBackend
from deepagents.middleware.filesystem import FilesystemMiddleware
from langchain.agents import create_agent
from langchain.agents.middleware import ModelCallLimitMiddleware, TodoListMiddleware
from langchain_core.language_models.chat_models import BaseChatModel
from langchain_core.tools import BaseTool

from src.core.prompts import (
    CODE_WRITER_SYSTEM,
    REVIEWER_SYSTEM,
    TEST_WRITER_SYSTEM,
)

from .tools import make_emit_edit

# Max LLM calls per sub-agent invocation. 20 is generous for the "read brief
# → plan todos → maybe read_file → write_file → emit_edit → summary" shape
# each sub-agent is prompted to follow (typically 4–6 turns). If we hit the
# cap, the ModelCallLimitMiddleware returns a synthetic AI message saying
# "limit exceeded" rather than raising — the orchestrator sees that as the
# sub-agent's output and moves on.
SUBAGENT_CALL_LIMIT: int = 20


def _compile(
    *,
    name: str,
    description: str,
    system_prompt: str,
    model: BaseChatModel,
    backend: LocalShellBackend,
    extra_tools: Sequence[BaseTool | Any],
) -> CompiledSubAgent:
    """Build a single leaf sub-agent.

    `extra_tools` is e.g. `[internet_search]` — the emit_edit tool is
    built here, pre-bound to `name`. The filesystem + shell tools are
    contributed by `FilesystemMiddleware`; `create_agent` discovers them
    via the `middleware.tools` attribute it walks internally.
    """
    emit_edit_tool = make_emit_edit(name)
    inner = create_agent(
        model=model,
        tools=[emit_edit_tool, *extra_tools],
        system_prompt=system_prompt,
        middleware=[
            TodoListMiddleware(),
            FilesystemMiddleware(backend=backend),
            ModelCallLimitMiddleware(
                run_limit=SUBAGENT_CALL_LIMIT,
                exit_behavior="end",
            ),
        ],
        name=name,
    )
    return CompiledSubAgent(  # type: ignore[misc]
        name=name,
        description=description,
        runnable=inner,
    )


def make_code_writer(
    model: BaseChatModel,
    backend: LocalShellBackend,
    extra_tools: Sequence[BaseTool | Any],
) -> CompiledSubAgent:
    return _compile(
        name="code-writer",
        description=(
            "Writes new application/source files. Uses the filesystem and "
            "shell for scratch drafts. Calls emit_edit once per finished "
            "file. MUST run BEFORE test-writer and reviewer."
        ),
        system_prompt=CODE_WRITER_SYSTEM,
        model=model,
        backend=backend,
        extra_tools=extra_tools,
    )


def make_test_writer(
    model: BaseChatModel,
    backend: LocalShellBackend,
    extra_tools: Sequence[BaseTool | Any],
) -> CompiledSubAgent:
    return _compile(
        name="test-writer",
        description=(
            "Writes idiomatic tests for the files code-writer emitted. May "
            "execute the tests via shell to verify. MUST run AFTER "
            "code-writer."
        ),
        system_prompt=TEST_WRITER_SYSTEM,
        model=model,
        backend=backend,
        extra_tools=extra_tools,
    )


def make_reviewer(
    model: BaseChatModel,
    backend: LocalShellBackend,
    extra_tools: Sequence[BaseTool | Any],
) -> CompiledSubAgent:
    return _compile(
        name="reviewer",
        description=(
            "Reviews the code + tests emitted by predecessors. Proposes at "
            "most ONE refinement edit. MUST run LAST, after both "
            "code-writer AND test-writer."
        ),
        system_prompt=REVIEWER_SYSTEM,
        model=model,
        backend=backend,
        extra_tools=extra_tools,
    )


__all__ = [
    "make_code_writer",
    "make_test_writer",
    "make_reviewer",
]
