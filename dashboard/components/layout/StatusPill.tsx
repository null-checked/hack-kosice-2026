"use client";

/**
 * Small connection-status pill — shows the WS state as a colored dot + label.
 */

import { cn } from "@/lib/utils";
import type { SocketStatus } from "@/lib/types";

export interface StatusPillProps {
    status: SocketStatus;
    reachable: boolean;
    className?: string;
}

export function StatusPill({ status, reachable, className }: StatusPillProps) {
    const effective: SocketStatus = !reachable && status !== "open" ? "reconnecting" : status;
    const { label, color, pulse } = resolve(effective);
    return (
        <div
            role="status"
            aria-live="polite"
            className={cn(
                "inline-flex items-center gap-2 rounded-full border border-white/10 bg-white/5 px-3 py-1.5 text-xs font-medium backdrop-blur",
                className,
            )}
        >
            <span
                className={cn(
                    "inline-block h-2 w-2 rounded-full",
                    color,
                    pulse && "animate-pulse",
                )}
                aria-hidden
            />
            <span className="text-muted-foreground">{label}</span>
        </div>
    );
}

function resolve(status: SocketStatus): { label: string; color: string; pulse: boolean } {
    switch (status) {
        case "open":
            return { label: "Live", color: "bg-cyan-400 shadow-[0_0_8px_theme(colors.cyan.400)]", pulse: true };
        case "connecting":
            return { label: "Connecting…", color: "bg-amber-400", pulse: true };
        case "reconnecting":
            return { label: "Offline — retrying…", color: "bg-amber-500", pulse: true };
        case "closed":
        default:
            return { label: "Disconnected", color: "bg-rose-500", pulse: false };
    }
}
