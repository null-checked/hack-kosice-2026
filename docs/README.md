# RubberDuck Live — Documentation Index

*An AI pair-programmer that watches you think.* An IntelliJ plugin + FastAPI
backend + Next.js dashboard that passively observes a developer's workflow,
detects "stuck" moments, intervenes with Socratic questions, and offers
on-demand test generation and refactor suggestions. **Privacy-first**: local
Ollama by default.

## Core documents

- 🏛 [`ARCHITECTURE.md`](ARCHITECTURE.md) — System design, API contract, event schema, StuckDetector heuristics, agentic-pipeline reuse plan, locked decisions, risks & mitigations, phase handoff checklist.
- 🎬 [`DEMO_SCRIPT.md`](DEMO_SCRIPT.md) — Minute-by-minute 3-minute pitch narrative with exact staged code snippets (fizzbuzz, nested loops, undo-burst), fallback plan if Ollama is slow, and the 10-step **pre-flight checklist** to run 5 minutes before judging.
- 🏆 [`JUDGING_CHEATSHEET.md`](JUDGING_CHEATSHEET.md) — One-page judge reference: elevator pitch, three pillars, top 5 differentiators, tech stack, "what we'd build next", team credits.

## Phase implementation notes

Each phase's engineer logged the decisions they made that weren't already
locked in the architecture doc, plus any known gaps for downstream phases.

- 📝 [`PHASE2_NOTES.md`](PHASE2_NOTES.md) — Backend (FastAPI, LLM provider, WS envelope, mock-mode fixtures).
- 📝 [`PHASE3_NOTES.md`](PHASE3_NOTES.md) — IntelliJ plugin (listeners, StuckDetector, SQLite, threading).
- 📝 [`PHASE4_NOTES.md`](PHASE4_NOTES.md) — Next.js dashboard (WS hook, wellness score derivation, layout).
- 📝 [`PHASE5_NOTES.md`](PHASE5_NOTES.md) — Integration, gradle wrapper bootstrap, 3 compile-error fixes, end-to-end smoke tests.

## Visual assets

- 🖼 [`assets/README.md`](assets/README.md) — recording recipes for `hero.gif`, `pillar1.png`, `pillar2.png`, `pillar3.png`.
- 🖼 [`assets/banner.png`](assets/banner.png) — project banner used by the root README.

## Reading order

1. **Start with** [`JUDGING_CHEATSHEET.md`](JUDGING_CHEATSHEET.md) for the 60-second overview.
2. **Then** [`ARCHITECTURE.md`](ARCHITECTURE.md) for the technical blueprint — source of truth for Phases 2–6.
3. **Finally** [`DEMO_SCRIPT.md`](DEMO_SCRIPT.md) for what the final product looks like on stage.

## Phase status

| Phase | Name | Owner | Status |
| --- | --- | --- | --- |
| 1 | Architecture & planning | Architect | ✅ Complete |
| 2 | Backend refactor + new endpoints | Code | ✅ Complete — see [`PHASE2_NOTES.md`](PHASE2_NOTES.md) |
| 3 | IntelliJ plugin implementation | Code | ✅ Complete — see [`PHASE3_NOTES.md`](PHASE3_NOTES.md) |
| 4 | Dashboard rework | Code | ✅ Complete — see [`PHASE4_NOTES.md`](PHASE4_NOTES.md) |
| 5 | Integration & smoke test | Code | ✅ Complete — see [`PHASE5_NOTES.md`](PHASE5_NOTES.md) |
| 6 | Polish & submission | Code | ✅ Complete — root README, Makefile, preflight, assets |
| — | **Project status** | — | **✅ READY TO DEMO** (pending 4 GIFs + live `runIde` dry-run) |

See [`ARCHITECTURE.md` §13 Phase Handoff Checklist](ARCHITECTURE.md#13-phase-handoff-checklist) for exactly what each downstream phase received.
