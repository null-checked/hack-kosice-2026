# RubberDuck Live — VS Code Setup Guide

This is the day-to-day setup for the **VS Code** extension. The backend and
dashboard are unchanged; the extension speaks the exact same HTTP +
WebSocket envelopes as the IntelliJ plugin.

> If you'd rather use IntelliJ, see [`plugin/README.md`](../plugin/README.md)
> and the root [`README.md`](../README.md). The IntelliJ plugin and the VS
> Code extension can run side-by-side against the same backend.

---

## 1. TL;DR — install in 60 seconds

```bash
git clone <this-repo> hack-kosice-2026 && cd hack-kosice-2026
make install        # installs backend, dashboard, plugin wrapper, AND vscode-extension deps
make demo           # backend (mock) + dashboard, in two coloured terminals
make vscode-dev     # opens vscode-extension/ in VS Code
# In VS Code: press F5 → Extension Development Host launches with the duck loaded
```

That's it. Right-click in any file inside the dev host → see
🦆 RubberDuck commands. Open the duck activity bar icon → live event
stream + Socratic feed.

---

## 2. Prerequisites

| Tool | Version | Notes |
|---|---|---|
| Node.js | **20+** | VS Code 1.95 ships with Node 20 in its extension host |
| npm | 10+ | bundled with Node |
| VS Code | **1.95+** | older versions miss `TextDocumentChangeReason` |
| Python | 3.11+ | only needed if you also run the backend |
| `uv` | latest | recommended Python installer (used by `make install`) |

```bash
node --version    # ≥ v20
code --version    # ≥ 1.95.0
```

---

## 3. Install for development (F5 loop)

```bash
cd vscode-extension
npm install        # also done by `make install` from the repo root
npm run compile    # produces out/extension.js
code .             # open vscode-extension/ in VS Code
# Press F5 → "Run Extension"
```

A second VS Code window opens labeled **[Extension Development Host]** with
the duck pre-loaded. Open any folder inside it — Python or otherwise — and
the listeners start firing. The first event in the **Recent Events** view
is your `FILE_FOCUS` for whatever you opened.

If you're iterating, run `npm run watch` in the original window so each save
recompiles, then **Cmd/Ctrl+R** in the dev host to reload the extension.

---

## 4. Install as a `.vsix` for daily use

```bash
cd vscode-extension
npm run package    # produces rubberduck-live-vscode-0.1.0.vsix
code --install-extension rubberduck-live-vscode-0.1.0.vsix
```

Reload VS Code. The duck shows up in the activity bar permanently. To
update, bump `version` in `package.json`, repeat the two commands.

To uninstall: `code --uninstall-extension rubberduck-live.rubberduck-live-vscode`.

---

## 5. Configure

`Cmd/Ctrl+,` → search **RubberDuck Live**. Five settings:

| Setting | Default | Use it when |
|---|---|---|
| `rubberduckLive.backendUrl` | `http://127.0.0.1:8765` | you ran the backend on a different port |
| `rubberduckLive.dashboardUrl` | `http://localhost:3000` | dashboard runs elsewhere |
| `rubberduckLive.sendEventsToDashboard` | `true` | flip off for purely-local test runs |
| `rubberduckLive.interventionFrequency` | `NORMAL` | `MINIMAL` (10 min) / `NORMAL` (2 min) / `CHATTY` (30 s) |
| `rubberduckLive.privacyMode` | `false` | strict — disables Generate Tests + Suggest Refactor |

Setting `privacyMode = true` is the panic switch: code never leaves your
machine even if the backend is reachable. The event stream (which only
carries redacted paths + counts, not code) still flows; toggle
`sendEventsToDashboard` off to silence that as well.

---

## 6. Use the three pillars

Spin up the backend + dashboard in one terminal, then F5 the extension in
another:

```bash
make demo     # ← terminal 1: backend (mock) + dashboard
make vscode-dev  # ← terminal 2: opens VS Code in vscode-extension/, then F5
```

### Pillar 1 — Generate Tests on `fizz.py`

1. Inside the dev host, create `fizz.py` with:
   ```python
   def fizzbuzz(n):
       if n % 15 == 0: return "FizzBuzz"
       if n % 3 == 0:  return "Fizz"
       if n % 5 == 0:  return "Buzz"
       return str(n)
   ```
2. Right-click anywhere inside the function body → **🦆 RubberDuck: Generate Tests**.
3. A progress notification appears; ~2 s later `test_fizz.py` opens in a new
   tab next to it. Run it: the new test is parametrized for the 0/3/5/15 cases.

> Screenshot to record later: `docs/assets/vscode-pillar1.png` — the right-click
> menu showing both 🦆 entries, side-by-side with the generated `test_fizz.py`.

### Pillar 2 — Suggest Refactor on `bad.py`

1. Create `bad.py` with the canonical "smelly" sample (nested loops + magic
   numbers). The repo's [`docs/DEMO_SCRIPT.md`](DEMO_SCRIPT.md) has the exact
   listing.
2. Right-click → **🦆 RubberDuck: Suggest Refactor**.
3. VS Code's native diff viewer opens with **Original** on the left and a
   virtual `rubberduck:/refactor/...` document on the right. The detected
   smells (`nested_loops`, `magic_number`, …) appear in a notification.

> Screenshot to record later: `docs/assets/vscode-pillar2.png` — the diff
> viewer with smell pills along the title bar.

### Pillar 3 — Stuck detection on `stuck.py`

1. Create any file with an obvious bug (off-by-one, wrong comparator).
2. Smash **Cmd/Ctrl+Z** five times in under 30 seconds → after the 4th UNDO
   the `undo_burst` rule fires.
3. A balloon appears: *"🦆 RubberDuck noticed an undo_burst pattern. Want a
   rubber-duck question?"* with **Ask me** / **Not now** buttons.
4. Click **Ask me** → the duck POSTs `/socratic-question` with your last 20
   events + the current code; the answer appears as a follow-up notification
   AND in the **Socratic Feed** activity-bar view.

The wellness score in the status bar (`🦆 Live • 95`) ticks down by 5 each
time a stuck rule fires; it heals back +1 / minute when you're calm.

> Screenshot to record later: `docs/assets/vscode-pillar3.png` — the
> notification + activity-bar Socratic Feed.

---

## 7. Troubleshooting

**"🦆 Backend unreachable"** when running `🦆 RubberDuck: Test Connection`
- Run `make demo` (or `make dev-live`) in another terminal.
- Verify `curl http://127.0.0.1:8765/api/v1/health` returns JSON.
- If you changed the port: update `rubberduckLive.backendUrl`.

**Status bar stuck on `🦆 Offline`**
- The WebSocket couldn't reach `ws://127.0.0.1:8765/api/v1/events`.
- Check the **Output** panel → drop-down → **RubberDuck Live**. You'll see
  the backoff schedule (1s → 2s → 4s → ... → 30s).
- Backend down? `make demo`. Wrong URL? Update settings — the WS reconnects
  with the new URL on the next backoff tick.

**Undo events not firing**
- VS Code only sets `TextDocumentChangeReason.Undo` for *its own* undo
  stack. Some keymap extensions (Vim, Emacs) implement undo differently and
  the change comes through as a regular EDIT.
- Workaround: trigger undo with **Cmd/Ctrl+Z** (the default binding) — that
  always sets the right reason flag.

**No `STUCK_DETECTED` after pasting + deleting a giant block**
- Bulk-edit suppression coalesces `contentChanges.length > 5` into a single
  EDIT with `payload.bulk = true`, by design (see
  [`docs/PHASE7_NOTES.md`](PHASE7_NOTES.md)).
- The detector still tracks per-line `EDIT` density via the unsuppressed
  changes; if you want to force a stuck event for the demo, smash undo
  instead of editing.

**`better-sqlite3` failed to install**
- The extension transparently falls back to JSON-lines logging at
  `~/.rubberduck-live/events.ndjson` — no functional impact.
- Check the **RubberDuck Live** output channel for
  `EventLog: ndjson ready at ...`.

**Generate Tests / Suggest Refactor are greyed out**
- `rubberduckLive.privacyMode` is on. Open settings, untick it.

**Test-run detection is task-based, not test-API-based.**
We deliberately don't use the `vscode.tests` proposed API (which would
require special install flags like `--enable-proposed-api` and would
crash activation in stable VS Code with `CANNOT use API proposal:
testObserver`). Instead we listen for `pytest` / `jest` / `vitest` /
`gradle test` / `mvn test` / `npm test` / `yarn test` / `cargo test` /
`go test` exit codes via the stable `vscode.tasks.onDidEndTaskProcess`
API. This means individual test results aren't tracked — just whole
test-task pass/fail (keyed on the task name) — but it works in any
vanilla VS Code install with zero extra flags. The StuckDetector's
`repeated_failing_test` rule keys on the task name, so this is enough
to drive Socratic interventions.

---

## 8. Why we shipped both IntelliJ and VS Code

The judging audience is split: JetBrains is a sponsor and the original brief
asks for an IntelliJ plugin, but our team — and most demo viewers — drive
VS Code daily. Maintaining both clients was cheap because the **backend
contract is the source of truth** (see [`docs/ARCHITECTURE.md` §3-§4](ARCHITECTURE.md)),
the StuckDetector is a self-contained 100-line port, and path-redaction is
identical across the two languages. The wellness dashboard is wire-format
agnostic — it cannot tell which IDE produced an event, and it shouldn't.

If you're a judge: the IntelliJ plugin proves we shipped the requested
JetBrains experience; the VS Code extension proves the architecture is
portable, not coincidental.
