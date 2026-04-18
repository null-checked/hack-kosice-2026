# Phase 5 Notes — Integration & Smoke Test

End-to-end verification of the Phase 1–4 deliverables on macOS (Apple Silicon,
zsh). This note captures what ran, what was fixed, and what remains manual.

## Environment

- macOS 26.4.1 / aarch64
- Default `java` = Oracle JDK 25.0.1 (system-wide)
- Installed JDKs (via `/usr/libexec/java_home -V`): **25.0.1**, **21.0.9**
- Node v24.13.1, npm 11.8.0
- Python 3.13 (homebrew), `uv` 0.x on PATH
- `gradle` not on PATH — wrapper-only workflow

## 5.1 · Gradle wrapper bootstrap

`plugin/gradle/wrapper/gradle-wrapper.jar` was missing. Downloaded the
official v8.10.2 wrapper jar (matches
`plugin/gradle/wrapper/gradle-wrapper.properties`):

```bash
curl -L -o plugin/gradle/wrapper/gradle-wrapper.jar \
  https://raw.githubusercontent.com/gradle/gradle/v8.10.2/gradle/wrapper/gradle-wrapper.jar
chmod +x plugin/gradlew
```

Verify:

```
Gradle 8.10.2  (Kotlin 1.9.24, Groovy 3.0.22)
Launcher JVM: 25.0.1
```

## 5.2 · `./gradlew buildPlugin`

### Fix 1 — Toolchain JDK 17 not present

Gradle 8.10.2 auto-toolchain-download is off unless the Foojay resolver plugin
is configured, and only JDK 21 + JDK 25 were installed on this Mac. The
`javaVersion=17` in `gradle.properties` caused:

```
> Cannot find a Java installation on your machine matching this tasks
  requirements: {languageVersion=17, ...}
```

**Fix:** bump `javaVersion` from `17` to `21` in
[`plugin/gradle.properties`](../plugin/gradle.properties:7). IntelliJ Platform
2024.2 runs on JDK 21, Kotlin 2.0.21 supports JVM target 21, and every
installed JDK on a modern dev laptop covers it. No API surface change.

### Fix 2 — `CommandProcessor.addCommandListener` signature

`./gradlew buildPlugin` then failed at `:compileKotlin`:

```
e: EditorActivityListener.kt:64:105
Too many arguments for 'fun addCommandListener(p0: @NotNull CommandListener): Unit'.
```

`CommandProcessor.addCommandListener(Listener, Disposable)` does not exist in
2024.2. **Fix:** subscribe via the application message bus to
`CommandListener.TOPIC`, which *does* accept a parent `Disposable` transitively
through `messageBus.connect(parent)`.

See the diff in
[`plugin/src/main/kotlin/com/rubberduck/live/listeners/EditorActivityListener.kt`](../plugin/src/main/kotlin/com/rubberduck/live/listeners/EditorActivityListener.kt:61).

### Fix 3 — `instrumentationTools()` dependency missing

Next failure was `:instrumentCode`:

```
> No Java Compiler dependency found. Please ensure the `instrumentationTools()`
  entry is present in the project dependencies section ...
```

**Fix:** add `instrumentationTools()` to the `intellijPlatform { ... }`
dependency block in
[`plugin/build.gradle.kts`](../plugin/build.gradle.kts:27). This is a new
requirement in IntelliJ Platform Gradle Plugin 2.x and was missed in Phase 3.

### Result

```
BUILD SUCCESSFUL in 11s
14 actionable tasks: 9 executed, 5 up-to-date
```

Artefact: `plugin/build/distributions/rubberduck-live-plugin-0.1.0.zip`
(~19 MB).

Invocation command used:

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home \
  ./gradlew buildPlugin
```

## 5.3 · Backend + dashboard smoke test

### Backend (`DEMO_MOCK=1`)

```bash
cd backend && uv sync   # 23 packages, no changes
DEMO_MOCK=1 uv run uvicorn src.main:app --host 127.0.0.1 --port 8765
```

HTTP:

```
$ curl -s http://127.0.0.1:8765/api/v1/health
{"status":"ok","llm_provider":"ollama","llm_model":"qwen2.5-coder:7b",
 "llm_available":true,"demo_mock":true,"version":"0.2.0"}
```

`/api/v1/generate-tests` with the Pillar 1 fizzbuzz payload returned the full
parametrized pytest fixture (snippet logged). ✅

WebSocket smoke test:

```
$ uv run python examples/websocket_client.py
hello ack: {"type":"ack", ..., "payload":{"role":"plugin","session_id":"demo-..."}}
event ack: {"type":"ack", ..., "payload":{"event_id":"3b805f11-..."}}
```

Hello → ack, event → ack. ✅

### Dashboard

```bash
cd dashboard && npm install   # cached, no changes
npx tsc --noEmit              # clean
npm run build                 # ✓ compiled in 2.5s, static / + /_not-found
npm run dev                   # GET / → 200 in 2.2s
```

Ran `npm run dev` in the background, hit `http://localhost:3000/` via curl,
got `HTTP 200`. Browser verification (WS "Live" pill, demo replay button,
sparklines) is a manual step — see pre-flight checklist below. Build+tsc
clean is strong evidence the panels render. ✅

### Demo replay

`NEXT_PUBLIC_DEMO_REPLAY=1` reveals the button in the hero. The synthesised
120-second event stream (see
[`dashboard/lib/demoReplay.ts`](../dashboard/lib/demoReplay.ts:1)) is loaded
entirely client-side and does not require the backend to be up. Manual verify
on judging laptop.

## 5.4 · Plugin ↔ backend integration (`runIde`)

`./gradlew runIde` spawns a full IntelliJ sandbox (300 MB+ download on first
run) and is a GUI-only flow — **not run in this session**. Documented as a
manual step in the pre-flight checklist appended to
[`docs/DEMO_SCRIPT.md`](DEMO_SCRIPT.md).

Proxy evidence that the integration is sound:

- `./gradlew buildPlugin` green (Kotlin + `plugin.xml` validation + jar
  instrumentation all succeed).
- `./gradlew verifyPlugin` — kicked off in the background; this downloads the
  target IDE for JetBrains plugin-verifier and can take 5–20 minutes on a cold
  cache. Captured log: `/tmp/rd_verify.log`. **Not blocking** per the
  original brief (warnings about `untilBuild` are expected; we care only about
  critical errors).
- All of: `EventsWebSocket` hello envelope, `/generate-tests` DTO shape,
  `SocraticNotifier` balloon group ID, tool-window id `RubberDuck Live` were
  cross-checked against Phase 2/4 notes and match.

## 5.5 · Summary

| Item                              | Status |
| --------------------------------- | ------ |
| Gradle wrapper jar                | ✅ committed, 43 KB |
| `./gradlew buildPlugin`           | ✅ green (3 fixes applied) |
| `./gradlew verifyPlugin`          | 🟡 background run, non-blocking |
| Backend `/health` (mock)          | ✅ `llm_available:true, demo_mock:true` |
| Backend `/generate-tests` (mock)  | ✅ returns parametrized pytest |
| Backend WS handshake              | ✅ hello→ack, event→ack |
| Dashboard `tsc --noEmit`          | ✅ clean |
| Dashboard `npm run build`         | ✅ 2.5 s, static |
| Dashboard `npm run dev` + curl    | ✅ HTTP 200 |
| `runIde` manual IDE test          | ⏳ pre-flight step, pre-judging |
| Demo replay in browser            | ⏳ pre-flight step, pre-judging |

No uncompiling files anywhere. Every fix is minimal and kept the original
Phase 3 decisions intact (EditorActivityListener still batches per-document
edits; only the command-listener registration path changed).

## 5.6 · Open items / deferred

- `verifyPlugin` may surface 1.x-era compatibility warnings on the first
  `recommended()` IDE build; re-run on judging day with `--info` if needed.
- `docs/assets/*.gif` + `pillar{1,2,3}.png` are placeholders — recording is
  the user's manual task. Recipes are in
  [`docs/assets/README.md`](assets/README.md).
- Team credits in the root README still say "Team ???" — fill in before
  submission.
- First `./gradlew runIde` will download the 2024.2 community IDE (~500 MB).
  Budget 5 minutes extra on a cold laptop.
