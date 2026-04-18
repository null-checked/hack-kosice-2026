"use client";

/**
 * A reusable surface for every wellness panel: title row, optional subtitle,
 * optional right-side slot, and a content region. Keeps the look coherent.
 */

import type { ReactNode } from "react";

import { cn } from "@/lib/utils";

export interface PanelCardProps {
    title: string;
    subtitle?: string;
    right?: ReactNode;
    className?: string;
    bodyClassName?: string;
    children: ReactNode;
}

export function PanelCard({
    title,
    subtitle,
    right,
    className,
    bodyClassName,
    children,
}: PanelCardProps) {
    return (
        <section
            className={cn(
                "flex flex-col rounded-2xl border border-white/10 bg-surface/70 p-4 backdrop-blur shadow-inner shadow-black/20",
                className,
            )}
        >
            <header className="flex items-start justify-between gap-2">
                <div className="min-w-0">
                    <h2 className="text-sm font-semibold tracking-wide text-foreground">{title}</h2>
                    {subtitle && (
                        <p className="text-[11px] text-muted-foreground">{subtitle}</p>
                    )}
                </div>
                {right && <div className="shrink-0 text-xs text-muted-foreground">{right}</div>}
            </header>
            <div className={cn("mt-3 min-h-0 flex-1", bodyClassName)}>{children}</div>
        </section>
    );
}

export function EmptyState({ message = "Waiting for events…" }: { message?: string }) {
    return (
        <div className="flex h-full min-h-[120px] flex-col items-center justify-center gap-2 text-center text-xs text-muted-foreground">
            <DuckOutline className="h-10 w-10 opacity-30" />
            <span>{message}</span>
        </div>
    );
}

export function DuckOutline({ className }: { className?: string }) {
    return (
        <svg
            viewBox="0 0 64 64"
            className={className}
            aria-hidden
            fill="none"
            stroke="currentColor"
            strokeWidth="1.5"
            strokeLinecap="round"
            strokeLinejoin="round"
        >
            <path d="M20 38c0-10 8-18 18-18 5 0 10 2 13 6l6-2-2 6c2 3 3 6 3 10 0 10-8 18-18 18H14l2-6c-2-2-3-4-3-7 0-4 3-7 7-7Z" />
            <circle cx="40" cy="26" r="1.5" fill="currentColor" />
        </svg>
    );
}
