"use client";

/**
 * Minimal responsive line sparkline built on `recharts`.
 * No axes, no grid, no tooltip — pure trend shape.
 */

import { Line, LineChart, ResponsiveContainer } from "recharts";

import { cn } from "@/lib/utils";

export interface SparklineProps {
    data: readonly number[];
    color?: string;
    height?: number;
    className?: string;
}

export function Sparkline({ data, color = "#22d3ee", height = 32, className }: SparklineProps) {
    const series = data.map((value, index) => ({ index, value }));
    return (
        <div className={cn("w-full", className)} style={{ height }}>
            <ResponsiveContainer width="100%" height="100%">
                <LineChart data={series} margin={{ top: 2, bottom: 2, left: 0, right: 0 }}>
                    <Line
                        type="monotone"
                        dataKey="value"
                        stroke={color}
                        strokeWidth={1.75}
                        dot={false}
                        isAnimationActive={false}
                    />
                </LineChart>
            </ResponsiveContainer>
        </div>
    );
}
