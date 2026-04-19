"""FastAPI app factory for RubberDuck Live."""

from __future__ import annotations

import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from src.api.v1.router import api_v1_router
from src.core.agents import build_orchestrator
from src.core.llm_client import create_llm_client
from src.settings import settings

logger = logging.getLogger(__name__)


def _configure_logging(level: str) -> None:
    logging.basicConfig(
        level=getattr(logging, level.upper(), logging.INFO),
        format="%(asctime)s %(levelname)s %(name)s: %(message)s",
    )


@asynccontextmanager
async def lifespan(app: FastAPI):
    _configure_logging(settings.LOG_LEVEL)
    logger.info(
        "Starting %s v%s on %s:%s (demo_mock=%s, provider=%s, model=%s)",
        settings.APP_NAME,
        settings.APP_VERSION,
        settings.APP_HOST,
        settings.APP_PORT,
        settings.DEMO_MOCK,
        settings.LLM_PROVIDER,
        settings.active_llm_model,
    )
    # create_llm_client handles graceful degradation internally (emits WARNs
    # and returns an UnavailableLLMClient if nothing is configurable), so the
    # backend always boots — DEMO_MOCK=1 and /health continue to work even
    # when no API key is set.
    llm_client = create_llm_client(settings)

    # Probe LLM at startup (non-blocking best effort).
    try:
        available = await llm_client.available()
        logger.info("LLM available at startup: %s", available)
    except Exception as exc:  # noqa: BLE001
        logger.warning("LLM probe failed: %s", exc)

    # Build multi-agent orchestrator. Returns None when disabled/unavailable
    # (DEMO_MOCK=1, no OpenAI key, wrong provider, or ORCHESTRATOR_ENABLED=false).
    # The /code-chat endpoint checks for None at request time and falls back
    # to the single-shot path, so the app boots cleanly either way.
    try:
        orchestrator = build_orchestrator(settings)
    except Exception as exc:  # noqa: BLE001
        logger.warning("Orchestrator build failed: %s", exc)
        orchestrator = None

    app.state.settings = settings
    app.state.llm_client = llm_client
    app.state.orchestrator = orchestrator
    yield
    logger.info("Shutting down %s", settings.APP_NAME)


def create_app() -> FastAPI:
    app = FastAPI(
        title=settings.APP_NAME,
        version=settings.APP_VERSION,
        lifespan=lifespan,
    )

    app.add_middleware(
        CORSMiddleware,
        allow_origins=settings.CORS_ORIGINS,
        allow_credentials=False,
        allow_methods=["GET", "POST", "OPTIONS"],
        allow_headers=["*"],
    )

    app.include_router(api_v1_router)

    @app.get("/", tags=["meta"])
    async def root() -> dict[str, str]:
        return {"name": settings.APP_NAME, "version": settings.APP_VERSION}

    return app


app = create_app()


def start() -> None:  # pragma: no cover
    import uvicorn

    uvicorn.run(
        "src.main:app",
        host=settings.APP_HOST,
        port=settings.APP_PORT,
        reload=settings.RELOAD,
        log_level=settings.LOG_LEVEL.lower(),
    )


if __name__ == "__main__":  # pragma: no cover
    start()
