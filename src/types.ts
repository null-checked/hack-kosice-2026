export type MetricsEventType =
  | 'session-start'
  | 'session-end'
  | 'document-open'
  | 'document-close'
  | 'text-change'
  | 'cursor-selection';

export interface DocumentRef {
  uri: string;
  languageId: string;
  version: number;
  isUntitled: boolean;
}

export interface MetricsEvent {
  schemaVersion: 1;
  eventId: string;
  sessionId: string;
  timestamp: string;
  eventType: MetricsEventType;
  workspaceName: string | null;
  document: DocumentRef | null;
  payload: Record<string, unknown>;
}
