"use client";

/**
 * Vertical timeline of recent stuck moments, newest first.
 * Joins STUCK_DETECTED with the follow-up INTERVENTION_* events so each entry
 * tells a short story: rule → file → outcome.
 */

import type { StuckMoment } from "@/lib/metrics";
import { formatRelative, humanizeRule, shortenFile } from "@/lib/format";
import { cn } from "@/lib/utils";
import { PanelCard, EmptyState } from "./PanelCard";

export interface StuckMomentsTimelineProps {
    moments: StuckMoment[];
    limit?: number;
    className?: string;
}

export function StuckMomentsTimeline({ moments, limit = 10, className }: StuckMomentsTimelineProps) {
    const items = moments.slice(0, limit);
    return (
        <PanelCard
            title="Stuck moments"
            subtitle={items.length ? `${items.length} recent` : "Recent interventions"}
            className={className}
            bodyClassName="overflow-y-auto pr-1"
        >
            {items.length === 0 ? (
                <EmptyState message="No stuck moments yet — you're flowing." />
            ) : (
                <ol className="relative flex flex-col gap-3">
                    <span
                        className="absolute left-[7px] top-1 bottom-1 w-px bg-gradient-to-b from-violet-400/40 via-white/10 to-transparent"
                        aria-hidden
                    />
                    {items.map((m) => (
                        <li key={m.event_id} className="relative pl-6">
                            <span
                                className={cn(
                                    "absolute left-0 top-1 h-3 w-3 rounded-full ring-2 ring-[#0b0f17]",
                                    outcomeDot(m.interventionOutcome),
                                )}
                            />
                            <div className="flex flex-wrap items-baseline gap-2">
                                <span className="text-xs text-muted-foreground tabular-nums">
                                    {formatRelative(m.ts)}
                                </span>
                                <RuleBadge rule={m.rule} />
                                <span className="truncate text-xs text-foreground/80">
                                    {shortenFile(m.file)}
                                </span>
                                <OutcomePill outcome={m.interventionOutcome} />
                            </div>
                            {m.followUpQuestion && m.interventionOutcome === "accepted" && (
                                <p className="mt-1 text-xs italic text-foreground/80">
                                    &ldquo;{m.followUpQuestion}&rdquo;
                                </p>
                            )}
                        </li>
                    ))}
                </ol>
            )}
        </PanelCard>
    );
}

function RuleBadge({ rule }: { rule: string }) {
    return (
        <span className="rounded-full bg-violet-500/15 px-2 py-0.5 text-[11px] font-medium text-violet-200">
            {humanizeRule(rule)}
        </span>
    );
}

function OutcomePill({ outcome }: { outcome: StuckMoment["interventionOutcome"] }) {
    if (outcome === "accepted")
        return (
            <span className="rounded-full bg-cyan-500/15 px-2 py-0.5 text-[11px] text-cyan-300">
                Accepted ✓
            </span>
        );
    if (outcome === "dismissed")
        return (
            <span className="rounded-full bg-white/5 px-2 py-0.5 text-[11px] text-muted-foreground">
                Dismissed
            </span>
        );
    return null;
}

function outcomeDot(outcome: StuckMoment["interventionOutcome"]): string {
    if (outcome === "accepted") return "bg-cyan-400";
    if (outcome === "dismissed") return "bg-muted-foreground/60";
    return "bg-amber-400";
}
