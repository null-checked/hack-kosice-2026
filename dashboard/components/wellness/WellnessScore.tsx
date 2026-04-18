"use client";

/**
 * Circular score ring (0..100). SVG stroke-dasharray + gradient-based color.
 * No external chart lib — the geometry is small and the animation is subtle.
 */

import { useMemo } from "react";

import type { WellnessResult } from "@/lib/metrics";
import { cn } from "@/lib/utils";
import { PanelCard, EmptyState } from "./PanelCard";

export interface WellnessScoreProps {
    wellness: WellnessResult;
    empty: boolean;
    className?: string;
}

export function WellnessScore({ wellness, empty, className }: WellnessScoreProps) {
    const { score, label, breakdown } = wellness;

    const tooltip = useMemo(() => {
        const { stuckPenalty, undoPenalty, interventionBonus, testBonus } = breakdown;
        return [
            "Derived from last 60 min of activity",
            `• stuck penalty: −${stuckPenalty}`,
            `• undo penalty: −${undoPenalty}`,
            `• intervention bonus: +${interventionBonus}`,
            `• test bonus: +${testBonus.toFixed(1)}`,
        ].join("\n");
    }, [breakdown]);

    return (
        <PanelCard title="Wellness Score" subtitle="Last 60 min" className={className}>
            {empty ? (
                <EmptyState message="No activity yet" />
            ) : (
                <div className="flex h-full flex-col items-center justify-center gap-3" title={tooltip}>
                    <Ring score={score} />
                    <div className="text-center">
                        <div className={cn("text-xl font-semibold", labelColor(label))}>{label}</div>
                        <div className="text-[11px] text-muted-foreground">
                            hover ring for breakdown
                        </div>
                    </div>
                </div>
            )}
        </PanelCard>
    );
}

function Ring({ score }: { score: number }) {
    const size = 144;
    const stroke = 12;
    const r = (size - stroke) / 2;
    const circumference = 2 * Math.PI * r;
    const pct = Math.max(0, Math.min(100, score));
    const offset = circumference * (1 - pct / 100);
    const color = scoreColor(score);
    return (
        <div className="relative" style={{ width: size, height: size }}>
            <svg viewBox={`0 0 ${size} ${size}`} width={size} height={size} aria-hidden>
                <defs>
                    <linearGradient id="ring-grad" x1="0" x2="1" y1="0" y2="1">
                        <stop offset="0%" stopColor={color.from} />
                        <stop offset="100%" stopColor={color.to} />
                    </linearGradient>
                </defs>
                <circle
                    cx={size / 2}
                    cy={size / 2}
                    r={r}
                    stroke="rgba(255,255,255,0.07)"
                    strokeWidth={stroke}
                    fill="none"
                />
                <circle
                    cx={size / 2}
                    cy={size / 2}
                    r={r}
                    stroke="url(#ring-grad)"
                    strokeWidth={stroke}
                    strokeLinecap="round"
                    fill="none"
                    strokeDasharray={circumference}
                    strokeDashoffset={offset}
                    transform={`rotate(-90 ${size / 2} ${size / 2})`}
                    style={{ transition: "stroke-dashoffset 600ms ease" }}
                />
            </svg>
            <div className="absolute inset-0 flex flex-col items-center justify-center">
                <span className="text-4xl font-semibold leading-none text-foreground tabular-nums">
                    {score}
                </span>
                <span className="mt-1 text-[10px] uppercase tracking-widest text-muted-foreground">
                    / 100
                </span>
            </div>
        </div>
    );
}

function scoreColor(score: number): { from: string; to: string } {
    if (score >= 90) return { from: "#8b5cf6", to: "#22d3ee" };
    if (score >= 70) return { from: "#22d3ee", to: "#8b5cf6" };
    if (score >= 50) return { from: "#22d3ee", to: "#f59e0b" };
    if (score >= 30) return { from: "#f59e0b", to: "#ef4444" };
    return { from: "#ef4444", to: "#b91c1c" };
}

function labelColor(label: WellnessResult["label"]): string {
    switch (label) {
        case "Flowing":
            return "text-violet-300";
        case "Focused":
            return "text-cyan-300";
        case "Working":
            return "text-amber-300";
        case "Struggling":
            return "text-amber-400";
        case "Stuck":
        default:
            return "text-rose-400";
    }
}
