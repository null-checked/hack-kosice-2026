"""Aggregate v1 router."""

from __future__ import annotations

from fastapi import APIRouter

from src.api.v1.endpoints import (
    chat,
    code_chat,
    complete,
    events_ws,
    explain_code,
    generate_tests,
    health,
    index,
    socratic,
    suggest_refactor,
)

api_v1_router = APIRouter(prefix="/api/v1", tags=["v1"])
api_v1_router.include_router(health.router)
api_v1_router.include_router(generate_tests.router)
api_v1_router.include_router(suggest_refactor.router)
api_v1_router.include_router(socratic.router)
api_v1_router.include_router(complete.router)
# Primary streaming chat endpoint (new as of Step 2 of the chat rollout).
api_v1_router.include_router(chat.router, tags=["chat"])
# Legacy non-streaming chat — kept registered for backwards compatibility.
api_v1_router.include_router(code_chat.router)
api_v1_router.include_router(explain_code.router)
# Phase 2.1 — BM25 semantic file index (/index/build + /index/search).
api_v1_router.include_router(index.router, tags=["index"])
api_v1_router.include_router(events_ws.router)
