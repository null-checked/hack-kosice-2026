import { randomUUID } from 'node:crypto';
import type { DeveloperEvent } from '../model/events';

/**
 * Per-extension-launch session state. Mirrors
 * [`SessionContext.kt`](../../../plugin/src/main/kotlin/com/rubberduck/live/detector/SessionContext.kt:1).
 */
class SessionContextImpl {
  readonly sessionId: string = randomUUID();
  activeFile: string | null = null;
  lastTestFailureSignature: string | null = null;
  /** Wellness score derived from STUCK_DETECTED density. Updated by ui/statusBarItem. */
  wellnessScore = 100;

  private readonly recentEvents: DeveloperEvent[] = [];
  private readonly maxRecent = 50;

  record(event: DeveloperEvent): void {
    this.recentEvents.push(event);
    while (this.recentEvents.length > this.maxRecent) this.recentEvents.shift();
  }

  recent(n: number): DeveloperEvent[] {
    if (this.recentEvents.length <= n) return [...this.recentEvents];
    return this.recentEvents.slice(this.recentEvents.length - n);
  }
}

let _instance: SessionContextImpl | undefined;

export const SessionContext = {
  getInstance(): SessionContextImpl {
    if (!_instance) _instance = new SessionContextImpl();
    return _instance;
  },
};
