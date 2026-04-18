# Phase 4 Notes

Dashboard implementation details that weren't fully spelled out in the
architecture doc. Phase 5 (polish) can revisit any of these.

## Contract compliance

- **WS handshake** matches [`docs/PHASE2_NOTES.md`](PHASE2_NOTES.md) exactly:
  first outbound frame is
  `{type:"hello", timestamp, payload:{role:"dashboard", session_id:"*"}}`.
- First inbound frame is a `broadcast` with `payload.kind === "snapshot"` —
  treated as authoritative, replaces in-memory events.
- Subsequent `broadcast` frames with `kind === "event"` are appended; memory
  is bounded to **2000** events (plugin SQLite remains the durable store).
- `ping` frames are answered with `pong` + current `Date.now()`.
- `ack` frames are silently accepted.
- `error` payloads bubble up to `lastError` but never crash the dashboard.

## Derivation

- All derivations are pure functions in [`lib/metrics.ts`](../dashboard/lib/metrics.ts)
  and are covered by a single memoised hook
  [`useDerivedMetrics`](../dashboard/hooks/useDerivedMetrics.ts) throttled to
  500 ms so bursts of events (plugin-side `ApplicationManager.invokeLater`
  floods) can't thrash React.
- Wellness score follows the brief exactly: start 100, −8/stuck (cap 40),
  −3/undo-cluster (cap 15), +5 if intervention accept-rate > 50 %, +0.2/pass
  (cap 10). Labels: `Flowing` ≥ 90, `Focused` ≥ 70, `Working` ≥ 50,
  `Struggling` ≥ 30, else `Stuck`.
- `antiPatternCounts` computes a naive 15-min vs previous-15-min delta so the
  arrow badge is meaningful even on a short timeline.

## Design decisions

- **Demo replay** is gated by `NEXT_PUBLIC_DEMO_REPLAY=1`. It synthesises
  ~120 s of plausible events ending in an `undo_burst` STUCK + accepted
  Socratic intervention mirroring the demo script. No fixture files on disk —
  the generator is fully in code at [`lib/demoReplay.ts`](../dashboard/lib/demoReplay.ts).
- **Single grid breakpoint**: the `lg:grid-cols-12` layout collapses to a
  single column below 1024 px. That's enough for judges' 1440×900 screens
  plus graceful fallback; responsive polish can wait for Phase 5.
- **No framer-motion** — every transition is a tailwind class. Live dot uses
  `animate-pulse`; wellness ring uses `stroke-dashoffset` transition.
- **No global state library**. Events live in the socket hook; everything
  else is props. Easier to reason about, faster to ship.
- **Path restrictions**: the dashboard never displays a raw absolute path —
  `shortenFile()` collapses to the last two segments. The plugin already
  redacts before transmitting, so this is just a display convenience.

## Known gaps for Phase 5

- **Heatmap granularity**: the brief described 7-day × 24-hour; current impl
  is 1 rolling day × 24 hours. With the plugin's SQLite retention (30 days)
  we have the data — just needs the backend to opt-in to replaying more than
  500 events, or the dashboard to hit an HTTP history endpoint. Out of scope
  for now.
- **Session switcher** lists sessions seen since the dashboard connected.
  Historical sessions that predate the connection won't appear. Acceptable
  for a demo where the judge watches a single IDE + dashboard.
- **Accessibility**: keyboard focus + `focus-visible` rings are wired; full
  ARIA labelling on the Recharts SVG is left as-is (Recharts default).
- **Auto-scroll "sticky bottom"** threshold is 12 px. Slow scroll-wheel users
  might hit the jump-to-bottom occasionally; bump to 32 px if it's annoying.
- **Backend-down UX**: `StatusPill` shows "Offline — retrying…" and the
  `/health` poll surfaces the LLM provider in the footer when reachable. No
  modal; the dashboard keeps rendering the last-known events.

## File layout

```
dashboard/
├── .env.example
├── README.md
├── app/{layout,page,globals.css,favicon.ico}
├── components/
│   ├── layout/{Hero, StatusPill, SessionSwitcher}.tsx
│   ├── viz/{Sparkline, MetricTile}.tsx
│   ├── wellness/{PanelCard, WellnessScore, ActivityHeatmap,
│   │             FlowStateChart, AntiPatternsList,
│   │             StuckMomentsTimeline, SocraticFeed, EventStream}.tsx
│   └── ui/ (untouched shadcn primitives)
├── hooks/{useEventsSocket, useDerivedMetrics, useBackendHealth}.ts
└── lib/{types, metrics, format, demoReplay, utils}.ts
```

## Build / typecheck

- `npm install` — 403 packages, no audit warnings blocking install.
- `npx tsc --noEmit` — ✅ clean.
- `npm run build` — ✅ compiled with Turbopack, static `/` route, ~231 ms of
  static generation.
- `npx eslint . --max-warnings=0` — ✅ clean.
