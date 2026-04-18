/**
 * Canonical developer-event types — wire-compatible with the IntelliJ plugin
 * (see [`plugin/.../EventType.kt`](../../../plugin/src/main/kotlin/com/rubberduck/live/model/EventType.kt:1))
 * and the backend schema in [`docs/ARCHITECTURE.md §3`](../../../../docs/ARCHITECTURE.md).
 *
 * The string form is what crosses the wire.
 */
export const EventType = {
  EDIT: 'EDIT',
  UNDO: 'UNDO',
  REDO: 'REDO',
  TEST_RUN: 'TEST_RUN',
  FILE_FOCUS: 'FILE_FOCUS',
  CARET_DWELL: 'CARET_DWELL',
  STUCK_DETECTED: 'STUCK_DETECTED',
  INTERVENTION_SHOWN: 'INTERVENTION_SHOWN',
  INTERVENTION_ACCEPTED: 'INTERVENTION_ACCEPTED',
  INTERVENTION_DISMISSED: 'INTERVENTION_DISMISSED',
} as const;

export type EventType = (typeof EventType)[keyof typeof EventType];

/**
 * A single developer event. `ts` is epoch millis; `file` MUST already be
 * project-relative (redacted by `pathRedactor`); `payload` is type-specific.
 */
export interface DeveloperEvent {
  event_id: string;
  session_id: string;
  ts: number;
  type: EventType;
  file?: string | null;
  language?: string | null;
  payload: Record<string, unknown>;
}
