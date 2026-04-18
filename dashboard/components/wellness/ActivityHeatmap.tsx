"use client";

/**
 * 24-hour activity heatmap — one cell per local hour, colored by edit density.
 * Designed to sit at ~3.5rem tall in the above-the-fold row.
 */

import type { HeatmapCell } from "@/lib/metrics";
import { cn } from "@/lib/utils";
import { PanelCard, EmptyState } from "./PanelCard";

export interface ActivityHeatmapProps {
    cells: HeatmapCell[];
    className?: string;
}

export function ActivityHeatmap({ cells, className }: ActivityHeatmapProps) {
    const empty = cells.every((c) => c.edit === 0 && c.stuck === 0);
    const currentHour = new Date().getHours();
    return (
        <PanelCard
            title="Activity — last 24h"
            subtitle="Edit density per local hour"
            right={<Legend />}
            className={className}
        >
            {empty ? (
                <EmptyState message="No edits yet today" />
            ) : (
                <div className="flex flex-col gap-3">
                    <div
                        className="grid gap-1"
                        style={{ gridTemplateColumns: "repeat(24, minmax(0, 1fr))" }}
                        role="img"
                        aria-label="Hourly edit density"
                    >
                        {cells.map((c) => (
                            <Cell key={c.hour} cell={c} isNow={c.hour === currentHour} />
                        ))}
                    </div>
                    <div className="flex justify-between text-[10px] uppercase tracking-widest text-muted-foreground">
                        <span>00</span>
                        <span>06</span>
                        <span>12</span>
                        <span>18</span>
                        <span>23</span>
                    </div>
                </div>
            )}
        </PanelCard>
    );
}

function Cell({ cell, isNow }: { cell: HeatmapCell; isNow: boolean }) {
    const bg = intensityColor(cell.intensity);
    return (
        <div
            title={`${cell.hour.toString().padStart(2, "0")}:00 — ${cell.edit} edits${cell.stuck ? `, ${cell.stuck} stuck` : ""}`}
            className={cn(
                "h-10 rounded-sm transition-colors",
                isNow && "ring-1 ring-cyan-300/60",
                cell.stuck > 0 && "outline outline-1 outline-amber-400/60",
            )}
            style={{ backgroundColor: bg }}
        />
    );
}

function intensityColor(t: number): string {
    if (t <= 0) return "rgba(255,255,255,0.04)";
    // violet → cyan blend, opacity = intensity
    const opacity = 0.2 + 0.75 * t;
    return `rgba(139, 92, 246, ${opacity.toFixed(2)})`;
}

function Legend() {
    return (
        <div className="flex items-center gap-2">
            <span>low</span>
            <div className="flex h-2 w-20 overflow-hidden rounded-full">
                <span className="flex-1" style={{ background: "rgba(139,92,246,0.2)" }} />
                <span className="flex-1" style={{ background: "rgba(139,92,246,0.55)" }} />
                <span className="flex-1" style={{ background: "rgba(139,92,246,0.95)" }} />
            </div>
            <span>high</span>
        </div>
    );
}
