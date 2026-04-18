import { describe, expect, it, beforeEach } from 'vitest';
import { StuckDetector } from '../src/detector/stuckDetector';
import type { DeveloperEvent, EventType } from '../src/model/events';

function ev(
  type: EventType,
  ts: number,
  file: string | null,
  payload: Record<string, unknown> = {},
): DeveloperEvent {
  return {
    event_id: `${type}-${ts}-${Math.random()}`,
    session_id: 'test-session',
    ts,
    type,
    file,
    language: null,
    payload,
  };
}

describe('StuckDetector', () => {
  let detector: StuckDetector;
  let fires: Array<{ rule: string; file: string | null; n: number; conf: number }>;

  beforeEach(() => {
    detector = new StuckDetector();
    fires = [];
    detector.testSink = (rule, file, n, conf) => fires.push({ rule, file, n, conf });
  });

  it('undo_burst fires after 4 UNDOs in 30s on the same file', () => {
    for (let i = 0; i < 4; i += 1) detector.ingest(ev('UNDO', 1000 + i * 1000, 'src/foo.py'));
    expect(fires.find((f) => f.rule === 'undo_burst')).toBeDefined();
    expect(fires[0]?.conf).toBe(0.9);
  });

  it('undo_burst does NOT fire when UNDOs are spread > 30s', () => {
    for (let i = 0; i < 4; i += 1) detector.ingest(ev('UNDO', i * 60_000, 'src/foo.py'));
    expect(fires.find((f) => f.rule === 'undo_burst')).toBeUndefined();
  });

  it('repeated_failing_test fires when same test fails twice within 5 min', () => {
    detector.ingest(ev('TEST_RUN', 1000, 'src/foo.py', { test_name: 'test_x', outcome: 'fail' }));
    detector.ingest(ev('TEST_RUN', 2000, 'src/foo.py', { test_name: 'test_x', outcome: 'fail' }));
    expect(fires.find((f) => f.rule === 'repeated_failing_test')).toBeDefined();
  });

  it('long_idle_on_error fires when caret dwells ≥ 90s with error', () => {
    detector.ingest(ev('CARET_DWELL', 1000, 'src/foo.py', { duration_ms: 95_000, has_error_marker: true }));
    expect(fires.find((f) => f.rule === 'long_idle_on_error')).toBeDefined();
  });

  it('long_idle_on_error does NOT fire when there is no error marker', () => {
    detector.ingest(ev('CARET_DWELL', 1000, 'src/foo.py', { duration_ms: 95_000, has_error_marker: false }));
    expect(fires.find((f) => f.rule === 'long_idle_on_error')).toBeUndefined();
  });

  it('rapid_file_switching fires across 6+ events / 4+ files / avg gap < 8s', () => {
    const files = ['a.py', 'b.py', 'c.py', 'd.py', 'e.py', 'f.py'];
    files.forEach((f, i) => detector.ingest(ev('FILE_FOCUS', i * 1000, `src/${f}`)));
    expect(fires.find((f) => f.rule === 'rapid_file_switching')).toBeDefined();
  });

  it('edit_thrash fires after 10 EDITs on same file:line within 20s', () => {
    for (let i = 0; i < 10; i += 1) {
      detector.ingest(ev('EDIT', 1000 + i * 100, 'src/foo.py', { line: 42, chars_added: 1, chars_removed: 0 }));
    }
    expect(fires.find((f) => f.rule === 'edit_thrash')).toBeDefined();
  });

  it('per-rule rate-limit suppresses repeated fires within 60s', () => {
    for (let i = 0; i < 4; i += 1) detector.ingest(ev('UNDO', 1000 + i * 1000, 'src/foo.py'));
    const initial = fires.length;
    // Another burst close in time should NOT fire (rate limit is 60s wall clock).
    for (let i = 0; i < 4; i += 1) detector.ingest(ev('UNDO', 6000 + i * 1000, 'src/foo.py'));
    expect(fires.length).toBe(initial);
  });
});
