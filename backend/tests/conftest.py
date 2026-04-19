"""Shared pytest fixtures.

`DEMO_MOCK` is read at module import (src.settings builds a singleton Settings),
so we set the env var before any test imports src.main.
"""

from __future__ import annotations

import os

# MUST run before any `from src...` import. Setting here in conftest ensures
# pytest collection picks it up before test modules are imported.
os.environ.setdefault("DEMO_MOCK", "true")
# Force LLM_PROVIDER to something that doesn't require a key; DEMO_MOCK short-
# circuits the actual network call so the provider choice is cosmetic here.
os.environ.setdefault("LLM_PROVIDER", "openai")
os.environ.setdefault("OPENAI_API_KEY", "sk-test-not-used-under-demo-mock")

# Silence LangSmith: LangChain background callbacks otherwise try to phone
# home to api.smith.langchain.com, which (a) we don't want and (b) will
# timeout under pytest even in DEMO_MOCK mode when agents are constructed.
os.environ.setdefault("LANGCHAIN_TRACING_V2", "false")
os.environ.setdefault("LANGCHAIN_CALLBACKS_BACKGROUND", "false")
os.environ.setdefault("LANGSMITH_TRACING", "false")
