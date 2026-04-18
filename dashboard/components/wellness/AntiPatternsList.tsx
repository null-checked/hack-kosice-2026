"use client";

/**
 * Top 5 anti-patterns detected by the plugin's StuckDetector.
 * Each row: icon, human-readable name, session total, 15-min delta arrow.
 */

import {
    AlertTriangle,
    ArrowDownRight,
    ArrowRight,
    ArrowUpRight,
    Hourglass,
    RotateCcw,
    Shuffle,
    Undo2,
    type LucideIcon,
} from "lucide-react";

import type { AntiPatternCount } from "@/lib/metrics";
import { humanizeRule } from "@/lib/format";
import { cn } from "@/lib/utils";
import { PanelCard, EmptyState } from "./PanelCard";

export interface AntiPatternsListProps {
    items: AntiPatternCount[];
    className?: string;
}

const RULE_META: Record<string, { icon: LucideIcon; blurb: string }> = {
    undo_burst: { icon: Undo2, blurb: "≥ 4 UNDOs within 30 s on the same file." },
    edit_thrash: { icon: RotateCcw, blurb: "Lots of churn, near-zero net change." },
    repeated_failing_test: {
        icon: AlertTriangle,
        blurb: "Same failure signature ≥ 3×.",
    },
    long_idle_on_error: { icon: Hourglass, blurb: "Long stare at a red marker." },
    rapid_file_switching: { icon: Shuffle, blurb: "Too many files in too little time." },
};

export function AntiPatternsList({ items, className }: AntiPatternsListProps) {
    const ranked = [...items].sort((a, b) => b.count - a.count).slice(0, 5);
    const empty = ranked.every((r) => r.count === 0);
    return (
        <PanelCard
            title="Anti-patterns"
            subtitle="Recurring stuck signatures"
            className={className}
        >
            {empty ? (
                <EmptyState message="No anti-patterns detected yet" />
            ) : (
                <ul className="flex flex-col divide-y divide-white/5">
                    {ranked.map((r) => {
                        const meta = RULE_META[r.rule] ?? {
                            icon: AlertTriangle,
                            blurb: "Custom heuristic.",
                        };
                        const Icon = meta.icon;
                        return (
                            <li
                                key={r.rule}
                                className="flex items-center gap-3 py-2 first:pt-0 last:pb-0"
                                title={meta.blurb}
                            >
                                <span className="flex h-8 w-8 items-center justify-center rounded-lg bg-white/5 text-violet-300">
                                    <Icon className="h-4 w-4" />
                                </span>
                                <div className="min-w-0 flex-1">
                                    <div className="truncate text-sm text-foreground">
                                        {humanizeRule(r.rule)}
                                    </div>
                                    <div className="text-[11px] text-muted-foreground">
                                        {r.count} total
                                    </div>
                                </div>
                                <DeltaBadge delta={r.delta} />
                            </li>
                        );
                    })}
                </ul>
            )}
        </PanelCard>
    );
}

function DeltaBadge({ delta }: { delta: number }) {
    if (delta > 0) {
        return (
            <span
                className={cn(
                    "inline-flex items-center gap-0.5 rounded-full bg-rose-500/10 px-2 py-0.5 text-[11px] font-medium text-rose-300",
                )}
                title="Up in the last 15 min"
            >
                <ArrowUpRight className="h-3 w-3" /> +{delta}
            </span>
        );
    }
    if (delta < 0) {
        return (
            <span
                className={cn(
                    "inline-flex items-center gap-0.5 rounded-full bg-cyan-500/10 px-2 py-0.5 text-[11px] font-medium text-cyan-300",
                )}
                title="Down in the last 15 min"
            >
                <ArrowDownRight className="h-3 w-3" /> {delta}
            </span>
        );
    }
    return (
        <span className="inline-flex items-center gap-0.5 rounded-full bg-white/5 px-2 py-0.5 text-[11px] text-muted-foreground">
            <ArrowRight className="h-3 w-3" /> 0
        </span>
    );
}
