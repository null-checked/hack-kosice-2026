/**
 * Wire types mirroring the backend (see `docs/ARCHITECTURE.md` §3–§4) and
 * the plugin's `DeveloperEvent` / `EventType` models. Kept deliberately
 * permissive on `payload` so newer fields from the plugin don't break the UI.
 */

export type EventType =
  | "EDIT"
  | "UNDO"
  | "REDO"
  | "TEST_RUN"
  | "FILE_FOCUS"
  | "CARET_DWELL"
  | "STUCK_DETECTED"
  | "INTERVENTION_SHOWN"
  | "INTERVENTION_ACCEPTED"
  | "INTERVENTION_DISMISSED"
  | "INSPECTION";

export const ALL_EVENT_TYPES: readonly EventType[] = [
  "EDIT",
  "UNDO",
  "REDO",
  "TEST_RUN",
  "FILE_FOCUS",
  "CARET_DWELL",
  "STUCK_DETECTED",
  "INTERVENTION_SHOWN",
  "INTERVENTION_ACCEPTED",
  "INTERVENTION_DISMISSED",
  "INSPECTION",
] as const;

/** Free-form payload blob; individual components narrow with safe accessors. */
export type EventPayload = Record<string, unknown>;

export interface DeveloperEvent {
  event_id: string;
  session_id: string;
  /** Epoch millis. */
  ts: number;
  type: EventType | string;
  file?: string | null;
  language?: string | null;
  payload?: EventPayload;
}

/** Known `STUCK_DETECTED.payload.rule` values (see architecture §5). */
export type StuckRule =
  | "undo_burst"
  | "edit_thrash"
  | "repeated_failing_test"
  | "long_idle_on_error"
  | "rapid_file_switching"
  | string;

export type WSFrameType =
  | "hello"
  | "ack"
  | "event"
  | "broadcast"
  | "ping"
  | "pong"
  | "error";

export interface WSEnvelope<P = unknown> {
  type: WSFrameType;
  timestamp: number;
  payload: P;
}

/** Initial snapshot payload the backend pushes on dashboard connect. */
export interface SnapshotPayload {
  kind: "snapshot";
  session_id: string;
  events: DeveloperEvent[];
}

/** Live-event fan-out payload. */
export interface LiveEventPayload {
  kind: "event";
  session_id?: string;
  event: DeveloperEvent;
}

export type BroadcastPayload = SnapshotPayload | LiveEventPayload;

export type SocketStatus = "connecting" | "open" | "reconnecting" | "closed";

export interface BackendHealth {
  status: string;
  llm_provider?: string;
  llm_model?: string;
  llm_available?: boolean;
}

/**
 * Chat DTOs mirroring `backend/src/models/api.py` and the Kotlin plugin.
 * The dashboard does not render chat edits in v1; types live here for
 * completeness and to keep the three-tier DTO contract in sync.
 */

/** A file @-mentioned by the user for additional chat context. */
export interface ContextFile {
  path: string;
  content: string;
}

/**
 * A structured code edit proposed by the assistant in a chat response.
 * `file_path` is redacted (project-relative); `diff_unified` is computed
 * backend-side via difflib. `start_line`/`end_line` are 1-based, inclusive;
 * both null means the edit targets the whole file.
 */
export interface CodeEdit {
  file_path: string;
  original_code: string;
  proposed_code: string;
  explanation?: string;
  start_line?: number | null;
  end_line?: number | null;
  diff_unified?: string;
}

/** Single turn in the /code-chat conversation. */
export interface ChatTurn {
  role: "user" | "assistant";
  text: string;
  /** Assistant-side structured edits; empty for user turns. */
  edits?: CodeEdit[];
}

export interface CodeChatRequest {
  conversation?: ChatTurn[];
  current_code?: string;
  file_path?: string | null;
  language?: string;
  /** Files @-mentioned by the user for additional context. */
  context_files?: ContextFile[];
  /** When true, the system prompt asks the LLM for structured edits. */
  allow_edits?: boolean;
}

export interface CodeChatResponse {
  answer: string;
  model?: string;
  /** Structured edits proposed by the assistant (empty when `allow_edits` was false). */
  edits?: CodeEdit[];
}
