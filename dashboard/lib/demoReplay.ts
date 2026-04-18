/**
 * Synthetic 2-minute event stream used when `NEXT_PUBLIC_DEMO_REPLAY=1`
 * and the judge clicks "Demo replay" before any real IDE events arrive.
 *
 * The shape is identical to what the plugin would emit so every panel renders
 * authentically. All timestamps are relative to `Date.now()` at generation time.
 */

import type { DeveloperEvent, EventPayload } from "./types";

let counter = 0;
function uid(prefix: string): string {
    counter += 1;
    return `${prefix}-${counter.toString(36)}-${Math.random().toString(36).slice(2, 7)}`;
}

function mk(
    sessionId: string,
    tsOffsetMs: number,
    type: DeveloperEvent["type"],
    file: string | null,
    payload: EventPayload,
    now: number,
): DeveloperEvent {
    return {
        event_id: uid("demo"),
        session_id: sessionId,
        ts: now + tsOffsetMs,
        type,
        file,
        language: file?.endsWith(".py") ? "python" : file?.endsWith(".kt") ? "kotlin" : null,
        payload,
    };
}

/**
 * Build ~120 seconds of plausible activity leading to a "stuck" moment
 * and an accepted Socratic intervention — mirrors `docs/DEMO_SCRIPT.md`.
 */
export function generateDemoReplay(now: number = Date.now()): DeveloperEvent[] {
    counter = 0;
    const sid = "demo-session-a1b2";
    const file = "src/fizz.py";
    const events: DeveloperEvent[] = [];

    // t-120s → t-90s: warm-up edits
    for (let i = 0; i < 18; i++) {
        events.push(
            mk(sid, -120_000 + i * 1_800, "EDIT", file, {
                chars_added: 8 + (i % 5),
                chars_removed: i % 3,
                line: 12 + (i % 6),
            }, now),
        );
    }

    // t-90s: first test run — fail
    events.push(
        mk(sid, -90_000, "TEST_RUN", "tests/test_fizz.py", {
            test_name: "test_fizzbuzz_15",
            outcome: "fail",
            duration_ms: 132,
            failure_signature: "AssertionError: expected 'Fizz' got '3'",
        }, now),
    );

    // t-88s → t-70s: edit burst with undo thrash
    for (let i = 0; i < 6; i++) {
        events.push(
            mk(sid, -88_000 + i * 3_000, "EDIT", file, {
                chars_added: 12,
                chars_removed: 10,
                line: 42,
            }, now),
        );
    }
    for (let i = 0; i < 5; i++) {
        events.push(
            mk(sid, -75_000 + i * 4_000, "UNDO", file, { line: 42 }, now),
        );
    }

    // t-60s: second test fail (same signature → repeated_failing_test-ish)
    events.push(
        mk(sid, -60_000, "TEST_RUN", "tests/test_fizz.py", {
            test_name: "test_fizzbuzz_15",
            outcome: "fail",
            duration_ms: 140,
            failure_signature: "AssertionError: expected 'Fizz' got '3'",
        }, now),
    );

    // t-55s: another undo burst cluster
    for (let i = 0; i < 4; i++) {
        events.push(
            mk(sid, -55_000 + i * 5_000, "UNDO", file, { line: 42 }, now),
        );
    }

    // t-40s: STUCK_DETECTED — undo_burst
    const stuckId = uid("stuck");
    events.push({
        event_id: stuckId,
        session_id: sid,
        ts: now - 40_000,
        type: "STUCK_DETECTED",
        file,
        language: "python",
        payload: { rule: "undo_burst", confidence: 0.9, window_events: 9 },
    });

    // t-38s: INTERVENTION_SHOWN — Socratic question
    const interventionId = uid("interv");
    events.push({
        event_id: uid("demo"),
        session_id: sid,
        ts: now - 38_000,
        type: "INTERVENTION_SHOWN",
        file,
        language: "python",
        payload: {
            intervention_id: interventionId,
            kind: "socratic",
            question:
                "You've rewritten this loop 4 times. What invariant are you trying to preserve?",
            follow_ups: [
                "What is the loop's termination condition supposed to guarantee?",
                "Which inputs have you actually tested against?",
            ],
        },
    });

    // t-30s: INTERVENTION_ACCEPTED
    events.push({
        event_id: uid("demo"),
        session_id: sid,
        ts: now - 30_000,
        type: "INTERVENTION_ACCEPTED",
        file,
        language: "python",
        payload: { intervention_id: interventionId, kind: "socratic" },
    });

    // t-25s → t-5s: calm, passing tests
    for (let i = 0; i < 10; i++) {
        events.push(
            mk(sid, -25_000 + i * 2_200, "EDIT", file, {
                chars_added: 3,
                chars_removed: 1,
                line: 44 + (i % 3),
            }, now),
        );
    }
    events.push(
        mk(sid, -4_000, "TEST_RUN", "tests/test_fizz.py", {
            test_name: "test_fizzbuzz_15",
            outcome: "pass",
            duration_ms: 92,
        }, now),
    );
    events.push(
        mk(sid, -2_000, "TEST_RUN", "tests/test_fizz.py", {
            test_name: "test_fizzbuzz_3",
            outcome: "pass",
            duration_ms: 78,
        }, now),
    );

    return events.sort((a, b) => a.ts - b.ts);
}
