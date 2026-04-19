"""Shared FastAPI dependencies: LLM client getter + optional auth scaffold."""

from __future__ import annotations

from fastapi import Header, HTTPException, Request, status

from src.core.llm_client import BaseLLMClient
from src.settings import Settings


def get_settings(request: Request) -> Settings:
    return request.app.state.settings  # type: ignore[no-any-return]


def get_llm(request: Request) -> BaseLLMClient:
    return request.app.state.llm_client  # type: ignore[no-any-return]


def get_orchestrator(request: Request):
    """Return the compiled DeepAgent graph, or None when disabled.

    Endpoints should tolerate None and fall back to the legacy path
    rather than 503ing — the orchestrator is optional."""
    return getattr(request.app.state, "orchestrator", None)


async def require_optional_token(
    request: Request,
    x_rubberduck_token: str | None = Header(default=None, alias="X-RubberDuck-Token"),
) -> None:
    """No-op unless `RUBBERDUCK_TOKEN` is configured."""
    settings: Settings = request.app.state.settings
    expected = settings.RUBBERDUCK_TOKEN
    if not expected:
        return
    if x_rubberduck_token != expected:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail={"error": {"code": "unauthorized", "message": "Invalid token"}},
        )
