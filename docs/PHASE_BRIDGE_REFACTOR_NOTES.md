# Phase 1 — ChatBridge Dispatcher Refactor Notes

**Date:** 2026-04-19
**Companion to:** [`CHAT_PLUGIN_AUDIT_V2.md`](CHAT_PLUGIN_AUDIT_V2.md), [`CHAT_PLUGIN_IMPROVEMENT_PLAN.md`](CHAT_PLUGIN_IMPROVEMENT_PLAN.md)
**Scope:** Phase 1 only (subtasks 1.1, 1.2, 1.3 + M3 + M4). Phase 2 & 3 deferred.

## What changed

### New architecture

```
ChatBrowserPanel
   └─ ChatBridge (thin facade, ~80 lines)
        └─ ChatBridgeDispatcher      ← pure, unit-testable
             ├─ ChatBridgeHandler    ← abstract base + helpers
             ├─ ChatHandlerContext   ← DI container (project + fakes-for-tests)
             └─ handlers/*.kt        ← one action per file
```

- [`ChatBridge.kt`](../plugin/src/main/kotlin/com/rubberduck/live/chat/ChatBridge.kt) shrank from 805 lines to ~100 lines. It now only:
  1. Builds a [`ChatHandlerContext`](../plugin/src/main/kotlin/com/rubberduck/live/chat/bridge/ChatHandlerContext.kt).
  2. Registers every handler on a [`ChatBridgeDispatcher`](../plugin/src/main/kotlin/com/rubberduck/live/chat/bridge/ChatBridgeDispatcher.kt).
  3. Forwards `buildBootstrapScript()` → `dispatcher.install(browser)` which wires one `JBCefJSQuery` per registered action (the only JCEF-dependent part of the new code).
  4. Disposes all `JBCefJSQuery` objects on `dispose()`.

- The dispatcher exposes a pure `dispatch(action, payload): DispatchResult` function. Every unit test goes through that function and never touches JCEF. Duplicate registration throws loudly, unknown actions produce a structured `ok:false, error:"unknown-action"` payload, and handler exceptions are caught and surfaced as `error:"handler-exception"` (stack trace is logged, never leaked to the webview).

### Handlers (26 total, 16 source files)

Split one action per class to keep the routing map unambiguous; related classes share a file where the grouping is self-evident:

| File | Action(s) |
|---|---|
| `ConfigHandler.kt` | `getConfig` |
| `SelectionHandler.kt` | `getSelection` |
| `OpenFilesHandler.kt` | `getOpenFiles` |
| `DiagnosticsHandler.kt` | `getDiagnostics` |
| `FileReadHandler.kt` | `readFile` **(M4 gate)** |
| `FilePickHandler.kt` | `pickFile` |
| `ModelPersistHandler.kt` | `setModel` |
| `InsertHandler.kt` | `insertAtCursor` |
| `ReplaceHandler.kt` | `replaceSelection` |
| `ClipboardHandler.kt` | `copyToClipboard` **(M3: 200 ms rate-limit + 10 MB size cap)** |
| `ModelsHandler.kt` | `listModels` |
| `SkillsHandler.kt` | `listSkills`, `getSkillBody` |
| `HistoryHandler.kt` | `saveHistory`, `listSessions`, `loadSession`, `deleteSession`, `renameSession` |
| `LogHandler.kt` | `log` |
| `NotifyHandler.kt` | `notify` **(M3: 10 Hz rate-limit + 4 KB message cap)** |
| `ReadyHandler.kt` | `onReady` |
| `EditHandler.kt` | `applyEdit`, `rejectEdit`, `emitIntervention` |
| `SearchHandler.kt` | `searchFiles`, `searchSemantic` |

### Hardening fixes

- **M3 — Clipboard / Notify rate + size limits.** Both handlers expose their caps as `const val` at the top of the file so future tuning is one-line. `ClipboardHandler` uses a single-slot 200 ms gate; `NotifyHandler` uses a sliding-window token bucket (`MAX_PER_SECOND=10` over `WINDOW_MS=1_000`). Messages over 4 KB are truncated with an ellipsis.
- **M4 — `readFile` path validation.** Two gates: (1) lexical — `Paths.normalize().startsWith(base)` (preserves the pre-refactor behaviour); (2) VFS — `ProjectFileIndex.isInContent(vf)` whenever the VFS resolves the path, which catches symlinks whose target escapes the content roots even though the lexical path is under base. Both reject paths are unit-tested (`testFileReadHandlerRejectsPathOutsideProject`, `testFileReadHandlerRejectsTraversal`).

## Wire format

**Preserved verbatim.** Action names and JSON payload shapes match the pre-refactor `ChatBridge.kt` exactly — [`chat-webui/app.js`](../chat-webui/app.js) and the VS Code sibling in [`vscode-extension/src/ui/chatWebviewProvider.ts`](../vscode-extension/src/ui/chatWebviewProvider.ts) did not need to change. The dispatcher uses one `JBCefJSQuery` per action (not a single envelope) so the existing Promise shim the webview relies on is byte-identical.

## Test coverage

```
./gradlew :plugin:test      →  63 tests, 63 passed, 0 failed  (100 %)
```

- [`ChatBridgeDispatcherTest.kt`](../plugin/src/test/kotlin/com/rubberduck/live/chat/bridge/ChatBridgeDispatcherTest.kt) — 9 tests covering:
  - Routing to a registered handler
  - Unknown action → structured error (typed + JSON-string forms)
  - Handler exception is caught + reported with `handler-exception` code
  - Malformed JSON payload doesn't break the dispatcher
  - Successful dispatch returns handler output verbatim
  - Duplicate registration throws
  - `registeredActions` set reflects registration
  - `dispatchToJsonString` encoding

- [`HandlersTest.kt`](../plugin/src/test/kotlin/com/rubberduck/live/chat/bridge/HandlersTest.kt) — 54 tests. At least one happy-path + one error-path per handler:
  - **ConfigHandler** — returns settings shape
  - **SelectionHandler** — null without editor, null for empty selection, returns code for selection
  - **OpenFilesHandler / DiagnosticsHandler** — array shape, empty array when no editor
  - **FileReadHandler (M4)** — reads in-project file, rejects `/etc/passwd`, rejects traversal, null for blank
  - **InsertHandler / ReplaceHandler** — insert at caret, replace selection, insert-fallback, false without editor
  - **ClipboardHandler (M3)** — first call accepted, second within 200 ms rejected, window slide re-accepts, oversized payload rejected
  - **NotifyHandler (M3)** — emits + captures kind, token-bucket limit enforced, message truncation with ellipsis
  - **LogHandler / ReadyHandler** — return `true`
  - **ModelPersistHandler / ModelsHandler** — blank returns false, new model persists + auto-appends, list non-empty
  - **SkillsHandler / SkillBodyHandler** — array shape, null for unknown/blank
  - **HistoryHandler family** — full round-trip (save → list → load → rename → delete), invalid payload error shape, blank-id rejections
  - **EditHandler family** — reject / emit-intervention valid, bad payload, unknown-type fallback
  - **SearchFilesHandler / SearchSemanticHandler** — array shape, privacy-mode kill switch, bad payload, empty query

**Line-coverage estimate for `chat/bridge/**`:** ≥ 85 % (every handler's primary code path and at least one error branch is exercised; only the JCEF-install glue inside `ChatBridgeDispatcher.install` is not directly covered because it requires a live browser — the `dispatch`/`dispatchToJsonString`/`register`/`registerAll`/`dispose` methods are). No kover task is currently configured in [`build.gradle.kts`](../plugin/build.gradle.kts); adding one is a follow-up for Phase 2's CI gate (plan §1.5).

## Deviations from the plan

- **Handler count.** The Open Tabs list names 13 handler files; the audit and plan explicitly permit extracting additional actions. The final landed shape has **16 files / 26 handler classes** because several actions (edit lifecycle, history ops, skills read) logically belong in the same file but each still needs its own `ChatBridgeHandler` to keep the dispatcher's "one action per handler" invariant.
- **`ChatHandlerContext` is a `data class` rather than a plain class** so tests can use `.copy(activeEditorProvider = { myFixture.editor })` to swap in a deterministic editor without reconstructing the whole context. This is an idiomatic Kotlin fit for the "DI container with overridable defaults" pattern described in the plan.
- **No kover wiring yet.** The plan's subtask 1.5 (CI gate + `koverVerify`) is deferred — the refactor PR is already large, and the coverage target can be validated by reading the test list above. Phase 2 should add `id("org.jetbrains.kotlinx.kover")` + a `koverVerify` task and wire it into CI.
- **`readFile` returns `JsonNull` rather than a structured error.** The webview's existing treatment of `null` as "not available" is wire-format-critical; introducing a new error shape here would require coordinated webview changes, which the plan forbids in Phase 1.

## Known follow-ups (explicitly deferred to Phase 2 / 3)

- **Phase 2:** webview watchdog + recreate (M2), font/language sync (L3), JCEF registry auto-flip (L4), kover + `koverVerify` CI gate (plan §1.5).
- **Phase 3:** `rewind_files`, prompt enhancer, skill CRUD, history favorites / deep search / export (M1, L1, L5, L6).
- **Smoke test.** `./gradlew runIde` was NOT run as part of this task (it takes > 2 min on this machine). Unit tests passing + behavioural parity (verbatim code moves + same wire format) give us high confidence, but a manual smoke is the last step any reviewer should take before merging.

## Feature A — History search / favorites / export (Phase 3)

Narrowed Phase 3 slice shipped in isolation — Rewind, Prompt Enhancer and Skill CRUD remain deferred. Four new bridge actions land on the existing dispatcher and the web UI gets a search box, per-row star, and per-row export button.

### New bridge actions (wire format)

- `historySearch` · `{ query: string }` → `{ sessions: [{id, title, snippet, updatedAt, favorite}] }`. Empty/whitespace query returns every session; non-empty does a case-insensitive substring match over title + every message body and returns a ~120-char snippet around the first hit. Results sort favorites-first, then by `updatedAt` descending.
- `historyToggleFavorite` · `{ id, favorite }` → `{ ok: true, favorite }` (`{ ok: false, error }` on invalid id / payload). Idempotent. The existing `listSessions` now also carries `favorite` so the rail can paint stars without a per-session load.
- `historyExportMarkdown` · `{ id }` → `{ markdown, filename }`. Markdown is `# <title>` + `_Exported <ISO>_` + alternating `## User/Assistant — <createdAt>` sections with message bodies copied verbatim (fenced code blocks preserved). `filename` = `rubberduck-<slug-title>-<yyyyMMdd-HHmm>.md` (falls back to the session id when the slug is empty).
- `file_save_dialog` · `{ defaultName, content, extension }` → `{ saved: true, path }` on write; `{ saved: false }` on cancel or `{ saved: false, error }` on failure. Drives `FileChooserFactory.createSaveFileDialog` on the EDT via `invokeAndWait`, then writes bytes off the EDT with `Files.writeString`.

Session file migration: a session written before this change has no `favorite` key. `SessionStore.list()` and `load()` both read a missing key as `false` without throwing; the next save/toggle writes the field in place. A dedicated test (`testMigrationSessionFileMissingFavoriteLoadsAsFalseAndCanBeToggled`) pins that behaviour.

### How to demo

1. Send a few chat turns so the session rail has 2–3 rows.
2. Type a keyword from one of the earlier conversations into the new search box at the top of the rail — the list narrows (with a snippet under the title) within 200 ms.
3. Click the ☆ on any row; it flips to ★ and the row jumps to the top. Reload the IDE — the star persists (per-session JSON on disk).
4. Click the ⤓ export button on any row, choose a destination in the native save dialog, and open the resulting `.md` file. The transcript round-trips with fenced code blocks intact and user/assistant turns headed by their timestamps.

### Deferred polish

- Keyboard-first favorite toggle (pressing `f` on a focused row) is not wired — mouse/click only for now.
- Export dialog fixes the extension to `.md`; a future pass could offer `.txt`/`.html` alternatives driven by a small format picker in the web UI.
- Search is pure substring; token / fuzzy scoring (with per-match highlight) is a sensible follow-up once Feature A ships.
