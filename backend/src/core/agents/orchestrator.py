"""`build_orchestrator(settings)` — compiles the top-level DeepAgent.

Called once at FastAPI startup from `main.py:lifespan`. The result is
stored on `app.state.orchestrator` and returned by the `get_orchestrator`
dependency. None if orchestration is disabled (DEMO_MOCK, missing API
key, non-OpenAI provider, or `ORCHESTRATOR_ENABLED=false`).

Why we return None rather than raising:

  The FastAPI app must boot cleanly regardless of orchestrator
  availability. Users running in DEMO_MOCK, local-only, or without an
  OpenAI key should still get a working /health endpoint + working
  legacy endpoints. The `/code-chat` endpoint checks for `None` at
  request time and falls back to the single-shot `answer_code_chat`
  path.
"""

from __future__ import annotations

import logging
import os
from typing import Any

from deepagents import create_deep_agent
from deepagents.backends import LocalShellBackend
from langchain.agents.middleware import ModelCallLimitMiddleware
from langchain_openai import ChatOpenAI
from langgraph.graph.state import CompiledStateGraph

from src.core.prompts import ORCHESTRATOR_SYSTEM
from src.settings import Settings

from .context import RubberDuckContext
from .middleware import OrderingGate
from .subagents import make_code_writer, make_reviewer, make_test_writer
from .tools import make_orchestrator_emit_edit

logger = logging.getLogger(__name__)

# Hard cap on orchestrator-level LLM calls per request. Worst case the
# orchestrator runs ~2 turns per sub-agent dispatch (dispatch + observe
# the ToolMessage return) + 1 final summary: 3 sub-agents × 2 turns + 1 =
# 7 turns nominal. Leave generous headroom (30) for re-planning when
# OrderingGate blocks a call and the model retries. Each sub-agent has
# its own 20-call ceiling (see subagents.SUBAGENT_CALL_LIMIT) — absolute
# worst case is 30 + 3*20 = 90 LLM calls per request, bounded.
ORCHESTRATOR_CALL_LIMIT: int = 30


def _build_chat_openai(settings: Settings) -> ChatOpenAI:
    """Instantiate the ChatOpenAI client the orchestrator + sub-agents share.

    We pass `base_url` only when set — leaving `base_url=None` makes
    ChatOpenAI default to `api.openai.com`. `max_retries=2` matches the
    legacy httpx behaviour we're replacing."""
    kwargs: dict[str, Any] = {
        "model": settings.OPENAI_MODEL,
        "api_key": settings.OPENAI_API_KEY,
        "timeout": settings.LLM_TIMEOUT_SECONDS,
        "max_retries": 2,
        "temperature": 0.2,
    }
    if settings.OPENAI_BASE_URL:
        kwargs["base_url"] = settings.OPENAI_BASE_URL
    return ChatOpenAI(**kwargs)


def _should_build(settings: Settings) -> tuple[bool, str]:
    """Return (build?, reason-if-not). Kept explicit for log clarity."""
    if settings.DEMO_MOCK:
        return False, "DEMO_MOCK=1"
    if not settings.ORCHESTRATOR_ENABLED:
        return False, "ORCHESTRATOR_ENABLED=false"
    provider = (settings.LLM_PROVIDER or "").lower()
    if provider not in {"openai", "openai_compatible"}:
        return False, f"LLM_PROVIDER={provider!r} (orchestrator requires openai or openai_compatible)"
    if not (settings.OPENAI_API_KEY or "").strip():
        return False, "OPENAI_API_KEY is empty"
    return True, ""


def build_orchestrator(settings: Settings) -> CompiledStateGraph | None:
    """Assemble the orchestrator graph or return None.

    The shared `LocalShellBackend` is critical: we create it ONCE and
    pass the same instance into the orchestrator and every sub-agent.
    That means all agents see the same file-tree view and can hand
    files off via the filesystem.
    """
    should, reason = _should_build(settings)
    if not should:
        logger.info("Orchestrator not built: %s", reason)
        return None

    try:
        model = _build_chat_openai(settings)
    except Exception as exc:  # noqa: BLE001
        logger.warning("Orchestrator not built: ChatOpenAI init failed: %s", exc)
        return None

    work_dir = settings.ORCHESTRATOR_WORK_DIR or os.getcwd()
    try:
        backend = LocalShellBackend(
            root_dir=work_dir,
            timeout=settings.ORCHESTRATOR_SHELL_TIMEOUT,
            max_output_bytes=settings.ORCHESTRATOR_SHELL_MAX_OUTPUT_BYTES,
        )
    except Exception as exc:  # noqa: BLE001
        logger.warning("Orchestrator not built: LocalShellBackend init failed: %s", exc)
        return None

    # Build search tool lazily so unit-test environments that opt out don't
    # import `langchain_community` unnecessarily.
    extra_tools: list[Any] = []
    if settings.ORCHESTRATOR_WEB_SEARCH_ENABLED:
        try:
            from .tools_search import make_internet_search  # noqa: PLC0415

            extra_tools.append(make_internet_search())
        except Exception as exc:  # noqa: BLE001
            logger.warning("Web search tool unavailable: %s", exc)

    subagents = [
        make_code_writer(model, backend, extra_tools),
        make_test_writer(model, backend, extra_tools),
        make_reviewer(model, backend, extra_tools),
    ]

    logger.info(
        "Orchestrator active — work_dir=%s, shell_timeout=%ss, search=%s, subagents=%s",
        work_dir,
        settings.ORCHESTRATOR_SHELL_TIMEOUT,
        bool(extra_tools),
        [sa["name"] for sa in subagents],
    )

    # OrderingGate sits at the orchestrator level to enforce
    # code-writer → test-writer → reviewer. The sub-agents are now
    # LEAF `create_agent` graphs (see subagents.py) so they have no
    # nested `task` tool — nothing further to gate below this layer.
    # ModelCallLimitMiddleware is a fail-safe: if the orchestrator gets
    # stuck re-planning after a blocked dispatch, it terminates after
    # ORCHESTRATOR_CALL_LIMIT turns instead of burning the API bill.
    return create_deep_agent(
        model=model,
        tools=[make_orchestrator_emit_edit(), *extra_tools],
        subagents=subagents,
        system_prompt=ORCHESTRATOR_SYSTEM,
        middleware=[
            OrderingGate(),
            ModelCallLimitMiddleware(
                run_limit=ORCHESTRATOR_CALL_LIMIT,
                exit_behavior="end",
            ),
        ],
        context_schema=RubberDuckContext,
        backend=backend,
        name="orchestrator",
    )


__all__ = ["build_orchestrator"]
