# hack-kosice-2026

**RubberDuck Live** — Hack Košice 2026 entry for the JetBrains *"Help the Developer"* challenge.

## Repository layout

- [`backend/`](backend/) — FastAPI service (Python) exposing REST + WebSocket endpoints that drive the AI agent pipeline. Salvaged from the legacy prototype.
- [`dashboard/`](dashboard/) — Next.js + TypeScript web dashboard for live visualization of agent activity. Salvaged from the legacy prototype.
- [`plugin/`](plugin/) — IntelliJ Platform Kotlin plugin (to be scaffolded) that surfaces RubberDuck Live inside the JetBrains IDE.
- [`docs/`](docs/) — Architecture notes, demo script, and judging cheatsheet (to be written).

## Legacy reference

The folder [`APP_EXAMPLE_BUT_MAYBE_TO_BE_REMOVED/`](APP_EXAMPLE_BUT_MAYBE_TO_BE_REMOVED/) contains the original prototype this project was forked from. Its reusable contents have been migrated into `backend/` and `dashboard/`. It will be deleted once the migration is verified — do not add new code there.
