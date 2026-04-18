"use client";

/**
 * Memoised derivation of dashboard metrics from the live event stream.
 *
 * Recomputes only when the event list changes by length or tail timestamp,
 * and throttles to at most once per ~500 ms so a burst of events doesn't
 * thrash the UI. The returned object is deeply readonly in spirit — treat
 * values as immutable.
 */

import { useEffect, useMemo, useRef, useState } from "react";

import {
    antiPatternCounts,
    bucketize,
    eventRatePerMinute,
    heatmapBuckets,
    stuckMoments,
    topFiles,
    wellnessScore,
    type AntiPatternCount,
    type FileHit,
    type FlowSeries,
    type HeatmapCell,
    type StuckMoment,
    type WellnessResult,
} from "@/lib/metrics";
import type { DeveloperEvent } from "@/lib/types";

export interface DerivedMetrics {
    wellness: WellnessResult;
    heatmap: HeatmapCell[];
    flow: FlowSeries;
    antiPatterns: AntiPatternCount[];
    stuckMoments: StuckMoment[];
    topFiles: FileHit[];
    eventRate: number;
    computedAt: number;
}

const THROTTLE_MS = 500;

export function useDerivedMetrics(events: DeveloperEvent[]): DerivedMetrics {
    const signature = useMemo(() => {
        const tail = events.length > 0 ? events[events.length - 1].ts : 0;
        return `${events.length}:${tail}`;
    }, [events]);

    const [tick, setTick] = useState(0);
    const lastComputeRef = useRef(0);
    const pendingRef = useRef<ReturnType<typeof setTimeout> | null>(null);

    useEffect(() => {
        const now = Date.now();
        const elapsed = now - lastComputeRef.current;
        if (elapsed >= THROTTLE_MS) {
            lastComputeRef.current = now;
            setTick((t) => t + 1);
        } else if (!pendingRef.current) {
            pendingRef.current = setTimeout(() => {
                lastComputeRef.current = Date.now();
                pendingRef.current = null;
                setTick((t) => t + 1);
            }, THROTTLE_MS - elapsed);
        }
        return () => {
            if (pendingRef.current) {
                clearTimeout(pendingRef.current);
                pendingRef.current = null;
            }
        };
    }, [signature]);

    return useMemo<DerivedMetrics>(() => {
        const now = Date.now();
        return {
            wellness: wellnessScore(events, 60 * 60 * 1000, now),
            heatmap: heatmapBuckets(events, now),
            flow: bucketize(events, 5 * 60 * 1000, 24, now),
            antiPatterns: antiPatternCounts(events, now),
            stuckMoments: stuckMoments(events),
            topFiles: topFiles(events, 5),
            eventRate: eventRatePerMinute(events, now),
            computedAt: now,
        };
        // `tick` is intentionally part of the dependency list so throttled
        // recomputes run; `signature` keeps us in sync with the event stream.
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [signature, tick]);
}
