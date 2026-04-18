import { EventEmitter } from 'node:events';
import type { DeveloperEvent } from '../model/events';

/**
 * In-process pub/sub for [DeveloperEvent]s. Mirrors
 * [`EventBus.kt`](../../../plugin/src/main/kotlin/com/rubberduck/live/detector/EventBus.kt:1):
 * all producers (listeners, detector, commands) publish; all consumers
 * (WS client, SQLite log, tree views, detector) subscribe.
 */
class EventBusImpl {
  private readonly emitter = new EventEmitter();

  constructor() {
    // Generous cap — we have ~6 listeners; defaults to 10 which is fine, but
    // bumping prevents Node from warning under churn.
    this.emitter.setMaxListeners(64);
  }

  publish(event: DeveloperEvent): void {
    this.emitter.emit('event', event);
  }

  subscribe(handler: (event: DeveloperEvent) => void): () => void {
    this.emitter.on('event', handler);
    return () => this.emitter.off('event', handler);
  }
}

let _instance: EventBusImpl | undefined;

export const EventBus = {
  getInstance(): EventBusImpl {
    if (!_instance) _instance = new EventBusImpl();
    return _instance;
  },
};
