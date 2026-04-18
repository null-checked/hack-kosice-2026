/**
 * Small display-format helpers — relative time, path shortening, durations.
 * All pure and side-effect free.
 */

/** "2 min ago" / "just now" — short, lossy on purpose. */
export function formatRelative(ts: number, now: number = Date.now()): string {
    const diffMs = Math.max(0, now - ts);
    const sec = Math.floor(diffMs / 1000);
    if (sec < 5) return "just now";
    if (sec < 60) return `${sec}s ago`;
    const min = Math.floor(sec / 60);
    if (min < 60) return `${min} min ago`;
    const hr = Math.floor(min / 60);
    if (hr < 24) return `${hr}h ago`;
    const day = Math.floor(hr / 24);
    return `${day}d ago`;
}

/** HH:MM:SS in local time — used by the event stream. */
export function formatClock(ts: number): string {
    const d = new Date(ts);
    const pad = (n: number) => n.toString().padStart(2, "0");
    return `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
}

/** Short tail of a project-relative path: ".../pkg/File.kt". */
export function shortenFile(path: string | null | undefined, segments = 2): string {
    if (!path) return "—";
    const parts = path.split(/[\\/]+/).filter(Boolean);
    if (parts.length <= segments) return parts.join("/");
    return `…/${parts.slice(-segments).join("/")}`;
}

/** ms → human ("1.2s", "340ms"). */
export function formatDuration(ms: number): string {
    if (ms < 1000) return `${Math.round(ms)}ms`;
    if (ms < 60_000) return `${(ms / 1000).toFixed(1)}s`;
    return `${Math.floor(ms / 60_000)}m ${Math.round((ms % 60_000) / 1000)}s`;
}

/** Humanise a StuckDetector rule id for UI. */
export function humanizeRule(rule: string): string {
    switch (rule) {
        case "undo_burst":
            return "Undo Burst";
        case "edit_thrash":
            return "Edit Thrash";
        case "repeated_failing_test":
            return "Repeated Failing Test";
        case "long_idle_on_error":
            return "Long Idle on Error";
        case "rapid_file_switching":
            return "Rapid File Switching";
        default:
            return rule.replace(/_/g, " ").replace(/\b\w/g, (c) => c.toUpperCase());
    }
}
