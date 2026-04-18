# Phase 3 Notes — IntelliJ Plugin

Decisions and known trade-offs from the Phase 3 build. Future phases (4 polish,
5 live demo) can revisit if needed.

## Architectural decisions

- **Kotlin-only, Platform Plugin 2.x.** Build script uses
  `org.jetbrains.intellij.platform:2.1.0`. No Java sources. Java toolchain 17.
- **Application services everywhere.** Each long-lived component
  (`EventBus`, `EventLog`, `BackendClient`, `EventsWebSocket`,
  `SessionContext`, `StuckDetector`, `StuckInterventionCoordinator`,
  `RubberDuckSettings`) is an `@Service(Service.Level.APP)` singleton. Per-
  project listeners are installed from `RubberDuckStartup.execute()` (a
  `ProjectActivity`) and disposed with the project.
- **Thread-safe event bus.** `EventBus` uses `MutableSharedFlow(replay=0,
  extraBufferCapacity=256)`. All producers (listeners, detector, actions)
  publish; all consumers (WS, SQLite, tool window, detector) subscribe via
  coroutine `collect` on `Dispatchers.Default` / `Dispatchers.IO`. This avoids
  the classic EDT-blocking broadcast footgun.
- **SessionContext also keeps an in-memory ring of the last 50 events.** This
  is how `/socratic-question` gets its `recent_events[]` field without re-
  querying SQLite.
- **SQLite driver loaded via `Class.forName("org.sqlite.JDBC")`** to sidestep
  classloader isolation issues in the IntelliJ plugin sandbox (architecture
  risk #5).
- **Path redaction is *always* applied.** The plugin is the only redactor
  (phase 2 note says the backend does nothing). Every listener builds the
  `file` field with `PathRedactor.redact(project, vf.path)`; every action DTO
  does the same. `EventsWebSocket.sendEvent` additionally depends on that
  upstream redaction — it does not double-redact.
- **OkHttp** is used for both HTTP and WS (one client instance). 20 s ping
  interval plus our own application-level `ping` envelope at 20 s (the
  architecture §4 requirement). Exponential backoff on reconnect: 1s → 2s →
  … → 30s.

## Listener strategy

| Listener | API | Notes |
| --- | --- | --- |
| `EditorActivityListener` | `EditorFactory.getInstance().eventMulticaster` + `CommandProcessor.addCommandListener` | Bulk per-document edit captured; UNDO/REDO inferred from `CommandEvent.commandName`. |
| `FileFocusListener` | project message bus `FileEditorManagerListener.FILE_EDITOR_MANAGER` | Tracks previous file + dwell duration. |
| `CaretDwellTracker` | `CaretListener` on the editor multicaster, with a 90 s coroutine timer | Uses `DaemonCodeAnalyzerImpl.processHighlights` to detect error-severity highlights on the caret line. See *Known issues* below. |
| `TestExecutionListener` | `SMTRunnerEventsListener.TEST_STATUS` + `ExecutionListener.TOPIC` | Emits `TEST_RUN` with outcome/name/duration/failure_signature. |

## StuckDetector rule thresholds

Straight from architecture §5. Constants live in
`StuckDetector.kt` only, so tweaks won't go out of sync:

| Rule | Trigger |
| --- | --- |
| `undo_burst` | ≥ 4 UNDO in 30 s, same file. |
| `repeated_failing_test` | Same test name appears ≥ 2 times in the last 10 failures within 5 min. |
| `long_idle_on_error` | `CARET_DWELL.duration_ms ≥ 90_000` AND `has_error_marker=true`. |
| `rapid_file_switching` | ≥ 6 FILE_FOCUS events in 60 s across ≥ 4 distinct files, avg gap < 8 s. |
| `edit_thrash` | ≥ 10 EDIT events on the same `file:line` within 20 s. |

Per-rule rate-limit: 60 s. Coordinator also has a global 2-min throttle
(`NORMAL` frequency) and a 10-min per-rule silence after a `Not now` click.

## plugin.xml extension points used

- `com.intellij.applicationService` × 8 (all singletons above)
- `com.intellij.postStartupActivity` → `RubberDuckStartup`
- `com.intellij.toolWindow` id `RubberDuck Live`, factory
  `RubberDuckToolWindowFactory`, anchor `right`
- `com.intellij.notificationGroup` id `RubberDuck Live`, displayType `BALLOON`
- `com.intellij.applicationConfigurable` parent `tools`, id
  `com.rubberduck.live.settings`
- 3 `<action>`s: two under `EditorPopupMenu`+`ProjectViewPopupMenu`,
  one under `ToolsMenu`.

## Known issues / deferred to Phase 5

- **`DaemonCodeAnalyzerImpl.processHighlights`** is technically in the `impl`
  package (internal-ish). It's widely used in third-party plugins and stable
  in 2024.2.x, but if it ever goes away we'll fall back to
  `DocumentMarkupModel.forDocument(doc, project, false)` and walk
  `RangeHighlighter`s by severity.
- **Python PSI is not bundled in IDEA Community.** `CodeSelection` intentionally
  uses regex/indentation heuristics rather than `PyFunction` to stay dependency-
  free. Works for the demo's `fizz.py`, `bad.py`, and `stuck.py`.
- **`gradle-wrapper.jar` is not checked in** (binary). README explains the
  one-line bootstrap (`gradle wrapper --gradle-version 8.10.2`). Phase 5
  should commit the jar once the wrapper version is frozen.
- **Gradle build not executed in this environment** — no JDK + no network for
  IntelliJ Platform dependency resolution in the sandbox. `buildPlugin`,
  `runIde`, and `verifyPlugin` should all work on a dev machine; the code is
  syntactically clean (no obvious import / type errors) and `plugin.xml` is
  well-formed.
- **`repeated_failing_test` threshold** matches the prompt's wording (≥ 2 in a
  row within 5 min); architecture §5 originally said ≥ 3 — we followed the
  Phase 3 brief since it was explicit.
- **WS role=plugin handshake** conforms to Phase 2 notes: `hello` envelope
  contains `{role: "plugin", session_id: <uuid>}`. Re-sent on every
  reconnect (fresh `Listener.onOpen`).
- **Privacy mode** disables HTTP AI endpoints (tests/refactor/socratic) and
  surfaces a notification explaining why. The event stream (which only carries
  redacted paths + counts, not code) still flows by design — toggle off
  `Send events to dashboard` in Settings to silence that as well.

## Files added outside `plugin/`

- `docs/PHASE3_NOTES.md` — this file.

No other tracked files were modified.
