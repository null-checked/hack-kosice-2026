"use client";

/**
 * Dense monospace stream of the most recent events. Auto-scrolls to the
 * newest row unless the user has scrolled up (sticky-bottom pattern).
 */

import { useEffect, useMemo, useRef, useState } from "react";

import type { DeveloperEvent } from "@/lib/types";
import { formatClock, shortenFile } from "@/lib/format";
import { cn } from "@/lib/utils";
import { PanelCard, EmptyState } from "./PanelCard";

export interface EventStreamProps {
    events: DeveloperEvent[];
    limit?: number;
    className?: string;
}

export function EventStream({ events, limit = 50, className }: EventStreamProps) {
    const recent = useMemo(() => events.slice(-limit), [events, limit]);
    const scrollerRef = useRef<HTMLDivElement | null>(null);
    const [stuckToBottom, setStuckToBottom] = useState(true);

    useEffect(() => {
        const el = scrollerRef.current;
        if (!el || !stuckToBottom) return;
        el.scrollTop = el.scrollHeight;
    }, [recent, stuckToBottom]);

    const onScroll = () => {
        const el = scrollerRef.current;
        if (!el) return;
        const nearBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 12;
        setStuckToBottom(nearBottom);
    };

    return (
        <PanelCard
            title="Event stream"
            subtitle={`Last ${Math.min(limit, recent.length)} events`}
            right={
                <span className="font-mono text-[10px] uppercase tracking-widest text-muted-foreground">
                    {stuckToBottom ? "live" : "scroll paused"}
                </span>
            }
            className={className}
            bodyClassName="min-h-[180px]"
        >
            {recent.length === 0 ? (
                <EmptyState message="No events yet — start editing in the IDE." />
            ) : (
                <div
                    ref={scrollerRef}
                    onScroll={onScroll}
                    className="h-[180px] overflow-y-auto rounded-lg border border-white/5 bg-black/30 p-2 font-mono text-[11px] leading-5"
                >
                    {recent.map((e) => (
                        <Row key={e.event_id} event={e} />
                    ))}
                </div>
            )}
        </PanelCard>
    );
}

function Row({ event }: { event: DeveloperEvent }) {
    return (
        <div className="grid grid-cols-[60px_130px_1fr_auto] gap-2 whitespace-nowrap">
            <span className="text-muted-foreground tabular-nums">{formatClock(event.ts)}</span>
            <TypeBadge type={event.type} />
            <span className="truncate text-foreground/80">{shortenFile(event.file)}</span>
            <span className="truncate text-right text-muted-foreground">{summarise(event)}</span>
        </div>
    );
}

function TypeBadge({ type }: { type: string }) {
    return (
        <span
            className={cn(
                "inline-flex items-center rounded-sm px-1.5 text-[10px] font-semibold uppercase tracking-wider",
                typeColor(type),
            )}
        >
            {type}
        </span>
    );
}

function typeColor(type: string): string {
    switch (type) {
        case "EDIT":
            return "bg-cyan-500/15 text-cyan-300";
        case "UNDO":
        case "REDO":
            return "bg-amber-500/15 text-amber-300";
        case "STUCK_DETECTED":
            return "bg-rose-500/15 text-rose-300";
        case "TEST_RUN":
            return "bg-violet-500/15 text-violet-200";
        case "INTERVENTION_SHOWN":
        case "INTERVENTION_ACCEPTED":
        case "INTERVENTION_DISMISSED":
            return "bg-violet-500/10 text-violet-200";
        default:
            return "bg-white/5 text-muted-foreground";
    }
}

function summarise(e: DeveloperEvent): string {
    const p = e.payload ?? {};
    if (e.type === "EDIT") {
        const added = typeof p.chars_added === "number" ? p.chars_added : 0;
        const removed = typeof p.chars_removed === "number" ? p.chars_removed : 0;
        const line = typeof p.line === "number" ? p.line : "?";
        return `+${added}/-${removed} @${line}`;
    }
    if (e.type === "TEST_RUN") {
        const outcome = typeof p.outcome === "string" ? p.outcome : "?";
        const name = typeof p.test_name === "string" ? p.test_name : "";
        return `${outcome} · ${name}`;
    }
    if (e.type === "STUCK_DETECTED") {
        const rule = typeof p.rule === "string" ? p.rule : "?";
        const conf = typeof p.confidence === "number" ? p.confidence.toFixed(2) : "?";
        return `${rule} · ${conf}`;
    }
    if (e.type === "UNDO" || e.type === "REDO") {
        const line = typeof p.line === "number" ? p.line : "?";
        return `line ${line}`;
    }
    if (e.type.startsWith("INTERVENTION_")) {
        const kind = typeof p.kind === "string" ? p.kind : "";
        return kind;
    }
    return "";
}
