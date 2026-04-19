# RubberDuck Live — Backend

Local FastAPI backend for the **RubberDuck Live** IntelliJ plugin and Next.js
dashboard. Serves four HTTP endpoints plus one WebSocket, binds loopback only
(`127.0.0.1:8765`). Default LLM is **OpenAI `gpt-4o-mini`** (just set
`OPENAI_API_KEY`); a local [Ollama](https://ollama.ai) setup is fully
supported as the offline / privacy-first fallback.

See [`docs/ARCHITECTURE.md`](../docs/ARCHITECTURE.md) for the full contract.

## What it provides

| Endpoint | Purpose |
| --- | --- |
| `GET  /api/v1/health`             | Liveness + LLM probe + `orchestrator_available` |
| `POST /api/v1/chat`               | Unified streaming chat (SSE) — three modes; see below |
| `POST /api/v1/generate-tests`     | LLM-generated unit tests (pytest / JUnit5 / Jest) |
| `POST /api/v1/suggest-refactor`   | Heuristic smell detection + LLM rewrite + unified diff |
| `POST /api/v1/socratic-question`  | One Socratic question, ≤ 25 words |
| `WS   /api/v1/events`             | Plugin pushes events; dashboards subscribe to the stream |

### `/chat` modes

The primary chat endpoint branches on `ChatRequest.mode`:

| `mode`  | Path taken                                    | Cost          | Best for                                        |
| ------- | --------------------------------------------- | ------------- | ----------------------------------------------- |
| `ask`   | Plain streaming chat — tokens forwarded raw.  | 1 LLM call    | Q&A, explanations, "what does this do?"         |
| `edit`  | Envelope mode — LLM returns `{answer,edits}` JSON; server parses + emits `code_edit` events. | 1 LLM call    | Single-file or one-line fixes, quick refactors. |
| `agent` | **DeepAgents orchestrator** — code-writer → test-writer → reviewer chain, each with filesystem + shell tools. Edits emit inline. | ~20 LLM calls | Multi-file scaffolds, generate-code-with-tests. |

When `mode` is omitted, legacy clients sending `allow_edits=true` are
interpreted as `mode="edit"`; otherwise `mode="ask"`.

### ⚠️ Relaxed privacy posture on `/chat` with `mode="agent"`

Agent mode deliberately relaxes four of the root `CLAUDE.md` privacy
invariants. In plain English: when you POST to `/chat` with `mode="agent"`
against a live LLM, the backend gains a local RCE surface — sub-agents
read/write arbitrary files under `ORCHESTRATOR_WORK_DIR`, shell out freely,
and search DuckDuckGo. Read
[`docs/PRIVACY_NOTES.md`](docs/PRIVACY_NOTES.md) before enabling this on a
machine you care about. Disable with `ORCHESTRATOR_ENABLED=false` (ask/edit
modes still work).

## Install

Requires Python ≥ 3.13.

```bash
cd backend
# Either uv (recommended):
uv sync
# …or plain pip:
pip install -e .
```

## Run

```bash
./run.sh
# or, equivalently:
uvicorn src.main:app --host 127.0.0.1 --port 8765 --reload
```

### Demo / mock mode

```bash
DEMO_MOCK=1 ./run.sh
```

All three AI endpoints return pre-canned responses from
[`src/core/mock_fixtures.py`](src/core/mock_fixtures.py) — no LLM required. The
fixtures are the exact shapes the demo script in
[`docs/DEMO_SCRIPT.md`](../docs/DEMO_SCRIPT.md) relies on (fizzbuzz tests, the
`analyze` refactor, the `undo_burst` Socratic question).

## Environment variables

Copy [`.env.example`](.env.example) to `.env` and edit. Highlights:

| Var | Default | Notes |
| --- | --- | --- |
| `APP_HOST`         | `127.0.0.1`      | Always loopback. |
| `APP_PORT`         | `8765`           | Architecture §10. |
| `DEMO_MOCK`        | `0`              | Set to `1` for canned demo responses. |
| `LLM_PROVIDER`     | `openai`         | `openai` \| `openai_compatible` \| `ollama` |
| `LLM_FALLBACK`     | `true`           | If primary fails, walk `LLM_FALLBACK_CHAIN` in order. |
| `LLM_FALLBACK_CHAIN` | `["openai","openai_compatible","ollama"]` | JSON list of providers to try after the primary. |
| `OPENAI_API_KEY`   | *(unset)*        | **Required for the default OpenAI provider** (and for OpenAI-compatible). |
| `OPENAI_MODEL`     | `gpt-4o-mini`    | Override for e.g. `gpt-4o`. |
| `OPENAI_BASE_URL`  | *(unset)*        | Set to use an OpenAI-compatible server (LM Studio, vLLM, Groq, Together). |
| `OLLAMA_BASE_URL`  | `http://127.0.0.1:11434` | Used when provider/fallback is `ollama`. |
| `OLLAMA_MODEL`     | `qwen2.5-coder:7b`       | |
| `RUBBERDUCK_TOKEN` | *(unset)*        | If set, clients must send `X-RubberDuck-Token`. |

## Quick curl examples

```bash
# Health
curl -s http://127.0.0.1:8765/api/v1/health | jq

# Generate tests (mock mode)
curl -s -X POST http://127.0.0.1:8765/api/v1/generate-tests \
  -H 'Content-Type: application/json' \
  -d '{
        "language": "python",
        "code": "def fizzbuzz(n):\n    ...",
        "function_name": "fizzbuzz",
        "file_path": "demo/fizz.py"
      }' | jq

# Suggest refactor
curl -s -X POST http://127.0.0.1:8765/api/v1/suggest-refactor \
  -H 'Content-Type: application/json' \
  -d '{"language":"python","code":"def analyze(xs):\n    ..."}' | jq

# Socratic question
curl -s -X POST http://127.0.0.1:8765/api/v1/socratic-question \
  -H 'Content-Type: application/json' \
  -d '{"stuck_reason":"undo_burst","current_code":"def rolling_sum(...):","recent_events":[]}' | jq
```

## WebSocket smoke test

With the backend running:

```bash
python examples/websocket_client.py
```

## Privacy

- Server binds to `127.0.0.1` only; CORS allows only `http://localhost:3000` and
  `http://127.0.0.1:3000`.
- No database; event ring buffer is per-process, in-memory, `maxlen=500` per
  session.
- The plugin is the durable store.
