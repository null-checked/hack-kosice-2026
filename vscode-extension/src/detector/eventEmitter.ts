import { randomUUID } from 'node:crypto';
import type { DeveloperEvent, EventType } from '../model/events';
import { EventBus } from './eventBus';
import { SessionContext } from './sessionContext';
import { EventLog } from '../storage/eventLog';

export interface EmitOptions {
  type: EventType;
  file?: string | null;
  language?: string | null;
  payload?: Record<string, unknown>;
}

/**
 * Shared factory for constructing & publishing [DeveloperEvent]s.
 * Same shape as the plugin's `EventEmitter` Kotlin object.
 */
export function emit(opts: EmitOptions): DeveloperEvent {
  const ctx = SessionContext.getInstance();
  const ev: DeveloperEvent = {
    event_id: randomUUID(),
    session_id: ctx.sessionId,
    ts: Date.now(),
    type: opts.type,
    file: opts.file ?? null,
    language: opts.language ?? null,
    payload: opts.payload ?? {},
  };
  ctx.record(ev);
  EventBus.getInstance().publish(ev);
  // Persist asynchronously; failures are logged inside EventLog and never thrown.
  void EventLog.getInstance().append(ev);
  return ev;
}
