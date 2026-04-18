# Phase 7 Notes — VS Code Extension

Decisions and trade-offs from porting the IntelliJ plugin to a feature-equivalent
VS Code extension. The backend and the dashboard were **not** modified; the
extension talks the same HTTP + WebSocket envelopes as the Kotlin plugin.

## What I (re-)used from `TO_BE_REMOVED_TELEMETRY_TRACKER_IMPLEMENTATION/`

The directory was **not present** in this checkout (`ls TO_BE_REMOVED_…` →
`No such file or directory`). Nothing to mine — I built every listener from
scratch against the official `vscode` API, following the same shapes the
IntelliJ plugin uses.

If the directory shows up in a later branch, the natural reuse points would
be patterns around `vscode.workspace.onDidChangeTextDocument`, undo
classification via `TextDocumentChangeReason`, and any IPC scaffolding —
plus its `package.json` `engines.vscode` floor as a sanity check.

## Architectural choices

- **Same wire role.** The WS handshake sends `role:"plugin"` exactly like the
  Kotlin plugin (Phase 2 notes confirm the backend is producer-agnostic). The
  dashboard therefore renders both producers identically.
- **Per-launch session UUID** generated with `node:crypto.randomUUID()` →
  same shape as the IntelliJ `SessionContext`.
- **Detector parity.** Five rules with identical thresholds (`undo_burst` ≥4 in
  30 s, `repeated_failing_test` ≥2 of same name in 5 min, `long_idle_on_error`
  ≥90 s + error marker, `rapid_file_switching` ≥6/≥4 files/<8 s avg gap,
  `edit_thrash` ≥10 EDITs at same `file:line` in 20 s) and 60 s per-rule rate
  limit. Coordinator throttles match: NORMAL = 2 min global, MINIMAL = 10 min,
  CHATTY = 30 s; Not-now silences a rule for 10 min.
- **Storage.** `better-sqlite3` is declared as an `optionalDependency` so the
  install doesn't hard-fail in sandboxes that can't compile native bindings.
  At activation time we try `require('better-sqlite3')` first; on failure we
  transparently fall back to a JSON-lines append-only log at
  `~/.rubberduck-live/events.ndjson`. Both paths run a 30-day prune on init.
  *On this machine, `better-sqlite3` installed cleanly and is the active
  backend.*
- **Path redaction is always applied.** `PathRedactor.redactUri` is called by
  every listener and command DTO before anything leaves the extension host.
  Workspace folders are sorted longest-first so a nested folder wins over a
  parent.
- **Bulk-edit suppression.** `vscode.workspace.onDidChangeTextDocument`
  fires once per text-edit operation but `event.contentChanges` can be huge
  for a paste / multi-cursor refactor. We aggregate into a single EDIT and
  flag `payload.bulk = true` when `contentChanges.length > 5`.
- **Undo detection.** `TextDocumentChangeReason.Undo` / `.Redo` is
  authoritative. If the reason is undefined the change is treated as EDIT —
  matching the architecture's distinction between user typing and the IDE
  rolling history back.
- **Diff viewer via virtual scheme.** Suggest Refactor registers a
  `rubberduck:` `TextDocumentContentProvider` and opens the response with
  `executeCommand('vscode.diff', originalUri, virtualUri, title)` — VS Code's
  native side-by-side diff, no extra UI to maintain.
- **Test-run signal is a fall-back parser.** Most Python projects don't
  register a `vscode.tests` controller, so we listen on
  `vscode.tasks.onDidEndTaskProcess` and pattern-match the task name for
  `pytest|jest|gradle test|vitest|mocha`. If the native testing API does
  fire, we forward it as a generic "tests ran" signal so the StuckDetector
  still gets a heartbeat.
- **Status bar is the wellness HUD.** `🦆 Live • 100` (when WS connected) or
  `🦆 Offline • 100` (when not). Score decays by 5 per `STUCK_DETECTED` and
  heals +1/min, clicking opens the dashboard.

## Files added outside `vscode-extension/`

- `docs/PHASE7_NOTES.md` — this file.
- `docs/VSCODE_GUIDE.md` — the user-facing setup guide.
- `README.md` — added a `## VS Code` section linking to the guide.
- `Makefile` — added `vscode-install`, `vscode-compile`, `vscode-package`,
  `vscode-dev` targets and wired `vscode-install` into `install` and
  `vscode-compile` + lint into `check`.

No backend / dashboard / plugin code was changed.

## Known gaps / deferred

- **No `vscode-test` integration tests.** Vitest covers the pure logic
  (StuckDetector + PathRedactor); the listener wiring is exercised by the
  Extension Development Host (`F5`). Adding `@vscode/test-electron` would be
  a one-day job in Phase 5 polish.
- **`icon.png` is a 122-byte placeholder** (a tiny generated PNG so VS Code
  doesn't choke). Replace with a real 128×128 duck rendered from
  [`plugin/.../pluginIcon.svg`](../plugin/src/main/resources/META-INF/pluginIcon.svg)
  before publishing to the Marketplace.
- **`vscode.tests` API forwarding** is intentionally simplified — the API
  surface differs across VS Code versions and we don't want to depend on
  any internal types. If a future telemetry pass demands per-test outcomes,
  inspect `vscode.tests.onDidChangeTestResults` more carefully.
- **No live `make check` integration in this run beyond `vscode-compile +
  lint`.** The plugin's `gradle buildPlugin` requires a JDK 21 setup that's
  out of scope for Phase 7. The new VS Code targets are wired into `check`
  via a new `check-vscode` step.

## Bug fix 2026-04-18 — proposed-API activation crash

Removed `vscode.tests` proposed-API usage from
[`testExecutionListener.ts`](../vscode-extension/src/listeners/testExecutionListener.ts:1)
because it caused activation to fail in stable VS Code without
`--enable-proposed-api`:

> Activating extension 'rubberduck-live.rubberduck-live-vscode' failed:
> CANNOT use API proposal: testObserver. Its package.json#enabledApiProposals
> declares: [] but NOT testObserver.

Replaced with the stable `vscode.tasks.onDidEndTaskProcess` regex matcher
covering `pytest|jest|vitest|mocha|gradle test|mvn test|npm test|yarn test|
pnpm test|cargo test|go test`. Pass/fail comes from `event.exitCode === 0`;
the emitted `TEST_RUN` payload now carries `{ test_name, passed, outcome,
exit_code, runner, failure_signature }`. `package.json#enabledApiProposals`
is absent. No other proposed APIs in use (verified by grep against
`TestController|createTestObserver|onDidChangeTestResults|TestRunProfile|
enabledApiProposals|@vscode/proposed|terminalDataWriteEvent|
notebookDocumentEvents`). All 15/15 vitest tests still pass; `npm run
compile` and `npm run lint --max-warnings=0` both green.
