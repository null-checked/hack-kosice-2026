# 🦆 RubberDuck Live — one-command orchestration
# Every target is idempotent and safe to run from the repo root.

SHELL := /bin/bash
ROOT  := $(shell pwd)

# Plugin Gradle build requires JDK 21 (IntelliJ Platform 2024.2). On macOS we
# auto-locate it via /usr/libexec/java_home so system-wide Java (e.g. 25) stays
# untouched. On Linux and Windows, the user is expected to set JAVA_HOME
# themselves (standard practice).
ifeq ($(shell uname 2>/dev/null),Darwin)
PLUGIN_JAVA_HOME := $(shell /usr/libexec/java_home -v 21 2>/dev/null)
ifneq ($(PLUGIN_JAVA_HOME),)
export JAVA_HOME := $(PLUGIN_JAVA_HOME)
endif
endif

# Colours (fallback to no-op on dumb terminals).
C_RESET := $(shell tput -T xterm sgr0 2>/dev/null || echo "")
C_BOLD  := $(shell tput -T xterm bold 2>/dev/null || echo "")
C_DIM   := $(shell tput -T xterm dim  2>/dev/null || echo "")
C_CYAN  := $(shell tput -T xterm setaf 6 2>/dev/null || echo "")
C_GREEN := $(shell tput -T xterm setaf 2 2>/dev/null || echo "")
C_YELL  := $(shell tput -T xterm setaf 3 2>/dev/null || echo "")
C_RED   := $(shell tput -T xterm setaf 1 2>/dev/null || echo "")

.DEFAULT_GOAL := help
.PHONY: help install dev dev-live dev-local plugin-build plugin-run plugin-verify \
        demo demo-preflight check check-backend check-dashboard check-plugin \
        clean stop

## help — show this message
help:
	@printf "$(C_BOLD)🦆 RubberDuck Live — Makefile$(C_RESET)\n"
	@printf "\n"
	@printf "$(C_CYAN)Usage:$(C_RESET) make <target>\n\n"
	@printf "$(C_BOLD)Setup$(C_RESET)\n"
	@printf "  $(C_GREEN)install$(C_RESET)         install backend + dashboard deps + gradle wrapper\n"
	@printf "\n$(C_BOLD)Run$(C_RESET)\n"
	@printf "  $(C_GREEN)dev$(C_RESET)             backend (mock) + dashboard in parallel\n"
	@printf "  $(C_GREEN)dev-live$(C_RESET)        DEMO_MOCK=0, real LLM — OpenAI by default (needs OPENAI_API_KEY)\n"
	@printf "  $(C_GREEN)dev-local$(C_RESET)       DEMO_MOCK=0, LLM_PROVIDER=ollama — fully offline path (needs Ollama)\n"
	@printf "  $(C_GREEN)demo$(C_RESET)            alias for dev — the judging flow\n"
	@printf "  $(C_GREEN)demo-preflight$(C_RESET)  run the 10-step pre-demo checklist\n"
	@printf "  $(C_GREEN)stop$(C_RESET)            kill anything we spawned on :3000 / :8765\n"
	@printf "\n$(C_BOLD)Plugin$(C_RESET)\n"
	@printf "  $(C_GREEN)plugin-build$(C_RESET)    produce plugin/build/distributions/*.zip\n"
	@printf "  $(C_GREEN)plugin-run$(C_RESET)      launch IntelliJ sandbox with the plugin loaded\n"
	@printf "  $(C_GREEN)plugin-verify$(C_RESET)   run JetBrains plugin-verifier (slow — downloads IDE)\n"
	@printf "\n$(C_BOLD)CI$(C_RESET)\n"
	@printf "  $(C_GREEN)check$(C_RESET)           full cross-subsystem verification\n"
	@printf "  $(C_GREEN)clean$(C_RESET)           wipe build artefacts (prompts first)\n"
	@printf "\n$(C_DIM)Ports: backend=8765 · dashboard=3000 · Ollama=11434$(C_RESET)\n"

## install — install all deps
install:
	@printf "$(C_BOLD)→ backend deps$(C_RESET)\n"
	@cd backend && (command -v uv >/dev/null 2>&1 && uv sync || python3 -m pip install -e .)
	@printf "$(C_BOLD)→ dashboard deps$(C_RESET)\n"
	@cd dashboard && (test -d node_modules || npm install)
	@printf "$(C_BOLD)→ plugin gradle wrapper$(C_RESET)\n"
	@if [ ! -f plugin/gradle/wrapper/gradle-wrapper.jar ]; then \
	  curl -L -sS -o plugin/gradle/wrapper/gradle-wrapper.jar \
	    https://raw.githubusercontent.com/gradle/gradle/v8.10.2/gradle/wrapper/gradle-wrapper.jar ; \
	  chmod +x plugin/gradlew ; \
	fi
	@printf "$(C_GREEN)✓ install complete$(C_RESET)\n"

## dev — parallel backend (mock) + dashboard
dev:
	@bash scripts/dev.sh mock

## dev-live — real LLM; uses the backend's default provider (OpenAI by
## default — set OPENAI_API_KEY in backend/.env first). For the local-only
## Ollama path use `make dev-local` instead.
dev-live:
	@bash scripts/dev.sh live

## dev-local — real LLM pinned to local Ollama (requires `ollama` on :11434
## and `qwen2.5-coder:7b` pulled). Overrides LLM_PROVIDER for this run only.
dev-local:
	@LLM_PROVIDER=ollama bash scripts/dev.sh live

## demo — judging-day alias
demo: dev

## demo-preflight — pre-demo checklist
demo-preflight:
	@bash scripts/demo-preflight.sh

## plugin-build — assemble zip
plugin-build:
	@cd plugin && ./gradlew buildPlugin
	@printf "$(C_GREEN)✓ $(C_RESET)$$(ls plugin/build/distributions/*.zip 2>/dev/null | head -1)\n"

## plugin-run — IntelliJ sandbox
plugin-run:
	@cd plugin && ./gradlew runIde

## plugin-verify — plugin-verifier
plugin-verify:
	@cd plugin && ./gradlew verifyPlugin

## check — full cross-subsystem verification
check: check-backend check-dashboard check-plugin
	@printf "\n$(C_GREEN)✓ check: ALL PASSED$(C_RESET)\n"

check-backend:
	@printf "$(C_BOLD)[1/3] backend · py_compile + pytest$(C_RESET)\n"
	@python3 -m py_compile $$(find backend/src -name '*.py') && printf "$(C_GREEN)    ✓ backend py_compile$(C_RESET)\n"
	@cd backend && uv run --group dev pytest -q && printf "$(C_GREEN)    ✓ backend pytest$(C_RESET)\n"

check-dashboard:
	@printf "$(C_BOLD)[2/3] dashboard · tsc + next build$(C_RESET)\n"
	@cd dashboard && npx --no-install tsc --noEmit && npm run build >/dev/null 2>&1 && printf "$(C_GREEN)    ✓ dashboard clean$(C_RESET)\n"

check-plugin:
	@printf "$(C_BOLD)[3/3] plugin · gradle buildPlugin + test$(C_RESET)\n"
	@cd plugin && ./gradlew -q buildPlugin test && printf "$(C_GREEN)    ✓ plugin built + tests$(C_RESET)\n"

# Derive ports from backend/.env so one place controls everything. Fall back
# to 8765/3000 if the file isn't present or the vars aren't set.
BACKEND_PORT := $(shell grep -E '^APP_PORT=' backend/.env 2>/dev/null | tail -1 | cut -d= -f2 | tr -d ' ')
BACKEND_PORT := $(if $(BACKEND_PORT),$(BACKEND_PORT),8765)
DASHBOARD_PORT := $(shell grep -E '^DASHBOARD_PORT=' backend/.env 2>/dev/null | tail -1 | cut -d= -f2 | tr -d ' ')
DASHBOARD_PORT := $(if $(DASHBOARD_PORT),$(DASHBOARD_PORT),3000)

## stop — free demo ports (cross-platform via scripts/kill_port.py)
stop:
	@python scripts/kill_port.py $(BACKEND_PORT) $(DASHBOARD_PORT)
	@printf "$(C_GREEN)✓ stopped$(BACKEND_PORT)/$(DASHBOARD_PORT)$(C_RESET)\n"

## clean — wipe build artefacts
clean:
	@printf "$(C_YELL)This will remove: dashboard/{.next,node_modules}, plugin/build, plugin/.gradle, backend/.venv$(C_RESET)\n"
	@read -r -p "Proceed? [y/N] " ans && [ "$$ans" = "y" ] || [ "$$ans" = "Y" ]
	@rm -rf dashboard/.next dashboard/node_modules
	@rm -rf plugin/build plugin/.gradle plugin/.intellijPlatform plugin/.kotlin
	@rm -rf backend/.venv backend/__pycache__ backend/src/**/__pycache__
	@printf "$(C_GREEN)✓ clean$(C_RESET)\n"
