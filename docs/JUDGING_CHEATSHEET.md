# RubberDuck Live — Judging Cheatsheet

*One-page reference for judges at Hack Košice 2026 · JetBrains "Help the Developer" challenge.*

---

## Elevator Pitch

**RubberDuck Live is a privacy-optional IntelliJ plugin that watches how you code, detects when you're stuck, and asks the right Socratic question instead of generating more code for you. Runs on OpenAI `gpt-4o-mini` by default; one env flip moves the entire AI path onto a local Ollama for zero-outbound privacy.**

---

## The Three Pillars (each maps to the JetBrains prompt)

- 🧪 **Test Creator** — Right-click any function → *Generate Tests*. Local LLM, structured pipeline, pytest/JUnit output written straight into the editor.
- ♻️ **Refactor Suggester** — Right-click → *Suggest Refactor*. Smells detected (nested loops, magic numbers, dupes), unified diff rendered in IntelliJ's native diff viewer.
- 🦆 **META Wellness Layer** — Passive listeners + a rule-based StuckDetector catch undo-bursts, repeated failing tests, long idles on errors, rapid file switches — then pop a Socratic question and log the moment to a personal analytics dashboard.

---

## Top 5 Differentiators vs Other Hackathon Entries

1. **AI via OpenAI `gpt-4o-mini` by default; fully swappable to local Ollama for zero-outbound privacy.** Plugin ↔ backend ↔ dashboard stay on `127.0.0.1` no matter what — the *only* outbound hop is the chosen LLM provider, and that hop disappears entirely when you flip `LLM_PROVIDER=ollama`. Privacy-optional, not privacy-required — still unusual in a hackathon field where most entries hard-code a vendor.
2. **Socratic, not generative** — We don't autocomplete. We ask *"What invariant are you trying to preserve?"* This is a genuinely new interaction pattern in a space full of Copilot clones.
3. **META developer-wellness angle** — Flow-state heatmap, personal anti-pattern detection, stuck-moment timeline. No other entry will have this.
4. **Native IntelliJ plugin** — Real Kotlin + Platform SDK integration (actions, listeners, tool window, balloon notifications, native diff viewer), not a web-app wrapper. JetBrains judges reward this heavily.
5. **Built on a proven agentic pipeline** — Reuses a battle-tested planner→worker→reviewer backend from a prior project; the LLM JSON-retry scaffolding already works. We're not prototyping the engine during the hackathon.

---

## Tech Stack (one-liner)

**Kotlin / IntelliJ Platform SDK 2024.2+** plugin · **Python 3.13 / FastAPI / httpx** backend with a reused architect-agent pipeline · **Next.js 16 / React 19 / Tailwind 4 / shadcn** dashboard · **OpenAI `gpt-4o-mini`** default LLM with Ollama `qwen2.5-coder:7b` + OpenAI-compatible fallback chain · **SQLite** for local event log · all inter-component traffic is loopback, ports `8765 / 3000` (+ `11434` when running Ollama).

---

## Screenshots

See the three polished pillar mockups in the root
[`README.md`](../README.md#the-three-pillars) — each one covers a right-click
action (Generate Tests, Suggest Refactor) or the Socratic-balloon +
wellness-dashboard loop. Source SVGs live under
[`assets/`](assets/) and can be swapped 1:1 for real GIFs/PNGs using the
recording recipes in [`assets/README.md`](assets/README.md). The full
architecture diagram is in [`ARCHITECTURE.md` §1](ARCHITECTURE.md#1-high-level-architecture).

---

## What We'd Build Next

1. **Team mode** — opt-in shared anti-pattern radar so squads can see which files are frustration hotspots.
2. **Session replay** — scrub the editor timeline and replay the stuck moments with the questions that were asked.
3. **VSCode port** — same backend, new thin client; proves portability.
4. **"Explain my old code"** — feed function + git-blame context to the LLM for a natural-language summary of code you wrote three months ago.
5. **Local vector memory of past stuck moments** — "You solved something similar on Tuesday — here's the commit."

---

## Quick Facts

- **Lines of plugin code**: *TBD Phase 5*
- **Backend endpoints**: 4 (`/generate-tests`, `/suggest-refactor`, `/socratic-question`, `/health`) + 1 WebSocket (`/events`)
- **Stuck-detection rules**: 5 (undo burst, repeated failing test, long idle on error, rapid file switching, edit thrash)
- **Works offline**: ✅ with `LLM_PROVIDER=ollama` (and `DEMO_MOCK=1` needs nothing)
- **Telemetry**: ❌ none

---

## Team Credits

*TBD — fill in Phase 5:*
- `<Name>` — Plugin / IntelliJ Platform
- `<Name>` — Backend / Agentic pipeline
- `<Name>` — Dashboard / UX
- `<Name>` — Demo, design, pitch

*Hack Košice 2026 · JetBrains "Help the Developer" Challenge*
