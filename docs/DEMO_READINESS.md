# 🦆 RubberDuck Live — Demo Readiness Checklist

**Branch:** `feat/advanced-skills` &nbsp;·&nbsp; **HEAD:** `c18d07b` (pre-smoke) → smoke commit (this commit)
**Verified:** 2026-04-18 &nbsp;·&nbsp; **Scope:** end-to-end smoke of the 7-step chat rollout (commits `69bc808 → c18d07b`)

This document is the single pre-judging sanity checklist. Run through §2 (manual smoke) right before demoing. If anything fails, see §4 (rollback plan).

---

## 1. Automated gates — all passed ✅

| # | Gate | Command | Exit | Notes |
|---|------|---------|------|-------|
| 1 | Backend tests | `cd backend && PYTHONPATH=src uv run pytest -v` | **0** | 16/16 passed (7 chat + 9 legacy) in 0.24 s |
| 2 | Plugin Kotlin tests | `cd plugin && JAVA_HOME=…jdk-21… ./gradlew test --no-daemon` | **0** | `StuckDetectorTest` passes; `compileTestKotlin` clean |
| 3a | VS Code compile | `cd vscode-extension && npm run compile` | **0** | `tsc -p .` clean |
| 3b | VS Code lint | `cd vscode-extension && npm run lint` | **0** | `eslint --max-warnings=0` clean |
| 4a | Dashboard lint | `cd dashboard && npm run lint` | **0** | Next 16 / ESLint 9 clean |
| 4b | Dashboard build | `cd dashboard && npm run build` | **0** | `Compiled successfully in 1878 ms`, 4 static pages generated |
| 5 | Backend live smoke (MOCK) | 5 curl probes vs `uvicorn` on :8000 | **0** | see §1.1 |
| 6 | Plugin distributable | `./gradlew buildPlugin --no-daemon` | **0** | `plugin/build/distributions/rubberduck-live-plugin-0.1.0.zip` — **17,934,274 B (17.9 MB)** |
| 7 | VSIX package | `npx vsce package --no-dependencies` | **0** | `vscode-extension/rubberduck-live-vscode-0.1.0.vsix` — **154,901 B (151 KB, 54 files)** |
| 8 | Chat-webui standalone | `python3 -m http.server 8080 -d chat-webui` + curl | **0** | index HTML served; all 4 vendor assets return **200** |

### 1.1 Backend live smoke — verbatim outputs

`DEMO_MOCK=1 PYTHONPATH=src uv run uvicorn src.main:app --host 127.0.0.1 --port 8000`

1. **Health**
   ```json
   {"status":"ok","llm_provider":"openai","llm_model":"gpt-4o-mini","llm_available":true,"demo_mock":true,"version":"0.2.0"}
   ```

2. **Non-streaming** — `POST /api/v1/chat` with `stream:false`
   ```json
   {"content":"Hi! I'm RubberDuck (mock mode)…","usage":{"prompt_tokens":98,"completion_tokens":29,"total_tokens":127},"finish_reason":"stop","model":"mock-duck-1"}
   ```

3. **SSE streaming** — `POST /api/v1/chat` with `stream:true`; first frames:
   ```text
   data: {"type":"token","delta":"Hi! "}
   data: {"type":"token","delta":"I'm "}
   data: {"type":"token","delta":"Rubb"}
   …
   data: {"type":"usage","prompt_tokens":98,"completion_tokens":29,"total_tokens":127}
   data: {"type":"done","finish_reason":"stop"}
   data: [DONE]
   ```
   → schema `{type:token|usage|done}` matches contract; terminator `[DONE]` present.

4. **Slash command** — `slash_command:"explain"` → `prompt_tokens:169` (base 98 + system prompt injection confirmed).

5. **Skill body** — `skill_body:"# Test Generator…"` → `prompt_tokens:115` (base 98 + skill body injection confirmed).

Backend killed cleanly; `lsof -ti:8000` empty afterwards.

### 1.2 Bundle contents verification

**Plugin JAR** (`lib/rubberduck-live-plugin-0.1.0.jar`, 512 KB):

```text
webui/chat/app.js                     41 224 B
webui/chat/index.html                  3 819 B
webui/chat/vendor/marked.min.js       35 479 B
webui/chat/vendor/dompurify.min.js    21 251 B
webui/chat/vendor/highlight.min.js   121 727 B
webui/chat/vendor/highlight.css        1 315 B
skills/code-explainer.md                 549 B
skills/refactor-suggester.md             604 B
skills/socratic-coach.md                 648 B
skills/test-generator.md                 581 B
```

**VSIX** (`rubberduck-live-vscode-0.1.0.vsix`):

```text
extension/out/extension.js                        6 556 B
extension/media/chat/index.html                   3 819 B
extension/media/chat/app.js                      41 224 B
extension/media/chat/vendor/marked.min.js        35 479 B
extension/media/chat/vendor/dompurify.min.js     21 251 B
extension/media/chat/vendor/highlight.min.js    121 727 B
extension/media/chat/vendor/highlight.css         1 315 B
extension/media/skills/test-generator.md            581 B
extension/media/skills/refactor-suggester.md        604 B
extension/media/skills/socratic-coach.md            648 B
extension/media/skills/code-explainer.md            549 B
```

---

## 2. Manual smoke script (GUI-only — run right before judging)

Automated gates cover headless build/contract correctness; this script covers the JCEF + webview runtime surfaces that cannot be exercised without a GUI session.

### 2.0 Boot the stack

```bash
cd /Users/A200284980/Projects/hack-kosice-2026
make dev          # mock mode (default — no API key required)
# OR
make dev-live     # real OpenAI (needs OPENAI_API_KEY in backend/.env)
# OR
make dev-local    # Ollama on :11434 (offline)
```

Expect: dashboard at `http://localhost:3000`, backend at `http://127.0.0.1:8765`.

### 2.1 IntelliJ (JCEF chat) — 90 s

1. `cd plugin && JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home ./gradlew runIde --no-daemon`
2. Sandbox IDE opens. Open the **Chat** tool window (right-hand rail, duck icon).
3. **Toolbar** loads: brand, model dropdown (5 models), New-chat button, Stop button.
4. Type `hello` → **Enter**. Tokens stream in (~1 s total in mock mode). Usage pill appears below assistant message.
5. Type `/` → slash-command menu appears with `explain`, `generate-tests`, `refactor`, `socratic`. Click `/explain` → input prefix updates.
6. Type `@` → skill mention menu lists **Test Generator, Refactor Suggester, Code Explainer, Socratic Coach**. Pick "Test Generator" → chip rendered above input.
7. Send message → skill body is injected server-side (verify by checking mock reply prompt_tokens > baseline in IDE devtools → Network → `/chat`).
8. **Keyboard shortcuts** (`Help → Find Action…` lookup):
   - **Ctrl+Alt+Shift+K** (⌘+⌥+⇧+K on mac) — focuses chat input from anywhere.
   - **Ctrl+Alt+Shift+N** (⌘+⌥+⇧+N on mac) — starts a fresh chat.
9. **Settings** (`Settings → Tools → RubberDuck Live`) — verify the Chat section:
   - Model **combo box** pre-selected from `rubberduck.chat.defaultModel`
   - Checkboxes: *Attach open files*, *Attach diagnostics*
   - Spinner: *Max history tokens* (default 8000)
   - Apply → restart IDE → values persist.

### 2.2 VS Code (webview chat) — 60 s

1. Open `vscode-extension/` in VS Code, press **F5** — Extension Development Host spawns.
2. Activity bar → **RubberDuck Live** icon → Chat view appears.
3. Type message → tokens stream. Slash (`/`) menu appears on keystroke. `@` lists skills.
4. Right-click in editor with selection → **🦆 RubberDuck: Ask About Selection** → snippet prefilled into chat.
5. Keybindings:
   - **Ctrl+Alt+Shift+K** — focus chat input.
   - **Ctrl+Alt+Shift+N** — new chat (only when chat view focused).
6. **Settings** (`⌘,` → search `rubberduck`):
   - `rubberduck.chatModel` renders as dropdown with 5 enum values.
   - `rubberduck.chatAvailableModels`, `chatMaxHistoryTokens`, `chatAttachOpenFiles`, `chatAttachDiagnostics`, `enabledSkills` all editable.

### 2.3 Dashboard (smoke — not on critical path)

1. Open `http://localhost:3000`. Hero + wellness panels render.
2. Backend-health pill shows green. No console errors.

---

## 3. Known issues / non-blockers

- **`rubberduckLive.backendUrl` and `rubberduckLive.dashboardUrl`** remain in `package.json` with `deprecationMessage` annotations. Superseded by `rubberduck.backendBaseUrl` / `rubberduck.dashboardBaseUrl`. Kept for one release cycle to avoid breaking users' existing settings.json. Not a demo blocker.
- `baseline-browser-mapping` emits a "data is over two months old" warning during `next build`. Cosmetic, no effect on bundle.
- `vsce package` emits a "detected repository" info line — expected now that `repository` is declared.
- Dashboard has not been touched since commit `de644a4` (before the 7-step chat rollout); build + lint green but not end-to-end smoked in GUI. Plan B: dashboard is not on the demo critical path.
- Chat-webui standalone server is for design-time preview only (no SSE; backend URL is relative). Use `make dev` + an IDE shell for a real chat.

---

## 4. Rollback plan

If the chat breaks catastrophically during judging and cannot be recovered in < 2 min:

```bash
git reset --hard 304eb5a
```

That commit (`feat(vscode): replace inline chat with shared webui bundle + full IDE bridge`) is the last known point where **both** IDE shells had working JCEF + webview chat wired to the backend, before slash-commands and settings-unification were layered on. Judges still see a full chat loop; slash menu + settings page are degraded but non-fatal.

Intermediate safer rollback points:
- `01966a8` — post slash-commands, pre settings unification.
- `c18d07b` (current HEAD before this smoke commit) — fully complete; only rollback if the smoke-fix itself introduces regressions (it shouldn't; the fix is purely metadata in `package.json`).

---

## 5. Demo-script excerpts (2-minute happy-path narration)

Matches the tone of [`docs/DEMO_SCRIPT.md`](docs/DEMO_SCRIPT.md:1):

> *[0:00]* "RubberDuck Live is an AI pair programmer that lives inside IntelliJ and VS Code — both wired to the same FastAPI backend. Let's see it."
>
> *[0:15]* "Here's the chat panel in IntelliJ. I type `Summarise the stuck-detector`, and you can see tokens stream in — that's Server-Sent Events from the backend. Mock mode, so it's instant."
>
> *[0:35]* "Now I hit `/` — slash commands: `explain`, `generate-tests`, `refactor`, `socratic`. One-click preset prompts."
>
> *[0:50]* "And `@` — skill mentions. These are markdown files on disk. Pick 'Test Generator' and the skill's system prompt is injected server-side. Same chat, different personality."
>
> *[1:10]* "Flip to VS Code — same extension, same backend, same chat. Ctrl-Alt-Shift-K to focus, Ctrl-Alt-Shift-N for a new conversation."
>
> *[1:30]* "Settings UI in both IDEs — pick a model from the dropdown, toggle open-files attachment, cap history tokens. Stored per-IDE, read by both the bridge and the webview."
>
> *[1:50]* "Entirely local, optional Ollama path, MIT-licensed. Thank you."

Full version in [`docs/DEMO_SCRIPT.md`](docs/DEMO_SCRIPT.md:1).
