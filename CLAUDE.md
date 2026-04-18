# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this repo is

**RubberDuck Live** — a local-first IntelliJ coding copilot with three pillars: test generation, refactor suggestions, and a META "wellness" layer that detects *stuck* moments and asks a Socratic question instead of generating code. Three components talk over loopback (`127.0.0.1`); no outbound network by default.

```
plugin (Kotlin, IntelliJ Platform 2024.2+) ──HTTP/WS──▶ backend (FastAPI :8765) ──▶ Ollama :11434 / OpenAI
                                                              │
                                              WS broadcast ──▶ dashboard (Next.js :3000)
```

`docs/ARCHITECTURE.md` is the source of truth for schemas, ports, and the DTO contract — read it before changing cross-component shapes.

## Layout

| Path | What lives here |
| --- | --- |
| `plugin/src/main/kotlin/com/rubberduck/live/` | Kotlin plugin. Listeners, detector, intervention, actions, tool window, settings, SQLite log. |
| `backend/src/` | FastAPI app. `api/v1/endpoints/` has the 4 REST + 1 WS routes; `core/llm_client.py` is the provider dispatcher; `core/mock_fixtures.py` backs `DEMO_MOCK=1`. |
| `dashboard/` | Next.js 16 App Router. `hooks/useEventsSocket.ts` is the single WS client; `lib/metrics.ts` does all derivation. |
| `docs/` | `ARCHITECTURE.md` (blueprint), `DEMO_SCRIPT.md` (3-min demo), `PHASE{2,3,4,5}_NOTES.md` (implementation history & locked decisions). |
| `scripts/dev.sh`, `scripts/demo-preflight.sh` | Bash orchestration — see "Windows" below. |
| `scripts/kill_port.py` | Cross-platform port killer — used by `make stop` and the bash scripts. |

## Build & run

Prerequisites: **JDK 21**, **Python 3.13**, **Node 18+**, optionally **Ollama** with `qwen2.5-coder:7b`.

Standard Makefile targets (Git Bash on Windows, any POSIX shell elsewhere):

```bash
make install              # backend uv sync · dashboard npm install · gradle wrapper
make dev                  # backend (DEMO_MOCK=1) + dashboard in parallel
make dev-live             # real LLM — OpenAI by default; needs OPENAI_API_KEY in backend/.env
make dev-local            # real LLM pinned to Ollama (offline path)
make stop                 # kill anything on :8765 / :3000 (cross-platform)
make plugin-run           # open sandbox IDE with the plugin loaded
make plugin-build         # → plugin/build/distributions/*.zip
make check                # py_compile · tsc · gradle buildPlugin (all three subsystems)
```

Per-component, if you only want one (works from any shell on any OS):

```bash
cd backend && DEMO_MOCK=1 uv run uvicorn src.main:app --host 127.0.0.1 --port 8765 --reload
cd dashboard && npm run dev
cd plugin && ./gradlew runIde           # or .\gradlew.bat runIde from cmd/PowerShell
```

`DEMO_MOCK=1` is the lifeline for demos and for working without Ollama — it short-circuits the three AI endpoints to canned responses in `backend/src/core/mock_fixtures.py`. Use it by default during plugin work so Ollama cold-start doesn't slow the loop.

## Windows

The orchestration is cross-platform by design: `scripts/kill_port.py` replaces the Unix-only `lsof`, and the macOS `java_home` lookup in the Makefile is guarded by `uname` detection. Two paths:

1. **With Git Bash (recommended):** install Git for Windows (ships with Git Bash + `make`), then use the `make` targets above.
2. **From PowerShell or cmd.exe:** `make` isn't available; call the per-component commands directly. `plugin/gradlew.bat` works from any shell.

Set `JAVA_HOME` to your JDK 21 install before running plugin targets — standard practice on Windows and Linux.

## Testing

**There is no test suite yet** in any component. Smoke checks that do exist:

```bash
make check                # cross-subsystem: py_compile + tsc --noEmit + gradle buildPlugin
cd plugin && ./gradlew verifyPlugin   # JetBrains plugin-verifier (slow; downloads an IDE)
```

When adding plugin tests use `BasePlatformTestCase` from the IntelliJ test framework (already declared in `build.gradle.kts` via `TestFrameworkType.Platform`); backend tests should use `pytest` + `httpx.AsyncClient(app=...)`. If writing LLM-dependent tests, gate them on `DEMO_MOCK=1` or stub `LlmClient`.

## Event model — the shared contract

Plugin, backend, and dashboard all speak `DeveloperEvent`:

- Kotlin: `plugin/src/main/kotlin/com/rubberduck/live/model/DeveloperEvent.kt`
- TypeScript: `dashboard/lib/types.ts`
- Python: `backend/src/models/` (mirrors the JSON shape)

Timestamps are **epoch-millis ints**, not ISO strings — locked decision in `docs/ARCHITECTURE.md` §3. The WS envelope is `{type, timestamp, payload}` where `type ∈ {event, ack, broadcast, ping, pong, error}`. Ring buffer is in-memory on the backend (`maxlen=500` per session); the plugin's SQLite log at `$IDE_CONFIG_DIR/rubberduck-live/events.db` is the durable store with 30-day retention.

## Stuck detection

Five rules run locally in the plugin on a 5-minute sliding window, each rate-limited to once per 60s: `undo_burst`, `repeated_failing_test`, `long_idle_on_error`, `rapid_file_switching`, `edit_thrash`. The backend **never infers stuck** — it only reacts to `STUCK_DETECTED` events the plugin sends. Rule thresholds and confidence scores are in `docs/ARCHITECTURE.md` §5 and enforced in `plugin/src/main/kotlin/com/rubberduck/live/detector/StuckDetector.kt`.

## LLM provider

Dispatch via `backend/src/core/llm_client.py` — an async ABC (`BaseLLMClient.complete()` + `available()`) with three concrete impls. Provider is picked from `LLM_PROVIDER`:

1. **`openai`** (default) — vanilla OpenAI `gpt-4o-mini`; requires `OPENAI_API_KEY`. The hackathon default because organisers supply a key.
2. **`openai_compatible`** — any `OPENAI_BASE_URL` + `OPENAI_API_KEY` (LM Studio, vLLM, Groq, Together).
3. **`ollama`** — local `http://127.0.0.1:11434`, model `qwen2.5-coder:7b`. The fully-offline path; use `make dev-local` (pins `LLM_PROVIDER=ollama` for the run).

`LLM_FALLBACK=true` (default) walks `LLM_FALLBACK_CHAIN` (`openai → openai_compatible → ollama`) if the primary fails. Env lives in `backend/.env` — see `backend/.env.example`.

For Makefile-driven runs: `make dev-live` uses the configured default (OpenAI); `make dev-local` forces Ollama for that invocation only.

## Privacy invariants (don't break these)

- **Path redaction** — absolute paths never leave the IDE. Any new DTO carrying a path must flow through `plugin/src/main/kotlin/com/rubberduck/live/util/PathRedactor.kt`. Adding a new field is the #1 way a future change can break this.
- **Loopback only** — backend binds `127.0.0.1`; CORS allows only `http://localhost:3000` and `http://127.0.0.1:3000`. Don't widen this without a user-facing consent flow.
- **Strict privacy mode** — `RubberDuckSettings.strictPrivacy` disables outbound code-sending actions. Any new action that sends code must check this flag.
- **No telemetry** — no analytics SDKs, anywhere.

## Phase notes

`docs/PHASE{2,3,4,5}_NOTES.md` record *locked decisions* per phase. Consult the relevant phase before changing a cross-component contract — a field or port that looks arbitrary probably has a phase note explaining why it's that value.
