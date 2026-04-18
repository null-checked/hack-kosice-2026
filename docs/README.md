# RubberDuck Live — Documentation Index

*An AI pair-programmer that watches you think.* An IntelliJ plugin + FastAPI backend + Next.js dashboard that passively observes a developer's workflow, detects "stuck" moments, intervenes with Socratic questions, and offers on-demand test generation and refactor suggestions. Privacy-first: local Ollama by default.

## Documents

- 🏛 [`ARCHITECTURE.md`](ARCHITECTURE.md) — System design, API contract, event schema, StuckDetector heuristics, agentic-pipeline reuse plan, locked decisions, risks & mitigations, phase handoff checklist.
- 🎬 [`DEMO_SCRIPT.md`](DEMO_SCRIPT.md) — Minute-by-minute 3-minute pitch narrative with exact staged code snippets (`fizzbuzz`, bad nested loops, undo-burst), and a fallback plan if Ollama is slow.
- 🏆 [`JUDGING_CHEATSHEET.md`](JUDGING_CHEATSHEET.md) — One-page judge reference: elevator pitch, three pillars, top 5 differentiators, tech stack, "what we'd build next", team credits.

## Reading Order

1. **Start with** [`JUDGING_CHEATSHEET.md`](JUDGING_CHEATSHEET.md) for the 60-second overview.
2. **Then** [`ARCHITECTURE.md`](ARCHITECTURE.md) for the technical blueprint — this is the source of truth for Phases 2–5.
3. **Finally** [`DEMO_SCRIPT.md`](DEMO_SCRIPT.md) for what the final product will look like on stage.

## Phase Status

| Phase | Name | Owner | Status |
| --- | --- | --- | --- |
| 1 | Architecture & planning | Architect mode | ✅ Complete — see the three docs above |
| 2 | Backend refactor + new endpoints | Code mode | 🟢 Ready to start |
| 3 | IntelliJ plugin implementation | Code mode | ⏳ Blocked on Phase 2 API contract (already locked in [`ARCHITECTURE.md`](ARCHITECTURE.md §4)) — can start in parallel against mocked backend |
| 4 | Dashboard rework | Code mode | ⏳ Can start in parallel (types locked in §3, §7) |
| 5 | Polish, demo rehearsal, README GIFs | Code + architect | ⏳ |
| 6 | Submission & pitch | All | ⏳ |

See [`ARCHITECTURE.md` §13 Phase Handoff Checklist](ARCHITECTURE.md#13-phase-handoff-checklist) for exactly what each downstream phase receives.
