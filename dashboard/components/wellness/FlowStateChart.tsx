"use client";

/**
 * Stacked area chart of the last 2 hours (5-min bins): edits in cyan, undos in
 * amber, stuck events in rose. Gives a quick "was the pace calm or thrashy?"
 * read at a glance.
 */

import {
    Area,
    AreaChart,
    CartesianGrid,
    ResponsiveContainer,
    Tooltip,
    XAxis,
    YAxis,
} from "recharts";

import type { FlowSeries } from "@/lib/metrics";
import { PanelCard, EmptyState } from "./PanelCard";

export interface FlowStateChartProps {
    series: FlowSeries;
    className?: string;
}

export function FlowStateChart({ series, className }: FlowStateChartProps) {
    const empty = series.bins.every((b) => b.edit + b.undo + b.stuck === 0);
    const data = series.bins.map((b) => ({
        t: new Date(b.ts).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" }),
        edit: b.edit,
        undo: b.undo,
        stuck: b.stuck,
    }));
    return (
        <PanelCard
            title="Flow state"
            subtitle="Last 2 hours · 5-min bins"
            className={className}
            bodyClassName="h-[220px]"
        >
            {empty ? (
                <EmptyState message="No activity in the last 2 hours" />
            ) : (
                <ResponsiveContainer width="100%" height="100%">
                    <AreaChart data={data} margin={{ top: 6, right: 8, left: -16, bottom: 0 }}>
                        <defs>
                            <linearGradient id="g-edit" x1="0" x2="0" y1="0" y2="1">
                                <stop offset="0%" stopColor="#22d3ee" stopOpacity={0.65} />
                                <stop offset="100%" stopColor="#22d3ee" stopOpacity={0.05} />
                            </linearGradient>
                            <linearGradient id="g-undo" x1="0" x2="0" y1="0" y2="1">
                                <stop offset="0%" stopColor="#f59e0b" stopOpacity={0.55} />
                                <stop offset="100%" stopColor="#f59e0b" stopOpacity={0.05} />
                            </linearGradient>
                            <linearGradient id="g-stuck" x1="0" x2="0" y1="0" y2="1">
                                <stop offset="0%" stopColor="#ef4444" stopOpacity={0.6} />
                                <stop offset="100%" stopColor="#ef4444" stopOpacity={0.05} />
                            </linearGradient>
                        </defs>
                        <CartesianGrid strokeDasharray="3 3" stroke="rgba(255,255,255,0.06)" />
                        <XAxis
                            dataKey="t"
                            tick={{ fill: "#64748b", fontSize: 10 }}
                            interval={5}
                            axisLine={false}
                            tickLine={false}
                        />
                        <YAxis
                            tick={{ fill: "#64748b", fontSize: 10 }}
                            axisLine={false}
                            tickLine={false}
                            width={24}
                        />
                        <Tooltip
                            contentStyle={{
                                background: "#121826",
                                border: "1px solid rgba(255,255,255,0.1)",
                                borderRadius: 8,
                                fontSize: 12,
                            }}
                            labelStyle={{ color: "#cbd5e1" }}
                        />
                        <Area
                            type="monotone"
                            dataKey="edit"
                            stackId="1"
                            stroke="#22d3ee"
                            fill="url(#g-edit)"
                            strokeWidth={1.5}
                            isAnimationActive={false}
                        />
                        <Area
                            type="monotone"
                            dataKey="undo"
                            stackId="1"
                            stroke="#f59e0b"
                            fill="url(#g-undo)"
                            strokeWidth={1.5}
                            isAnimationActive={false}
                        />
                        <Area
                            type="monotone"
                            dataKey="stuck"
                            stackId="1"
                            stroke="#ef4444"
                            fill="url(#g-stuck)"
                            strokeWidth={1.5}
                            isAnimationActive={false}
                        />
                    </AreaChart>
                </ResponsiveContainer>
            )}
        </PanelCard>
    );
}
