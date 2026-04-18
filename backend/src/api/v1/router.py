"""Aggregate v1 router."""

from __future__ import annotations

from fastapi import APIRouter

from src.api.v1.endpoints import (
    code_chat,
    complete,
    events_ws,
    generate_tests,
    health,
    socratic,
    suggest_refactor,
)

api_v1_router = APIRouter(prefix="/api/v1", tags=["v1"])
api_v1_router.include_router(health.router)
api_v1_router.include_router(generate_tests.router)
api_v1_router.include_router(suggest_refactor.router)
api_v1_router.include_router(socratic.router)
api_v1_router.include_router(complete.router)
api_v1_router.include_router(code_chat.router)
api_v1_router.include_router(events_ws.router)
