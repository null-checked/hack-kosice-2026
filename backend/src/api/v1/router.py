"""Aggregate v1 router."""

from __future__ import annotations

from fastapi import APIRouter

from src.api.v1.endpoints import (
    code_chat,
    code_chat_stream,
    complete,
    complete_stream,
    events_ws,
    explain_code,
    explain_code_stream,
    generate_tests,
    generate_tests_stream,
    health,
    socratic,
    socratic_stream,
    suggest_refactor,
    suggest_refactor_stream,
)

api_v1_router = APIRouter(prefix="/api/v1", tags=["v1"])
api_v1_router.include_router(health.router)
api_v1_router.include_router(generate_tests.router)
api_v1_router.include_router(generate_tests_stream.router)
api_v1_router.include_router(suggest_refactor.router)
api_v1_router.include_router(suggest_refactor_stream.router)
api_v1_router.include_router(socratic.router)
api_v1_router.include_router(socratic_stream.router)
api_v1_router.include_router(complete.router)
api_v1_router.include_router(complete_stream.router)
api_v1_router.include_router(code_chat.router)
api_v1_router.include_router(code_chat_stream.router)
api_v1_router.include_router(explain_code.router)
api_v1_router.include_router(explain_code_stream.router)
api_v1_router.include_router(events_ws.router)
