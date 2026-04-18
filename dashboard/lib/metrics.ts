/**
 * Pure derivation functions over the `DeveloperEvent` stream.
 * Used by `useDerivedMetrics` and the wellness panels. No React, no IO.
 */

import type { DeveloperEvent } from "./types";

// ──────────────────────────────────────────────────────────────────────────────
// Helpers

function payloadNumber(e: DeveloperEvent, key: string): number {
    const v = e.payload?.[key];
    return typeof v === "number" && Number.isFinite(v) ? v : 0;
}

function payloadString(e: DeveloperEvent, key: string): string | undefined {
    const v = e.payload?.[key];
    return typeof v === "string" ? v : undefined;
}

// ──────────────────────────────────────────────────────────────────────────────
// Flow-state buckets

export interface FlowBin {
    ts: number;
    edit: number;
    undo: number;
    test_pass: number;
    test_fail: number;
    stuck: number;
}

export interface FlowSeries {
    startTs: number;
    endTs: number;
    bins: FlowBin[];
}

/**
 * Bucketise events into fixed-width time bins for the FlowStateChart.
 * Default: 5-minute bins covering the last ~2 hours (24 bins).
 */
export function bucketize(
    events: DeveloperEvent[],
    windowMs = 5 * 60 * 1000,
    buckets = 24,
    now: number = Date.now(),
): FlowSeries {
    const endTs = now;
    const startTs = endTs - buckets * windowMs;
    const bins: FlowBin[] = Array.from({ length: buckets }, (_, i) => ({
        ts: startTs + i * windowMs,
        edit: 0,
        undo: 0,
        test_pass: 0,
        test_fail: 0,
        stuck: 0,
    }));
    for (const e of events) {
        if (e.ts < startTs || e.ts > endTs) continue;
        const idx = Math.min(buckets - 1, Math.floor((e.ts - startTs) / windowMs));
        const bin = bins[idx];
        switch (e.type) {
            case "EDIT":
                bin.edit += 1;
                break;
            case "UNDO":
                bin.undo += 1;
                break;
            case "STUCK_DETECTED":
                bin.stuck += 1;
                break;
            case "TEST_RUN": {
                const outcome = payloadString(e, "outcome");
                if (outcome === "pass") bin.test_pass += 1;
                else bin.test_fail += 1;
                break;
            }
        }
    }
    return { startTs, endTs, bins };
}

// ──────────────────────────────────────────────────────────────────────────────
// 24-hour heatmap (single rolling day)

export interface HeatmapCell {
    hour: number; // 0..23 local
    edit: number;
    stuck: number;
    intensity: number; // 0..1
}

export function heatmapBuckets(
    events: DeveloperEvent[],
    now: number = Date.now(),
): HeatmapCell[] {
    const cells: HeatmapCell[] = Array.from({ length: 24 }, (_, h) => ({
        hour: h,
        edit: 0,
        stuck: 0,
        intensity: 0,
    }));
    const cutoff = now - 24 * 60 * 60 * 1000;
    for (const e of events) {
        if (e.ts < cutoff) continue;
        const hour = new Date(e.ts).getHours();
        if (e.type === "EDIT") cells[hour].edit += 1;
        else if (e.type === "STUCK_DETECTED") cells[hour].stuck += 1;
    }
    const max = Math.max(1, ...cells.map((c) => c.edit));
    for (const c of cells) c.intensity = c.edit / max;
    return cells;
}

// ──────────────────────────────────────────────────────────────────────────────
// Anti-patterns rollup

export interface AntiPatternCount {
    rule: string;
    count: number;
    recent: number; // last 15 min
    delta: number; // recent - previous15min
    lastTs: number;
}

const KNOWN_RULES = [
    "undo_burst",
    "edit_thrash",
    "repeated_failing_test",
    "long_idle_on_error",
    "rapid_file_switching",
];

export function antiPatternCounts(
    events: DeveloperEvent[],
    now: number = Date.now(),
): AntiPatternCount[] {
    const rec: Record<string, AntiPatternCount> = {};
    for (const r of KNOWN_RULES) {
        rec[r] = { rule: r, count: 0, recent: 0, delta: 0, lastTs: 0 };
    }
    const win = 15 * 60 * 1000;
    for (const e of events) {
        if (e.type !== "STUCK_DETECTED") continue;
        const rule = payloadString(e, "rule") ?? "unknown";
        const bucket =
            rec[rule] ?? (rec[rule] = { rule, count: 0, recent: 0, delta: 0, lastTs: 0 });
        bucket.count += 1;
        if (e.ts > bucket.lastTs) bucket.lastTs = e.ts;
        if (now - e.ts <= win) bucket.recent += 1;
        else if (now - e.ts <= 2 * win) bucket.delta -= 1; // subtract previous-window baseline
    }
    for (const b of Object.values(rec)) {
        b.delta = b.recent + b.delta; // recent minus previous
    }
    return Object.values(rec)
        .filter((b) => b.count > 0 || KNOWN_RULES.includes(b.rule))
        .sort((a, b) => b.count - a.count);
}

// ──────────────────────────────────────────────────────────────────────────────
// Stuck moments joined with interventions

export type InterventionOutcome = "accepted" | "dismissed" | "none";

export interface StuckMoment {
    event_id: string;
    ts: number;
    file: string;
    rule: string;
    confidence: number;
    interventionOutcome: InterventionOutcome;
    followUpQuestion?: string;
}

/**
 * Join each `STUCK_DETECTED` with nearby INTERVENTION_* events (same session,
 * within 2 minutes). Naive O(n·k) but n is bounded (≤2000) and k is tiny.
 */
export function stuckMoments(events: DeveloperEvent[]): StuckMoment[] {
    const window = 2 * 60 * 1000;
    const stucks = events.filter((e) => e.type === "STUCK_DETECTED");
    const shown = events.filter((e) => e.type === "INTERVENTION_SHOWN");
    const accepted = events.filter((e) => e.type === "INTERVENTION_ACCEPTED");
    const dismissed = events.filter((e) => e.type === "INTERVENTION_DISMISSED");
    return stucks
        .map<StuckMoment>((s) => {
            const nearby = (list: DeveloperEvent[]) =>
                list.find(
                    (x) =>
                        x.session_id === s.session_id &&
                        x.ts >= s.ts &&
                        x.ts - s.ts <= window,
                );
            let outcome: InterventionOutcome = "none";
            if (nearby(accepted)) outcome = "accepted";
            else if (nearby(dismissed)) outcome = "dismissed";
            const shownEv = nearby(shown);
            const question =
                payloadString(shownEv ?? ({} as DeveloperEvent), "question") ??
                payloadString(s, "question");
            return {
                event_id: s.event_id,
                ts: s.ts,
                file: s.file ?? "—",
                rule: payloadString(s, "rule") ?? "unknown",
                confidence: payloadNumber(s, "confidence"),
                interventionOutcome: outcome,
                followUpQuestion: question,
            };
        })
        .sort((a, b) => b.ts - a.ts);
}

// ──────────────────────────────────────────────────────────────────────────────
// Wellness score

export interface WellnessResult {
    score: number; // 0..100
    label: "Flowing" | "Focused" | "Working" | "Struggling" | "Stuck";
    breakdown: {
        stuckPenalty: number;
        undoPenalty: number;
        interventionBonus: number;
        testBonus: number;
    };
}

function labelFor(score: number): WellnessResult["label"] {
    if (score >= 90) return "Flowing";
    if (score >= 70) return "Focused";
    if (score >= 50) return "Working";
    if (score >= 30) return "Struggling";
    return "Stuck";
}

export function wellnessScore(
    events: DeveloperEvent[],
    windowMs = 60 * 60 * 1000,
    now: number = Date.now(),
): WellnessResult {
    const cutoff = now - windowMs;
    const recent = events.filter((e) => e.ts >= cutoff);

    const stuckCount = recent.filter((e) => e.type === "STUCK_DETECTED").length;
    const stuckPenalty = Math.min(40, stuckCount * 8);

    // Undo clusters: ≥3 UNDOs within any 30s window.
    const undos = recent.filter((e) => e.type === "UNDO").sort((a, b) => a.ts - b.ts);
    let clusters = 0;
    for (let i = 0; i < undos.length; i++) {
        let j = i;
        while (j < undos.length && undos[j].ts - undos[i].ts <= 30_000) j++;
        if (j - i >= 3) {
            clusters += 1;
            i = j - 1;
        }
    }
    const undoPenalty = Math.min(15, clusters * 3);

    const shown = recent.filter((e) => e.type === "INTERVENTION_SHOWN").length;
    const accepted = recent.filter((e) => e.type === "INTERVENTION_ACCEPTED").length;
    const acceptRate = shown > 0 ? accepted / shown : 0;
    const interventionBonus = acceptRate > 0.5 ? 5 : 0;

    const passes = recent.filter(
        (e) => e.type === "TEST_RUN" && payloadString(e, "outcome") === "pass",
    ).length;
    const testBonus = Math.min(10, passes * 0.2);

    const raw = 100 - stuckPenalty - undoPenalty + interventionBonus + testBonus;
    const score = Math.max(0, Math.min(100, Math.round(raw)));
    return {
        score,
        label: labelFor(score),
        breakdown: { stuckPenalty, undoPenalty, interventionBonus, testBonus },
    };
}

// ──────────────────────────────────────────────────────────────────────────────
// Misc rollups

export interface FileHit {
    file: string;
    edits: number;
}

export function topFiles(events: DeveloperEvent[], limit = 5): FileHit[] {
    const map = new Map<string, number>();
    for (const e of events) {
        if (e.type !== "EDIT" || !e.file) continue;
        map.set(e.file, (map.get(e.file) ?? 0) + 1);
    }
    return [...map.entries()]
        .map(([file, edits]) => ({ file, edits }))
        .sort((a, b) => b.edits - a.edits)
        .slice(0, limit);
}

/** Events-per-minute over the last 5 min. */
export function eventRatePerMinute(events: DeveloperEvent[], now: number = Date.now()): number {
    const cutoff = now - 5 * 60 * 1000;
    const n = events.filter((e) => e.ts >= cutoff).length;
    return Math.round((n / 5) * 10) / 10;
}

export function uniqueSessions(events: DeveloperEvent[]): string[] {
    return [...new Set(events.map((e) => e.session_id).filter(Boolean))];
}
