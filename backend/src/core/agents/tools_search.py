"""`internet_search` — DuckDuckGo search exposed as a LangChain tool.

Why DuckDuckGo? The user wanted web search without an API key; DDG's
HTML endpoint is the only mainstream option that's free, keyless, and
has an officially-maintained LangChain wrapper (`langchain-community`).

Gotchas worth knowing:

-   DDG rate-limits aggressively by IP. A burst of 5+ searches in a few
    seconds typically yields empty results for the next minute. Sub-agent
    prompts therefore instruct callers to bundle queries and retry at
    most once.
-   The `duckduckgo-search` package moves fast; public method names
    (`run`, `invoke`) have churned between minor versions. We rely on
    the LangChain wrapper's `.run(query)` which is stable.
-   We **only** construct the tool when the caller opts in via
    `ORCHESTRATOR_WEB_SEARCH_ENABLED`. The module-level import is lazy
    inside the factory so unit tests that never flip the flag don't need
    `langchain-community` on the import path.
"""

from __future__ import annotations

import logging

from langchain_core.tools import StructuredTool

logger = logging.getLogger(__name__)


def make_internet_search() -> StructuredTool:
    """Return an `internet_search(query: str) -> str` StructuredTool.

    Imports `DuckDuckGoSearchRun` lazily so the heavy dependency graph of
    `langchain_community` is only pulled in when search is actually
    enabled. Returns a StructuredTool rather than the raw DDG wrapper so
    the tool surface is uniform across the orchestrator and sub-agents
    (same name, same description format)."""
    from langchain_community.tools import DuckDuckGoSearchRun  # noqa: PLC0415

    _dg = DuckDuckGoSearchRun()

    def _run(query: str) -> str:
        """Run a DDG search and return a single string of result snippets.

        The LangChain wrapper already joins the snippets into one string;
        we forward it as-is. Any exception (rate-limit, DNS, timeout) is
        caught and returned as a short error sentence rather than raising
        — the calling agent's prompt handles the "search failed, rely on
        your own knowledge" fallback better than a stack trace would."""
        try:
            return _dg.run(query)
        except Exception as exc:  # noqa: BLE001
            logger.info("internet_search failed: %s", exc)
            return f"Search unavailable ({exc.__class__.__name__}). Proceed with own knowledge."

    description = (
        "Search the public web (DuckDuckGo) for information relevant to the "
        "coding task. Use this for API signatures, error-message lookups, or "
        "recent library behaviour. Returns text snippets from the top results. "
        "Rate-limited — batch queries, don't spam."
    )

    return StructuredTool.from_function(
        func=_run,
        name="internet_search",
        description=description,
    )


__all__ = ["make_internet_search"]
