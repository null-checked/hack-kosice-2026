# Phase 2 Notes

Decisions I made while implementing the backend that weren't spelled out
explicitly in the architecture doc. Future phases can revisit if needed.

## Pragmatic choices

- **WS URL** lives under the versioned prefix: `ws://127.0.0.1:8765/api/v1/events`
  (matching the HTTP namespace). The architecture doc mentions both `/events`
  and the `/api/v1` prefix; I picked the prefixed path for consistency so the
  plugin and dashboard can share a single base URL.
- **WS handshake**: the architecture doc describes "first message from client is
  `hello`" but doesn't lock a type. I used `type:"hello"` inside the same
  envelope and added `hello` as a `WSMessageType` enum value. Server replies
  with an `ack` envelope containing `{role, session_id}`.
- **Dashboard snapshot payload shape**: the doc says the first message a
  dashboard receives is a `broadcast` with the snapshot. I used
  `{"kind":"snapshot","session_id":"...","events":[...]}` for the snapshot and
  `{"kind":"event","event":{...}}` for live fan-out so the dashboard can
  disambiguate easily.
- **Dashboard wildcard subscription**: a dashboard can pass `session_id:"*"` in
  its hello to subscribe to every session (useful for the demo, where the
  dashboard shouldn't need to know the IDE's session UUID upfront).
- **Heartbeat**: backend pings dashboards every 20 s. For plugins, we rely on
  their incoming traffic; if no frame arrives within 45 s the server closes the
  connection. The plugin side is expected to send `ping` of its own per §4.
- **Auth scaffold**: `RUBBERDUCK_TOKEN` is wired as a FastAPI dependency (see
  [`backend/src/api/v1/deps.py`](../backend/src/api/v1/deps.py:1)). When unset
  (the default), it's a no-op. The WS endpoint intentionally skips the check —
  the token flow is HTTP-only for the MVP.
- **Framework detection** in `test_generator.py` is Python-first: everything
  else falls back to the framework the LLM picks or a best-guess default.
  Per the prompt, only Python needs to be perfect for the MVP.
- **LLM JSON robustness**: I implemented a balanced-brace JSON extractor
  (`extract_first_json`) instead of the 3× retry loop hinted at in §6. A single
  good parse is plenty in practice; if parsing fails, we fall back to lenient
  field defaults so the endpoint never 500s on a noisy LLM response.
- **Mock diff** is real: `mock_fixtures.py` computes its `diff_unified` live
  via `difflib` so the output is guaranteed consistent with the two canned
  code strings.
- **OPENAI_API_KEY** is now optional (was required in the old `settings.py`).
  Users running pure-Ollama won't need it.

## Known gaps for Phase 3 / 4

- **Retention / eviction** for the per-session ring buffer is implicit via
  `deque(maxlen=500)`. Sessions that go silent keep their buffer around until
  the process exits. Phase 3 should not assume otherwise.
- **Redaction**: the architecture (§11) expects the plugin to strip absolute
  paths before sending. The backend does NOT re-redact.
- **Rate-limiting** on HTTP endpoints is not implemented. The plugin is the
  natural rate limiter; add a token-bucket if/when a cloud deployment happens.
- **WebSocket back-pressure**: if a dashboard subscriber's queue overflows
  (>1024 pending events) we drop frames with a warning rather than disconnect.
  Phase 4 should not rely on guaranteed delivery of every event — the snapshot
  replay on reconnect is authoritative.
- **Smell detection** is intentionally simple (line count, control-keyword
  count, Python AST nesting depth, magic-number regex). The LLM carries the
  real weight. If we add more languages, port the AST nesting check to tree-
  sitter so Kotlin/TS get the same treatment.
- **Graceful shutdown**: live WS connections are closed abruptly when the
  process exits. Acceptable for MVP; switch to `ASGI lifespan`-aware shutdown
  if we ever need zero-drop deploys.
