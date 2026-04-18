import type { DeveloperEvent } from '../model/events';
import { EventType } from '../model/events';
import { EventBus } from './eventBus';
import { emit } from './eventEmitter';
import { log } from '../util/logger';
import { StuckInterventionCoordinator } from '../intervention/stuckInterventionCoordinator';

/**
 * Heuristic "the developer is stuck" engine — the 5 rules from
 * [ARCHITECTURE.md §5](../../../../docs/ARCHITECTURE.md). Identical thresholds
 * to [`StuckDetector.kt`](../../../plugin/src/main/kotlin/com/rubberduck/live/detector/StuckDetector.kt:1):
 *
 * - `undo_burst`: ≥ 4 UNDO in 30 s, same file, conf 0.9
 * - `repeated_failing_test`: same test name fails ≥ 2 in last 10 within 5 min, conf 0.95
 * - `long_idle_on_error`: CARET_DWELL ≥ 90 s with `has_error_marker=true`, conf 0.8
 * - `rapid_file_switching`: ≥ 6 FILE_FOCUS across ≥ 4 files in 60 s, avg gap < 8 s, conf 0.7
 * - `edit_thrash`: ≥ 10 EDIT events on the same `file:line` within 20 s, conf 0.75
 *
 * Per-rule rate limit: 60 s. Each fire emits a `STUCK_DETECTED` event AND
 * notifies [StuckInterventionCoordinator].
 */
export class StuckDetector {
  private readonly lastFired = new Map<string, number>();
  private readonly undoWindows = new Map<string, number[]>(); // ts list per file
  private readonly testFailures: Array<{ ts: number; testName: string; file: string | null }> = [];
  private readonly focusWindow: Array<{ ts: number; file: string | null }> = [];
  private readonly editWindows = new Map<string, number[]>(); // per file:line

  private unsubscribe?: () => void;
  /** Optional sink for tests; when set, fires go here instead of side-effects. */
  testSink?: (rule: string, file: string | null, windowEvents: number, confidence: number) => void;

  start(): void {
    if (this.unsubscribe) return;
    this.unsubscribe = EventBus.getInstance().subscribe((ev) => this.process(ev));
  }

  stop(): void {
    this.unsubscribe?.();
    this.unsubscribe = undefined;
  }

  /** For tests: feed an event directly and skip the bus. */
  ingest(ev: DeveloperEvent): void {
    this.process(ev);
  }

  private process(ev: DeveloperEvent): void {
    switch (ev.type) {
      case EventType.UNDO: this.onUndo(ev); break;
      case EventType.TEST_RUN: this.onTestRun(ev); break;
      case EventType.CARET_DWELL: this.onCaretDwell(ev); break;
      case EventType.FILE_FOCUS: this.onFileFocus(ev); break;
      case EventType.EDIT: this.onEdit(ev); break;
      default: /* ignore */ break;
    }
  }

  // --- undo_burst --------------------------------------------------------
  private onUndo(ev: DeveloperEvent): void {
    const file = ev.file ?? null;
    if (!file) return;
    const dq = this.undoWindows.get(file) ?? [];
    const now = ev.ts;
    dq.push(now);
    while (dq.length > 0 && now - (dq[0] ?? now) > 30_000) dq.shift();
    this.undoWindows.set(file, dq);
    if (dq.length >= 4) this.fire('undo_burst', file, dq.length, 0.9);
  }

  // --- repeated_failing_test --------------------------------------------
  private onTestRun(ev: DeveloperEvent): void {
    const outcome = ev.payload['outcome'];
    if (outcome !== 'fail' && outcome !== 'error') return;
    const name = ev.payload['test_name'];
    if (typeof name !== 'string' || name.length === 0) return;
    const now = ev.ts;
    this.testFailures.push({ ts: now, testName: name, file: ev.file ?? null });
    while (this.testFailures.length > 0 && now - (this.testFailures[0]?.ts ?? now) > 5 * 60_000) {
      this.testFailures.shift();
    }
    const lastTen = this.testFailures.slice(-10);
    const sameInARow = lastTen.filter((f) => f.testName === name).length;
    if (sameInARow >= 2) this.fire('repeated_failing_test', ev.file ?? null, sameInARow, 0.95);
  }

  // --- long_idle_on_error -----------------------------------------------
  private onCaretDwell(ev: DeveloperEvent): void {
    const dur = numberOf(ev.payload['duration_ms']);
    const hasErr = ev.payload['has_error_marker'] === true;
    if (dur === null) return;
    if (dur >= 90_000 && hasErr) this.fire('long_idle_on_error', ev.file ?? null, 1, 0.8);
  }

  // --- rapid_file_switching ---------------------------------------------
  private onFileFocus(ev: DeveloperEvent): void {
    const now = ev.ts;
    this.focusWindow.push({ ts: now, file: ev.file ?? null });
    while (this.focusWindow.length > 0 && now - (this.focusWindow[0]?.ts ?? now) > 60_000) {
      this.focusWindow.shift();
    }
    if (this.focusWindow.length >= 6) {
      const distinct = new Set(this.focusWindow.map((f) => f.file).filter((f): f is string => !!f)).size;
      let avgGap = 0;
      if (this.focusWindow.length >= 2) {
        let total = 0;
        let count = 0;
        for (let i = 1; i < this.focusWindow.length; i += 1) {
          const a = this.focusWindow[i - 1];
          const b = this.focusWindow[i];
          if (a && b) { total += b.ts - a.ts; count += 1; }
        }
        avgGap = count === 0 ? 0 : total / count;
      }
      if (distinct >= 4 && avgGap < 8000) {
        this.fire('rapid_file_switching', ev.file ?? null, this.focusWindow.length, 0.7);
      }
    }
  }

  // --- edit_thrash -------------------------------------------------------
  private onEdit(ev: DeveloperEvent): void {
    const line = numberOf(ev.payload['line']);
    if (line === null) return;
    const key = `${ev.file ?? '?'}:${line}`;
    const dq = this.editWindows.get(key) ?? [];
    const now = ev.ts;
    dq.push(now);
    while (dq.length > 0 && now - (dq[0] ?? now) > 20_000) dq.shift();
    this.editWindows.set(key, dq);
    if (dq.length >= 10) this.fire('edit_thrash', ev.file ?? null, dq.length, 0.75);
  }

  private fire(rule: string, file: string | null, windowEvents: number, confidence: number): void {
    const now = Date.now();
    const last = this.lastFired.get(rule) ?? 0;
    if (now - last < 60_000) return;
    this.lastFired.set(rule, now);
    log.info(`StuckDetector fire: ${rule} (file=${file ?? '?'}, n=${windowEvents})`);

    if (this.testSink) {
      this.testSink(rule, file, windowEvents, confidence);
      return;
    }

    const ev = emit({
      type: EventType.STUCK_DETECTED,
      file,
      payload: { rule, confidence, window_events: windowEvents },
    });
    // Defer to the next tick so listeners observe the event first.
    setImmediate(() => StuckInterventionCoordinator.getInstance().onStuckDetected(ev, rule));
  }
}

function numberOf(v: unknown): number | null {
  if (typeof v === 'number' && Number.isFinite(v)) return v;
  if (typeof v === 'string') {
    const n = Number(v);
    return Number.isFinite(n) ? n : null;
  }
  return null;
}

let _instance: StuckDetector | undefined;
export function getStuckDetector(): StuckDetector {
  if (!_instance) _instance = new StuckDetector();
  return _instance;
}
