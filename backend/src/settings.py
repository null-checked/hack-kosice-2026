"""Pydantic settings loaded from environment / `.env`."""

from __future__ import annotations

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """Runtime configuration for the RubberDuck Live backend."""

    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
        case_sensitive=False,
    )

    # Server
    APP_NAME: str = "RubberDuck Live"
    APP_VERSION: str = "0.2.0"
    APP_HOST: str = "127.0.0.1"
    APP_PORT: int = 8765
    RELOAD: bool = False
    LOG_LEVEL: str = "INFO"

    # CORS — dashboard only, loopback only.
    CORS_ORIGINS: list[str] = [
        "http://localhost:3000",
        "http://127.0.0.1:3000",
    ]

    # Demo mock short-circuit.
    DEMO_MOCK: bool = False

    # LLM provider
    # Default is OpenAI because the hackathon organisers supply an API key.
    # Swap to "ollama" for a fully offline path, or "openai_compatible" for
    # self-hosted OpenAI-API-shaped endpoints (LM Studio / vLLM / Groq / …).
    LLM_PROVIDER: str = "openai"  # openai | openai_compatible | ollama
    LLM_FALLBACK: bool = True
    # Ordered fallback chain tried after the primary fails. The primary is
    # always attempted first regardless of its position in this list.
    LLM_FALLBACK_CHAIN: list[str] = ["openai", "openai_compatible", "ollama"]
    LLM_TIMEOUT_SECONDS: float = 60.0

    # Ollama
    OLLAMA_BASE_URL: str = "http://127.0.0.1:11434"
    OLLAMA_MODEL: str = "qwen2.5-coder:7b"

    # OpenAI / OpenAI-compatible
    OPENAI_BASE_URL: str | None = None
    OPENAI_API_KEY: str | None = None
    OPENAI_MODEL: str = "gpt-4o-mini"

    # Optional auth scaffold (no-op when unset).
    RUBBERDUCK_TOKEN: str | None = None

    # Ring buffer size per session.
    EVENT_BUFFER_SIZE: int = 500

    # ---- Multi-agent orchestrator (for /chat with mode="agent") --------
    #
    # PRIVACY WARNING: enabling the orchestrator deliberately relaxes four
    # of the root CLAUDE.md invariants: outbound DuckDuckGo search,
    # unsandboxed filesystem read+write of the work dir, unsandboxed shell
    # execution, and un-redacted paths on the orchestrator request. Read
    # `backend/docs/PRIVACY_NOTES.md` BEFORE enabling on a machine you
    # care about. Disable with `ORCHESTRATOR_ENABLED=false` — `/chat` with
    # `mode="agent"` then returns an error frame, the other two modes
    # continue working normally.
    #
    # The orchestrator only builds on OpenAI / OpenAI-compatible providers;
    # under Ollama it falls through to an error frame even with
    # ORCHESTRATOR_ENABLED=true.

    # Master switch. Set to "false" to disable the orchestrator entirely.
    ORCHESTRATOR_ENABLED: bool = True
    # Optional sub-agent model override. Empty = sub-agents share the
    # orchestrator model (`OPENAI_MODEL`). Set to something cheaper (e.g.
    # `gpt-4o-mini`) to split costs while keeping the orchestrator big.
    ORCHESTRATOR_SUBAGENT_MODEL: str | None = None
    # Root directory the shared `LocalShellBackend` operates in. Empty =
    # the backend process's current working directory. Sub-agents will
    # read/write/edit files under this tree AND shell out to any command.
    ORCHESTRATOR_WORK_DIR: str | None = None
    # Per-command shell timeout (seconds). Commands exceeding this die.
    ORCHESTRATOR_SHELL_TIMEOUT: int = 120
    # Maximum captured stdout+stderr bytes returned to the agent per
    # `execute` call. Prevents a runaway command from blowing out context.
    ORCHESTRATOR_SHELL_MAX_OUTPUT_BYTES: int = 100_000
    # DuckDuckGo internet search tool availability. Set to "false" to
    # strip the `internet_search` tool from all agents (recommended on
    # air-gapped machines or when you don't want DuckDuckGo to see your
    # queries).
    ORCHESTRATOR_WEB_SEARCH_ENABLED: bool = True

    @property
    def active_llm_model(self) -> str:
        """Return the model name used by the currently selected provider."""
        if self.LLM_PROVIDER == "ollama":
            return self.OLLAMA_MODEL
        return self.OPENAI_MODEL


settings = Settings()
