# RubberDuck Live — Wellness Dashboard

A single-page Next.js 16 App-Router dashboard that visualises a developer's
coding session in real time. Subscribes to the backend event stream over
WebSocket, derives wellness metrics client-side, and renders seven live panels.

- **Backend WS**: `ws://127.0.0.1:8765/api/v1/events`
- **Backend HTTP**: `http://127.0.0.1:8765/api/v1`
- **Port**: `3000`
- **Theme**: dark-first, violet/cyan/amber accent palette.

## Panels

| Panel | Source |
| --- | --- |
| Wellness Score (0–100 ring) | `wellnessScore(events)` over last 60 min |
| Activity Heatmap (24h) | `heatmapBuckets(events)` |
| Flow-state Chart | `bucketize(events, 5min × 24)` |
| Anti-patterns | `antiPatternCounts(events)` grouped by rule |
| Stuck Moments Timeline | `stuckMoments(events)` joined with INTERVENTION_* |
| Socratic Feed | `INTERVENTION_SHOWN` payload questions |
| Event Stream | Last 50 events, monospace |
| Metric Tiles | Events/min + top file |

All derivation is pure and lives under [`lib/metrics.ts`](lib/metrics.ts).

## Run

```bash
cd dashboard
npm install
cp .env.example .env.local   # optional; defaults work
npm run dev                  # → http://localhost:3000
```

Demo-safe mode (injects 2 minutes of synthetic events on button click):

```bash
NEXT_PUBLIC_DEMO_REPLAY=1 npm run dev
```

## Production build

```bash
npm run build
npm start
```

## Architecture highlights

- **Single WS connection** via [`hooks/useEventsSocket.ts`](hooks/useEventsSocket.ts):
  sends the `hello`/`dashboard`/`*` handshake documented in
  [`docs/PHASE2_NOTES.md`](../docs/PHASE2_NOTES.md), replies to `ping` with
  `pong`, applies exponential backoff on disconnect.
- **Snapshot first, events after**: first inbound frame is
  `{kind:"snapshot"}` — seeds the event list. Subsequent `{kind:"event"}`
  frames are appended; memory is bounded to 2000.
- **Cheap derivation** via [`hooks/useDerivedMetrics.ts`](hooks/useDerivedMetrics.ts):
  memoised + throttled to ≤ 500 ms so bursts can't thrash React.
- **Empty states** everywhere — every panel renders something with zero events.
- **Zero telemetry**, zero external fonts loaded at request time (Inter +
  JetBrains Mono via `next/font`).

## Design language

- Background `#0b0f17`, surface `#121826`, borders `#1f2937`.
- Accents: violet `#8b5cf6`, cyan `#22d3ee`, amber `#f59e0b`, rose `#ef4444`.
- Mesh-gradient backgrounds via pure CSS radial gradients.
- Typography: Inter (UI) + JetBrains Mono (event stream).
