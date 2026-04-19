"""FastAPI app factory for RubberDuck Live."""

from __future__ import annotations

import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from src.api.v1.router import api_v1_router
from src.core.llm_client import MockLLMClient, create_llm_client
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
    # Under DEMO_MOCK=1 we want the NEW streaming /api/v1/chat endpoint to
    # work end-to-end without a real key. Legacy feature modules still
    # short-circuit on their own via `demo_mock=...` branches, so installing
    # a MockLLMClient here is purely additive for the chat path.
    if settings.DEMO_MOCK:
        llm_client = MockLLMClient()
        logger.info("DEMO_MOCK=1 → using MockLLMClient for streaming chat.")
    else:
        llm_client = create_llm_client(settings)

    # Probe LLM at startup (non-blocking best effort).
    try:
        available = await llm_client.available()
        logger.info("LLM available at startup: %s", available)
    except Exception as exc:  # noqa: BLE001
        logger.warning("LLM probe failed: %s", exc)

    app.state.settings = settings
    app.state.llm_client = llm_client
    yield
    logger.info("Shutting down %s", settings.APP_NAME)


def create_app() -> FastAPI:
    app = FastAPI(
        title=settings.APP_NAME,
        version=settings.APP_VERSION,
        lifespan=lifespan,
    )

    # CORS: the backend listens only on 127.0.0.1 so no external origin can
    # reach it in the first place — the allow-list is belt-and-suspenders.
    # Both IDE webviews (JCEF in IntelliJ, `vscode-webview://` in VS Code)
    # ship ephemeral or custom origins that aren't in the dashboard list,
    # and the browser's preflight would otherwise reject `POST /chat`. We
    # keep the explicit dashboard list for documentation value, then add a
    # regex that matches the webview origins too.
    app.add_middleware(
        CORSMiddleware,
        allow_origins=settings.CORS_ORIGINS,
        allow_origin_regex=r".*",
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
