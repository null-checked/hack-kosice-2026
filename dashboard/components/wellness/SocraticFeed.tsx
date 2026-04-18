"use client";

/**
 * Chat-bubble feed of the most recent Socratic interactions.
 * Sources: INTERVENTION_SHOWN payloads carrying `question` + `follow_ups`.
 */

import { useMemo } from "react";

import type { DeveloperEvent } from "@/lib/types";
import { formatRelative } from "@/lib/format";
import { PanelCard, EmptyState } from "./PanelCard";

export interface SocraticFeedProps {
    events: DeveloperEvent[];
    limit?: number;
    className?: string;
}

interface SocraticEntry {
    id: string;
    ts: number;
    question: string;
    followUps: string[];
    outcome: "accepted" | "dismissed" | "pending";
}

export function SocraticFeed({ events, limit = 5, className }: SocraticFeedProps) {
    const entries = useMemo(() => deriveEntries(events).slice(0, limit), [events, limit]);
    return (
        <PanelCard
            title="Socratic coach"
            subtitle="Latest gentle nudges"
            className={className}
            bodyClassName="overflow-y-auto pr-1"
        >
            {entries.length === 0 ? (
                <EmptyState message="No Socratic questions yet." />
            ) : (
                <ul className="flex flex-col gap-3">
                    {entries.map((e) => (
                        <li key={e.id} className="flex items-start gap-3">
                            <span
                                className="flex h-8 w-8 shrink-0 items-center justify-center rounded-full bg-white/5 text-lg"
                                aria-hidden
                            >
                                🦆
                            </span>
                            <div className="min-w-0 flex-1">
                                <div className="rounded-2xl rounded-tl-sm bg-white/5 px-3 py-2 text-sm text-foreground">
                                    {e.question}
                                </div>
                                {e.followUps.length > 0 && (
                                    <div className="mt-1 flex flex-wrap gap-1">
                                        {e.followUps.map((f, i) => (
                                            <span
                                                key={i}
                                                className="rounded-full bg-violet-500/10 px-2 py-0.5 text-[11px] text-violet-200"
                                            >
                                                {f}
                                            </span>
                                        ))}
                                    </div>
                                )}
                                <div className="mt-1 flex items-center gap-2 text-[11px] text-muted-foreground">
                                    <span>{formatRelative(e.ts)}</span>
                                    <span aria-hidden>·</span>
                                    <OutcomeTag outcome={e.outcome} />
                                </div>
                            </div>
                        </li>
                    ))}
                </ul>
            )}
        </PanelCard>
    );
}

function deriveEntries(events: DeveloperEvent[]): SocraticEntry[] {
    const shown = events.filter(
        (e) => e.type === "INTERVENTION_SHOWN" && (e.payload?.kind ?? "socratic") === "socratic",
    );
    const accepted = new Set(
        events
            .filter((e) => e.type === "INTERVENTION_ACCEPTED")
            .map((e) => String(e.payload?.intervention_id ?? "")),
    );
    const dismissed = new Set(
        events
            .filter((e) => e.type === "INTERVENTION_DISMISSED")
            .map((e) => String(e.payload?.intervention_id ?? "")),
    );

    return shown
        .map<SocraticEntry>((e) => {
            const id = String(e.payload?.intervention_id ?? e.event_id);
            const question = typeof e.payload?.question === "string" ? e.payload.question : "…";
            const follows = Array.isArray(e.payload?.follow_ups)
                ? (e.payload.follow_ups as unknown[]).filter((x): x is string => typeof x === "string")
                : [];
            const outcome: SocraticEntry["outcome"] = accepted.has(id)
                ? "accepted"
                : dismissed.has(id)
                  ? "dismissed"
                  : "pending";
            return { id: e.event_id, ts: e.ts, question, followUps: follows, outcome };
        })
        .sort((a, b) => b.ts - a.ts);
}

function OutcomeTag({ outcome }: { outcome: SocraticEntry["outcome"] }) {
    if (outcome === "accepted")
        return <span className="text-cyan-300">Accepted ✓</span>;
    if (outcome === "dismissed") return <span className="text-rose-300">Dismissed ✕</span>;
    return <span className="text-amber-300">Pending…</span>;
}
