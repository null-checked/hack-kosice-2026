# feat(chat): Copilot-grade chat for IntelliJ + VS Code (MVP)

## Summary

This PR restores and rebuilds the **chat experience from scratch** as a cohesive MVP across both IDE plugins: an IntelliJ JCEF tool window and a VS Code webview panel share a single vanilla HTML/JS/CSS bundle (`chat-webui/`), backed by a new SSE-streaming `/api/v1/chat` backend endpoint. Slash commands (`/explain`, `/tests`, `/refactor`, `/socratic`) and `@skill` mentions are wired end-to-end against the restored markdown-based Skills system, and all chat-related settings live under a unified `rubberduck.*` namespace with an identical `window.rubberduck.*` bridge contract exposed in both IDEs.

The previous `feat/advanced-skills` history (Phase 9.x panels: Checkpoints, Agent Manager, Code Index, Session Replay, Anti-pattern Radar, Explain-Old-Code, Quick Wins, env management) was **force-replaced** on this branch. Only the Skills subsystem was re-landed (as a dependency for `@skill` mentions); the other Phase 9 panels are intentionally deferred — see `docs/CHAT_FEATURE_PLAN.md` for MVP-scope decisions.

---

## What's in this PR

| # | SHA | Commit | One-liner |
|---|-----|--------|-----------|
| 1 | `69bc808` | `feat(plugin): restore markdown-based Skills system lost in merge (prep for chat slash-commands)` | Re-lands `SkillsManager` + 4 built-in `.md` skills so `@skill` mentions resolve. |
| 2 | `69c2290` | `feat(backend): add streaming SSE chat endpoint with context assembly and slash commands` | New `POST /api/v1/chat` — SSE tokens, `[DONE]` sentinel, context builder, slash-command routing. |
| 3 | `392f5d8` | `feat(chat-webui): add shared zero-build chat UI bundle consumed by IntelliJ and VS Code` | Single `chat-webui/` (HTML/JS/CSS + vendored `marked`/`highlight.js`/`DOMPurify`), copied verbatim to both IDEs. |
| 4 | `4417e47` | `feat(plugin): embed JCEF-based streaming chat tool window with full IDE bridge` | IntelliJ `ChatToolWindow` + `ChatBridge` + `ChatResourceHandler` (JBCefJSQuery round-trip). |
| 5 | `304eb5a` | `feat(vscode): replace inline chat with shared webui bundle + full IDE bridge` | VS Code `ChatWebviewProvider` serving the same bundle with matching bridge surface. |
| 6 | `01966a8` | `feat(chat): wire slash commands and @skill mentions end-to-end` | Front-end parser + back-end resolver; Skills loaded on both sides; mention autocompletion. |
| 7 | `c18d07b` | `feat(chat): unify settings namespace and bridge contract` | `rubberduck.*` everywhere; deprecated `rubberduckLive.*` shim; 15-method bridge parity. |
| 8 | `56b5b3a` | `test(chat): end-to-end smoke verification and demo readiness checklist` | Backend smoke run, ZIP/VSIX build verification, `docs/DEMO_READINESS.md` manual steps. |

---

## Key metrics

- **Backend**: 16/16 pytest passing (7 new tests in `backend/tests/test_chat.py`)
- **IntelliJ plugin**: `compileKotlin` + `test` green; distributable ZIP **17.9 MB** at `plugin/build/distributions/rubberduck-live-plugin-0.1.0.zip`
- **VS Code extension**: `tsc` + `eslint` clean; VSIX **151 KB** at `vscode-extension/rubberduck-live-vscode-0.1.0.vsix`
- **Shared web UI**: 3 first-party files + vendored `marked` / `highlight.js` / `DOMPurify`, served byte-identical from both IDEs
- **Dashboard**: lint + Next 16 build clean (untouched in this PR)

---

## New endpoints / APIs

### Backend

- **`POST /api/v1/chat`** — SSE-streaming chat
  - Frame schema: `{ "type": "token" | "usage" | "done" | "error", ... }`
  - Stream terminator: `data: [DONE]\n\n` sentinel
  - Request body: `{ messages, context?, slashCommand?, skillRefs?, stream: true }`
  - Context assembly: active file path, selection, open-editor list (supplied by IDE bridge)
  - Slash-command routing: `/explain`, `/tests`, `/refactor`, `/socratic` dispatch to existing v0.3 pipelines

### IDE bridge contract (`window.rubberduck.*` — identical in IntelliJ JCEF and VS Code webview)

15 methods, stable across both IDEs:

```
getState() · setState(partial) · getSettings() · updateSettings(partial)
getContext() · getOpenEditors() · getActiveSelection()
sendChat(payload) · abortChat(id)
listSkills() · getSkill(id)
openFile(uri, opts) · insertAtCursor(text) · applyEdit(edit)
log(level, msg)
```

---

## What was DISCARDED from origin/feat/advanced-skills

The previous history on origin was force-replaced. These 10 commits are gone from `feat/advanced-skills` (still reachable by SHA until GC — no data is lost):

| SHA | Commit |
|-----|--------|
| `03301f9` | `feat(plugin): Phase 9.A Quick Wins + integrate all Phase 9 panels into tool window (with correctness fixes)` |
| `7ddb73a` | `fix(vscode): add launch.json/tasks.json and connection logging so F5 launches extension against live backend` |
| `8664038` | `feat(backend,plugin): add unified .env management via /health env_info (Phase 9.I)` |
| `ae1f41f` | `feat(plugin): add Anti-pattern Radar with project view severity overlays (Phase 9.H)` |
| `ff34a83` | `feat(plugin,backend): add "Explain My Old Code" right-click action + backend endpoint (Phase 9.G)` |
| `e9513fd` | `feat(plugin): add Checkpoint/Restore for plugin state (Phase 9.F)` |
| `9f7c915` | `feat(plugin): add local TF-IDF Semantic Codebase Indexing with SQLite FTS5 (Phase 9.E)` |
| `72074ae` | `feat(plugin): add Agent Manager Panel for parallel agent sessions (Phase 9.D)` |
| `0a65df1` | `feat(plugin): add markdown-based Plugin Skills System loaded at runtime from .skills/ (Phase 9.C)` |
| `5457cc7` | `feat(plugin): add Session Replay Panel with timeline and playback controls (Phase 9.B)` |

Rationale: the chat MVP is the primary user-facing demo surface; Phase 9 panels were experimental/partial and blocked the shared bridge refactor. **Only the Skills system was re-landed** (commit 1 above) because it is a hard dependency for `@skill` mentions. The other panels can be re-introduced on top of the new bridge contract in a follow-up branch.

See `docs/CHAT_FEATURE_PLAN.md` §MVP decisions for the full trade-off rationale.

---

## Manual smoke checklist for reviewer

Full steps in [`docs/DEMO_READINESS.md`](docs/DEMO_READINESS.md). Quick version:

<details>
<summary><strong>IntelliJ plugin (click to expand)</strong></summary>

1. `cd plugin && ./gradlew runIde` (or install `plugin/build/distributions/rubberduck-live-plugin-0.1.0.zip` into a stock 2024.2+ IDE).
2. Ensure backend is running: `cd backend && ./run.sh` — confirm `GET /api/v1/health` returns 200.
3. Open the **RubberDuck Chat** tool window (right sidebar).
4. Type `Hello` → verify tokens stream in (no full-response latency).
5. Type `/explain` then select a code block → verify slash command dispatches.
6. Type `@socratic-coach how do I debug this?` → verify skill autocompletes and resolves.
7. Open **Settings → Tools → RubberDuck Live** → verify backend URL / model fields, toggle auto-open, save.

</details>

<details>
<summary><strong>VS Code extension (click to expand)</strong></summary>

1. Install the VSIX: `code --install-extension vscode-extension/rubberduck-live-vscode-0.1.0.vsix` (or F5 in the `vscode-extension/` workspace).
2. Backend running (same as above).
3. Open the **RubberDuck** activity bar icon → **Chat** view.
4. Repeat steps 4–6 above — streaming, slash commands, `@skill` mentions should behave identically to IntelliJ.
5. Open **Settings** → search `rubberduck` → verify unified namespace (backend URL, model, auto-open, telemetry).

</details>

---

## Known non-blockers

- **Deprecated-namespace shim**: `rubberduckLive.*` settings keys are still read as fallback for existing installs; a console warning is logged on read. Remove in next major.
- **Untracked `.rubberduck/` and `.skills/`**: local state/session directories — gitignored in a separate pre-existing commit; not part of this PR's diff but may appear in the working tree.
- **`docs/CHAT_FEATURE_PLAN.md`**: untracked planning doc — intentionally kept out of this PR to keep the diff focused; will be committed separately.
- **`.gitignore` modification**: small pre-existing working-tree change covering `.rubberduck/`/`.skills/`; not staged here.
- **No demo GIFs**: capture during the manual smoke above (no GUI session available during authoring).

---

## Rollback plan

- **Full revert (drop entire chat stack)**:
  ```bash
  git revert 56b5b3a^..56b5b3a
  # ...repeat for c18d07b, 01966a8, 304eb5a, 4417e47, 392f5d8, 69c2290, 69bc808
  # or equivalently:
  git revert --no-commit 69bc808^..56b5b3a && git commit -m "revert: chat MVP"
  ```
- **Partial revert (keep Skills + JCEF + webview shells, drop slash/@skill/settings unification + smoke)**:
  ```bash
  git reset --hard 304eb5a
  git push --force-with-lease origin feat/advanced-skills
  ```
- **Nuclear option (restore pre-chat origin state)**: not recommended — the old Phase 9.x commits are still reachable by SHA (`03301f9` etc.) via reflog / unreferenced-objects for ~90 days, but some partially shared files (Skills) have moved on.

---

## Screenshots / demo

No GUI session was available during authoring — please capture short GIFs during the manual smoke checklist above. Suggested captures:

1. IntelliJ: streaming tokens + slash command dispatch.
2. VS Code: identical UX in the sidebar.
3. `@skill` autocomplete dropdown on either IDE.

---

## Checklist

- [x] Backend tests pass (`cd backend && pytest` — 16/16)
- [x] Plugin builds (`cd plugin && ./gradlew buildPlugin`)
- [x] VS Code extension builds (`cd vscode-extension && npx vsce package`)
- [x] Dashboard still builds (`cd dashboard && npm run build`)
- [x] No new secrets, credentials, or `.env` values committed
- [x] No tags created for this push
- [ ] Reviewer has run the manual smoke checklist
- [ ] Demo GIFs attached
