# 🏆 Winning Hackathon Prompt: "Help the Developer" Challenge

Here is a carefully crafted, comprehensive prompt you can paste into a fresh chat to kick off planning and implementation. It's designed to maximize your odds of winning first place at Hack Kosice 2026 by targeting JetBrains' judging criteria (innovation, polish, technical depth, developer pain-point relevance, and demo-ability).

---

## 🎯 The Winning Idea (TL;DR)

**Project Name:** **`RubberDuck Live`** — *An AI Pair-Programmer That Watches You Think*

**The unique angle (why this wins):**
Every hackathon team builds "AI code generator #457". Judges are tired of it. Instead, we build something *meta* and emotionally resonant: an **IntelliJ plugin + companion web dashboard** that acts as a **live rubber-duck debugging partner**. It:

1. **Passively observes** your coding patterns (edits, cursor dwell, test runs, error outputs, time-on-line) via the IntelliJ Platform SDK — *no code leaves your machine unless you ask*.
2. **Detects "stuck" moments** (repeated undo, long cursor idle on a red squiggly, same test failing 3+ times, Stack Overflow tab switching proxy via window focus loss) using a local heuristic + small LLM classifier.
3. **Proactively, gently intervenes** with *Socratic questions* (not answers!) — e.g., *"You've rewritten this loop 4 times. What invariant are you trying to preserve?"* — inspired by real rubber-duck debugging research.
4. Offers **on-demand escalation**: inline refactor suggestions, auto-generated characterization tests for legacy code, and a "**Explain My Own Code From 3 Months Ago**" feature.
5. **Session Replay & Insights Dashboard** (web app): shows your "flow state" heatmap, most frustrating files, and personal anti-patterns over time — like Strava for coding.

**Why it wins:**
- ✅ Hits JetBrains' exact prompt (refactor suggester + test creator + META-level developer wellness — *all three*).
- ✅ Emotionally differentiated: "developer wellness" is a fresh, human angle judges remember.
- ✅ Deep IntelliJ integration (real plugin, not a wrapper) = JetBrains loves this.
- ✅ AI-first but privacy-first (local Ollama default) = modern & responsible.
- ✅ Visually stunning demo: the heatmap dashboard is the screenshot-worthy moment.
- ✅ Reuses your existing agentic pipeline from [`APP_EXAMPLE_BUT_MAYBE_TO_BE_REMOVED/`](APP_EXAMPLE_BUT_MAYBE_TO_BE_REMOVED/README.md) as the backend engine for test generation & refactor suggestions.

---

## 📋 THE PROMPT TO PASTE INTO THE NEXT CHAT

Copy everything in the code block below:

```markdown
# Hack Kosice 2026 — JetBrains "Help the Developer" Challenge

## Mission
Build a prize-winning submission for the JetBrains "Help the Developer" challenge. We are aiming for **1st place**. Judging weighs: innovation, technical execution, polish/demo quality, relevance to real developer pain, and AI integration.

## The Product: `RubberDuck Live`
An **IntelliJ Platform plugin** (Kotlin) + **companion Next.js web dashboard** that acts as a passive, privacy-first AI pair-programmer. Instead of generating code for the developer, it observes their workflow, detects "stuck" moments, and intervenes with Socratic questions, targeted refactor suggestions, and auto-generated tests. It also produces a post-session "coding wellness" dashboard showing flow-state heatmaps and recurring anti-patterns.

### Three Pillars (each maps to the JetBrains prompt)
1. **Routine Test Creator** — Right-click any function → "Generate Characterization Tests". Uses our agentic pipeline to produce tests that pin current behavior (great for legacy code).
2. **Refactor Suggester** — Static analysis + LLM detects smells (long methods, duplicate blocks, God classes, deep nesting) and proposes *in-editor diff previews* with one-click apply.
3. **META: Developer Wellness Layer** — The novel bit. Observes edit patterns, detects frustration signals, intervenes Socratically, and produces a personal analytics dashboard.

## Tech Stack
- **Plugin:** Kotlin, IntelliJ Platform SDK (Gradle IntelliJ Plugin 2.x), targets IntelliJ IDEA 2024.2+
- **Local backend:** Python 3.11, FastAPI, WebSockets — **reuse and refactor the existing agentic pipeline** in `APP_EXAMPLE_BUT_MAYBE_TO_BE_REMOVED/backend/src/` (architect agent → worker agents → reviewer → tester). Keep it; rename the folder to `backend/`.
- **Web dashboard:** Next.js 15 (App Router) + TailwindCSS + shadcn/ui — reuse the `APP_EXAMPLE_BUT_MAYBE_TO_BE_REMOVED/frontend/` scaffold; rename to `dashboard/`. Use Recharts for flow-state heatmaps.
- **LLM:** Ollama (default, `qwen2.5-coder:7b`) with OpenAI + custom OpenAI-compatible endpoint fallback. Provider abstraction already exists in the reused backend.
- **Local storage:** SQLite (via plugin) for event log; synced to dashboard via localhost WebSocket.
- **Privacy:** 100% local by default. A single config toggle enables cloud LLM. No telemetry.

## Repository Layout (target)
```
hack-kosice-2026/
├── plugin/                    # IntelliJ plugin (Kotlin, Gradle)
│   ├── build.gradle.kts
│   ├── src/main/kotlin/com/rubberduck/
│   │   ├── RubberDuckPlugin.kt
│   │   ├── listeners/         # DocumentListener, ExecutionListener, etc.
│   │   ├── services/          # EventCollector, StuckDetector, LlmBridge
│   │   ├── actions/           # GenerateTestsAction, SuggestRefactorAction
│   │   ├── ui/                # ToolWindow, inline hints, notification balloons
│   │   └── storage/           # SQLite event log
│   └── src/main/resources/META-INF/plugin.xml
├── backend/                   # FastAPI agentic pipeline (reused)
│   └── …existing structure, cleaned up
├── dashboard/                 # Next.js wellness dashboard (reused scaffold)
│   └── …existing structure, repurposed
├── docs/
│   ├── ARCHITECTURE.md
│   ├── DEMO_SCRIPT.md         # The 3-minute pitch flow
│   └── JUDGING_CHEATSHEET.md
└── README.md                  # Killer README with GIFs
```

## Feature Breakdown (MoSCoW)

### MUST (MVP — required for a working demo)
- [ ] IntelliJ plugin scaffold that loads in IDEA 2024.2+ via `./gradlew runIde`
- [ ] **DocumentListener** capturing edits, undo/redo bursts, timestamps per file
- [ ] **ExecutionListener** capturing test run outcomes (pass/fail/repeated fails)
- [ ] **StuckDetector** service: rule-based heuristic (3+ undos in 30s, or 2+ identical test failures, or 90s idle on a line with a red squiggly)
- [ ] Balloon notification when stuck: *"Want me to ask a rubber-duck question?"* → opens tool window with LLM-generated Socratic prompt
- [ ] **"Generate Tests" right-click action** that sends the selected function to the FastAPI backend, which uses the existing agentic pipeline to emit a `test_<name>.py` or `<Name>Test.kt` file
- [ ] **"Suggest Refactor" action**: sends current file, receives diff, shows in IntelliJ's built-in diff viewer
- [ ] FastAPI backend endpoints: `POST /generate-tests`, `POST /suggest-refactor`, `POST /socratic-question`, `WS /events`
- [ ] Next.js dashboard: one page showing a 24h activity heatmap + "top 3 anti-patterns this week"
- [ ] End-to-end demo flow works with Ollama locally

### SHOULD
- [ ] Inline gutter icons marking "suggestion available" lines
- [ ] Plugin settings panel (LLM provider, intervention frequency, privacy toggles)
- [ ] Characterization-test mode: run the function with fuzzed inputs, snapshot outputs, emit tests pinning current behavior
- [ ] Session replay: scrub through your editing timeline on the dashboard
- [ ] "Explain my old code" action: feeds function + git blame context to LLM for natural-language summary

### COULD
- [ ] Team/leaderboard mode (shared wellness scores — clearly opt-in)
- [ ] VSCode port (shows portability)
- [ ] Slack/Discord "focus time" integration
- [ ] Local vector store of past stuck moments → "You solved something similar on Tuesday"

### WON'T (this hackathon)
- Full code generation à la Copilot (explicitly differentiates us)
- Cloud-hosted SaaS backend

## Execution Plan (delegate as subtasks)

1. **Architecture Phase (architect mode)**
   - Produce `docs/ARCHITECTURE.md` with component diagram, event flow, data schemas (event log, LLM request/response), plugin↔backend protocol, and risk list.
   - Produce `docs/DEMO_SCRIPT.md`: a 3-minute demo narrative hitting all three pillars with a staged "stuck developer" scenario.
   - Decide: remove or rename `APP_EXAMPLE_BUT_MAYBE_TO_BE_REMOVED/`. Recommendation: **keep it, rename `backend/` and `dashboard/`**, delete the `.md` showcase files and `generated_projects/` after salvaging the pipeline.

2. **Backend Refactor Phase (code mode)**
   - Move `APP_EXAMPLE_BUT_MAYBE_TO_BE_REMOVED/backend/` → `backend/`
   - Strip game-generation-specific agents; repurpose architect pipeline for: test generation, refactor suggestion, Socratic question generation (3 new pipeline modes)
   - Add new FastAPI endpoints listed above
   - Add WebSocket endpoint to receive plugin events and forward to dashboard
   - Keep the Ollama/OpenAI/custom provider abstraction — it's already good

3. **Plugin Phase (code mode)**
   - Scaffold with IntelliJ Platform Plugin Template (Kotlin DSL Gradle)
   - Implement listeners, StuckDetector, actions, tool window, settings UI
   - HTTP client to local FastAPI (default `http://localhost:8765`)
   - SQLite event log via `org.xerial:sqlite-jdbc`

4. **Dashboard Phase (code mode)**
   - Move `APP_EXAMPLE_BUT_MAYBE_TO_BE_REMOVED/frontend/` → `dashboard/`
   - Strip "arena" components, build: Heatmap, AntiPatternsList, SessionReplay, StuckMomentsTimeline
   - Connect via WebSocket to backend

5. **Polish Phase (code + architect)**
   - Record demo GIFs for README
   - Write killer README with hero image, problem statement, demo GIF, feature list, architecture diagram, quickstart
   - Prepare `JUDGING_CHEATSHEET.md` (elevator pitch, differentiators, tech highlights, what we'd build next)
   - Dry-run the demo script end-to-end

## Non-Negotiable Quality Bar
- README must have at least one animated GIF of the plugin in action
- `./gradlew runIde` must work out of the box with clear setup steps
- Backend must start with one command (`uv run` or `python -m`)
- Dashboard must start with `pnpm dev`
- Everything works offline with Ollama (don't rely on API keys during judging)
- No hardcoded secrets; `.env.example` for every service

## Inspiration References (verify online during architecture phase)
- JetBrains AI Assistant plugin (what to differentiate against)
- Continue.dev (open-source IDE AI plugin — study their plugin.xml)
- Codescene's "behavioral code analysis" (for the anti-pattern dashboard aesthetic)
- "Rubber Duck Debugging" (Pragmatic Programmer) — cite in pitch
- Wakatime & Code::Stats (for the analytics dashboard inspiration)
- JetBrains Marketplace top plugins for UX patterns

## First Actions for the Next Chat
1. Switch to **architect mode**.
2. Read the existing codebase in `APP_EXAMPLE_BUT_MAYBE_TO_BE_REMOVED/` thoroughly to understand the agentic pipeline we're reusing.
3. Produce `docs/ARCHITECTURE.md` and `docs/DEMO_SCRIPT.md`.
4. Propose a concrete 24-hour build timeline with subtask delegation (which mode handles what, in what order).
5. Ask any clarifying questions (team size, hackathon duration, available API keys, target IntelliJ version) before coding.

## Existing Assets to Leverage
- `APP_EXAMPLE_BUT_MAYBE_TO_BE_REMOVED/backend/src/core/architect_pipeline.py` — reuse architect→worker agent pattern for test generation
- `APP_EXAMPLE_BUT_MAYBE_TO_BE_REMOVED/backend/src/core/agents.py` — reuse LLM client abstraction
- `APP_EXAMPLE_BUT_MAYBE_TO_BE_REMOVED/frontend/` — Next.js + Tailwind + shadcn scaffold is already set up
- `APP_EXAMPLE_BUT_MAYBE_TO_BE_REMOVED/backend/src/api/v1/endpoints/websocket_handler.py` — reuse WebSocket plumbing

Go build something judges remember.
```

---

## 📌 Usage Notes

1. **Paste the entire block above** (inside the ` ```markdown ` fences) into the next chat — it's self-contained.
2. The next chat should start in **architect mode** per the prompt's instructions.
3. Key strategic bets encoded in the prompt:
   - **Differentiation** via "developer wellness / Socratic debugging" — nobody else will build this.
   - **Covers all three** JetBrains sub-prompts (tests, refactor, meta) in one product.
   - **Privacy-first + local LLM** matches 2026 zeitgeist and lets you demo offline without flaky Wi-Fi.
   - **Reuses your existing agentic pipeline** — you're not starting from zero.
   - **Real IntelliJ plugin** (not a web wrapper) — JetBrains judges heavily reward native integration.
4. If you want to tweak the idea before sending, the two highest-impact levers are:
   - Swap the "wellness dashboard" for a "team anti-pattern radar" if you have a bigger team.
   - Drop the plugin and go web-only if nobody on the team knows Kotlin — but this weakens the JetBrains appeal significantly. 
