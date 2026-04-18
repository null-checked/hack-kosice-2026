"use client";

/**
 * Minimal session picker. "All sessions" is represented as `*` which
 * matches the handshake payload the dashboard sends to the backend.
 */

import { cn } from "@/lib/utils";

export interface SessionSwitcherProps {
    sessions: string[];
    value: string;
    onChange: (value: string) => void;
    className?: string;
}

export function SessionSwitcher({ sessions, value, onChange, className }: SessionSwitcherProps) {
    return (
        <label
            className={cn(
                "inline-flex items-center gap-2 rounded-full border border-white/10 bg-white/5 px-3 py-1.5 text-xs backdrop-blur",
                className,
            )}
        >
            <span className="text-muted-foreground">Session</span>
            <select
                aria-label="Session"
                className="appearance-none bg-transparent pr-2 text-foreground outline-none"
                value={value}
                onChange={(e) => onChange(e.target.value)}
            >
                <option value="*" className="bg-[#0b0f17]">
                    All
                </option>
                {sessions.map((s) => (
                    <option key={s} value={s} className="bg-[#0b0f17]">
                        {s.slice(0, 8)}
                    </option>
                ))}
            </select>
        </label>
    );
}
