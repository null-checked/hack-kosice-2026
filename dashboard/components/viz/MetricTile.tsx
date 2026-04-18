"use client";

/**
 * Headline numeric tile — big number + label + optional delta + sparkline.
 * Used for "Events / min", "Top file", etc.
 */

import type { ReactNode } from "react";

import { cn } from "@/lib/utils";
import { Sparkline } from "./Sparkline";

export interface MetricTileProps {
    label: string;
    value: ReactNode;
    hint?: string;
    trend?: readonly number[];
    trendColor?: string;
    className?: string;
    icon?: ReactNode;
}

export function MetricTile({
    label,
    value,
    hint,
    trend,
    trendColor,
    className,
    icon,
}: MetricTileProps) {
    return (
        <div
            className={cn(
                "relative flex flex-col justify-between rounded-2xl border border-white/10 bg-surface/70 p-4 shadow-inner shadow-black/20 backdrop-blur",
                className,
            )}
        >
            <div className="flex items-center justify-between gap-2">
                <span className="text-[11px] font-medium uppercase tracking-wider text-muted-foreground">
                    {label}
                </span>
                {icon && <span className="text-muted-foreground">{icon}</span>}
            </div>
            <div className="mt-1 truncate text-3xl font-semibold text-foreground">{value}</div>
            {hint && <div className="text-xs text-muted-foreground">{hint}</div>}
            {trend && trend.length > 1 && (
                <Sparkline data={trend} color={trendColor} height={28} className="mt-2" />
            )}
        </div>
    );
}
