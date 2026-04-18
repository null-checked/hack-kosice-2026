# Metrics Tracker VS Code Extension

This repository contains an initial VS Code extension setup for collecting editor metrics and writing them to a local file.

## What Is Tracked

- Written text (inserted text)
- Removed text (deleted/replaced text)
- Cursor and selection position changes
- Document open/close events
- Session start/end events

## Metrics Format

Metrics are stored as **NDJSON** (newline-delimited JSON): one JSON object per line.

Why NDJSON:

- Easy to process later (Python, Node, jq, Spark, etc.)
- Human-readable line-by-line events
- Works well with bounded retention (last N lines/events)

To keep disk usage controlled:

- Only the most recent `metricsTracker.maxEvents` events are persisted.
- Older events are dropped automatically.
- The metrics output file itself is excluded from tracking by default.
- Cursor events are debounced and logged after movement stops.
- Text changes are aggregated and logged after typing pauses.

Each event follows schema version `1` and includes:

- `eventId`, `sessionId`, `timestamp`
- `eventType`
- `workspaceName`
- `document` metadata (`uri`, `languageId`, `version`, `isUntitled`)
- `payload` with event-specific fields

Example event line:

```json
{"schemaVersion":1,"eventId":"f2d18d88-c61d-4f8e-8572-91c59d4be484","sessionId":"9f3f6fec-b44f-4646-b2ef-f8104fb0b352","timestamp":"2026-04-18T10:43:16.045Z","eventType":"text-change","workspaceName":"hack-kosice-2026","document":{"uri":"file:///d%3A/Desktop/hack-kosice-2026/src/extension.ts","languageId":"typescript","version":42,"isUntitled":false},"payload":{"changeIndex":0,"kind":"replace","reason":"other","rangeOffset":912,"rangeLength":4,"rangeStart":{"line":38,"character":6},"rangeEnd":{"line":38,"character":10},"insertedLength":5,"removedLength":4,"insertedText":"write","removedText":"save"}}
```

Output file default:

- `.metrics/events.ndjson` (relative to the first workspace folder)

## Quick Start

1. Install dependencies:

```bash
npm install
```

2. Compile extension:

```bash
npm run compile
```

3. Press `F5` in VS Code to run Extension Development Host.

4. In the development host, edit files.

5. Open command palette and run:

- `Metrics Tracker: Open Metrics File`

## Configuration

Available settings:

- `metricsTracker.outputFile`: file path for metrics output
- `metricsTracker.captureRawText`: include raw inserted/removed text values
- `metricsTracker.captureCursorSelections`: include cursor/selection movement events
- `metricsTracker.excludeOutputFileFromTracking`: skip tracking the output file itself (prevents recursive logs)
- `metricsTracker.maxEvents`: maximum number of retained events (last N)
- `metricsTracker.writerFlushIntervalMs`: flush interval for buffered metrics writes
- `metricsTracker.textChangeDebounceMs`: idle time before writing aggregated text-change events
- `metricsTracker.cursorDebounceMs`: idle time before writing cursor-selection events
- `metricsTracker.maxRawTextChars`: max raw inserted/removed chars per text-change event

## Notes

- Removed text is reconstructed from a per-document in-memory snapshot to capture deleted content.
- Text-change events are now batched (counts + lengths + optional raw text fields `insertedText` and `removedText`) rather than one line per keystroke.
- Cursor/selection events are emitted after debounce to reduce noise from rapid mouse movement.

## Log Visualization And Analysis

Use the analyzer script to generate charts, CSV tables, and an evaluation report from any metrics NDJSON file.

Run:

```bash
python scripts/analyze_metrics.py --input test_logs/events.ndjson --output analysis_output
```

Generated outputs include:

- `analysis_report.md` (human-readable evaluation)
- `overview.csv`, `event_type_counts.csv`, `document_activity.csv`, `session_activity.csv`
- `event_type_distribution.png`, `events_per_minute.png`, `cumulative_text_volume.png`
- `top_documents_by_events.png`, `cursor_kind_distribution.png`