"""`emit_edit` — the one custom tool every agent in the tree shares.

Why a factory (`make_emit_edit(agent_name)`) instead of a single tool?

`langgraph.config` in the installed versions exposes `get_stream_writer`,
`get_config`, and `get_store`, but NOT the `get_runtime` helper the plan
originally assumed. Without runtime introspection we can't ask "which
sub-agent is currently executing?" from inside the tool body. So we bind
the agent's name at tool *construction* time: each sub-agent receives a
distinct `emit_edit_<name>` closure that already knows its own identity.
That gives us agent-attributed edits in the `EditSink` without needing
global state.

The tool does three things on each call:

1.  Fills in `diff_unified` if the caller didn't supply one. We use stdlib
    `difflib.unified_diff` — cheap, dependency-free, and the exact format
    the plugin's apply-diff code already consumes.
2.  Appends an entry to the request-scoped `EditSink` (see `collector.py`).
    This is what the FastAPI endpoint reads at the end of a non-streaming
    run to populate `CodeChatResponse.edits`.
3.  Emits a `{"kind": "edit", "agent": ..., "edit": ...}` dict via
    `get_stream_writer()`. When the orchestrator is running under
    `astream(stream_mode=["custom", ...])`, this becomes a `custom` chunk
    that our SSE translator converts into an `event: edit` frame.

Returning a plain string keeps the tool compatible with the standard
ReAct agent loop — no `Command` / state-update return needed because we
write to a ContextVar sink rather than LangGraph state channels.
"""

from __future__ import annotations

import difflib
import logging
from collections.abc import Callable

from langchain_core.tools import StructuredTool
from langgraph.config import get_stream_writer

from src.models.api import CodeEdit

from .collector import append_entry

logger = logging.getLogger(__name__)


def _unified_diff(original: str, proposed: str, file_path: str) -> str:
    """Small wrapper: keeps the `a/` + `b/` prefix convention that plugin
    apply-diff code recognises, and always ends with a newline so the
    output paste-cleanly into a `.patch` buffer."""
    diff = difflib.unified_diff(
        original.splitlines(keepends=True),
        proposed.splitlines(keepends=True),
        fromfile=f"a/{file_path}",
        tofile=f"b/{file_path}",
        n=3,
    )
    return "".join(diff)


def _emit_edit_impl(
    edit: CodeEdit,
    *,
    agent_name: str,
) -> str:
    """Pure function the StructuredTool wraps. Kept separate so unit tests
    can call it directly without spinning up a LangGraph runtime."""
    if not edit.diff_unified:
        edit = edit.model_copy(
            update={
                "diff_unified": _unified_diff(
                    edit.original_code, edit.proposed_code, edit.file_path
                )
            }
        )

    entry = {"agent": agent_name, "edit": edit.model_dump()}
    append_entry(entry)

    # `get_stream_writer()` raises when called outside a streaming run, but
    # also works as a no-op when we're inside `ainvoke`. Guard with a broad
    # except so the non-streaming path doesn't blow up with "no active
    # stream writer" errors — the ContextVar sink is the source of truth
    # for non-stream runs.
    try:
        writer = get_stream_writer()
        writer({"kind": "edit", "agent": agent_name, "edit": edit.model_dump()})
    except Exception:  # noqa: BLE001
        logger.debug("emit_edit: no stream writer (non-streaming run)")

    return f"Edit recorded for {edit.file_path} by {agent_name}."


def make_emit_edit(agent_name: str) -> StructuredTool:
    """Build an `emit_edit` StructuredTool pre-bound to `agent_name`.

    The returned tool has:
      - name:        "emit_edit"
      - args_schema: CodeEdit (pydantic v2 model)
      - description: guides the LLM toward the "final output only" contract

    Each sub-agent gets its own instance so the `EditSink` can attribute
    entries by agent without runtime introspection."""

    def _run(
        file_path: str,
        original_code: str,
        proposed_code: str,
        explanation: str = "",
        start_line: int | None = None,
        end_line: int | None = None,
        diff_unified: str = "",
    ) -> str:
        edit = CodeEdit(
            file_path=file_path,
            original_code=original_code,
            proposed_code=proposed_code,
            explanation=explanation,
            start_line=start_line,
            end_line=end_line,
            diff_unified=diff_unified,
        )
        return _emit_edit_impl(edit, agent_name=agent_name)

    # Explicit, focussed description: the LLM will see this in its tool
    # catalogue, so phrasing matters for whether it calls emit_edit only on
    # user-facing output (as the system prompts instruct).
    description = (
        "Record a proposed code edit for the user. Call this EXACTLY ONCE per "
        "file you want to hand back, and ONLY when the file is final and "
        "ready for the user to apply. Use write_file/edit_file for scratch "
        "work that shouldn't surface in the response."
    )

    return StructuredTool.from_function(
        func=_run,
        name="emit_edit",
        description=description,
        args_schema=CodeEdit,
    )


# Convenience: the orchestrator also emits edits in rare cases (e.g. a
# direct answer with a small patch). Expose one with `agent_name="orchestrator"`
# so callers who don't need per-subagent attribution can grab it directly.
def make_orchestrator_emit_edit() -> StructuredTool:
    return make_emit_edit("orchestrator")


__all__: list[str] = [
    "make_emit_edit",
    "make_orchestrator_emit_edit",
    "_unified_diff",
    "_emit_edit_impl",
]

# Typing aid for callers that want to type-annotate the factory.
EmitEditFactory = Callable[[str], StructuredTool]
