"use client";

/**
 * Hero banner at the top of the dashboard.
 * Renders the brand title, tagline, status pill, session switcher, and the
 * optional DEMO_REPLAY button. Pure presentation — state lives in the page.
 */

import type { ReactNode } from "react";

import { cn } from "@/lib/utils";

export interface HeroProps {
    right?: ReactNode;
    className?: string;
}

export function Hero({ right, className }: HeroProps) {
    return (
        <header
            className={cn(
                "relative overflow-hidden rounded-3xl border border-white/10 bg-surface/70 px-6 py-5 backdrop-blur-xl",
                "shadow-[0_1px_0_0_rgba(255,255,255,0.04)_inset]",
                className,
            )}
        >
            <div className="pointer-events-none absolute inset-0 -z-10 mesh-hero opacity-80" aria-hidden />
            <div className="flex flex-col gap-3 lg:flex-row lg:items-center lg:justify-between">
                <div className="min-w-0">
                    <div className="flex items-center gap-3">
                        <span className="text-4xl leading-none" aria-hidden>
                            🦆
                        </span>
                        <h1 className="text-4xl font-semibold tracking-tight text-foreground sm:text-5xl">
                            RubberDuck{" "}
                            <span className="bg-gradient-to-r from-violet-400 via-cyan-300 to-violet-400 bg-clip-text text-transparent">
                                Live
                            </span>
                        </h1>
                    </div>
                    <p className="mt-1.5 text-sm text-muted-foreground sm:text-base">
                        Your IDE&rsquo;s pair programmer that watches you think.
                    </p>
                </div>
                {/*
                 * Right-side action cluster.
                 * The first child is conventionally the <StatusPill /> (role="status").
                 * We boost its prominence here (without modifying StatusPill itself) so
                 * judges immediately see the live backend connection state:
                 *   - larger text + padding
                 *   - bigger status dot
                 *   - subtle cyan ring + glow
                 *   - uppercase tracking for a "LIVE" feel
                 */}
                <div
                    className={cn(
                        "flex flex-wrap items-center gap-2.5",
                        "[&>[role=status]]:px-3.5 [&>[role=status]]:py-2 [&>[role=status]]:text-sm",
                        "[&>[role=status]]:font-semibold [&>[role=status]]:tracking-wide",
                        "[&>[role=status]]:ring-1 [&>[role=status]]:ring-cyan-400/30",
                        "[&>[role=status]]:shadow-[0_0_0_1px_rgba(34,211,238,0.15),0_0_18px_-2px_rgba(34,211,238,0.45)]",
                        "[&>[role=status]>span:first-child]:h-2.5 [&>[role=status]>span:first-child]:w-2.5",
                        "[&>[role=status]>span:last-child]:text-foreground/90",
                    )}
                >
                    {right}
                </div>
            </div>
        </header>
    );
}
