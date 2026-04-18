"use client";

/**
 * Polls the backend `/health` endpoint every 10 s and exposes the latest
 * reading plus a simple "reachable" flag. Silent on failures.
 */

import { useEffect, useState } from "react";

import type { BackendHealth } from "@/lib/types";

// Backend HTTP URL is fully configurable via env. Priority:
//   1. `NEXT_PUBLIC_BACKEND_HTTP_URL` — full override.
//   2. `NEXT_PUBLIC_BACKEND_HOST` + `NEXT_PUBLIC_BACKEND_PORT` — derive.
//   3. Fallback to `127.0.0.1:8765`.
const DEFAULT_BACKEND_HOST = process.env.NEXT_PUBLIC_BACKEND_HOST ?? "127.0.0.1";
const DEFAULT_BACKEND_PORT = process.env.NEXT_PUBLIC_BACKEND_PORT ?? "8765";
const DEFAULT_HTTP_URL =
    process.env.NEXT_PUBLIC_BACKEND_HTTP_URL ??
    `http://${DEFAULT_BACKEND_HOST}:${DEFAULT_BACKEND_PORT}/api/v1`;
const POLL_MS = 10_000;

export interface UseBackendHealthResult {
    health: BackendHealth | null;
    reachable: boolean;
    lastCheck: number;
}

export function useBackendHealth(baseUrl: string = DEFAULT_HTTP_URL): UseBackendHealthResult {
    const [health, setHealth] = useState<BackendHealth | null>(null);
    const [reachable, setReachable] = useState<boolean>(false);
    const [lastCheck, setLastCheck] = useState<number>(0);

    useEffect(() => {
        let cancelled = false;
        const tick = async () => {
            try {
                const res = await fetch(`${baseUrl}/health`, { cache: "no-store" });
                if (!res.ok) throw new Error(`HTTP ${res.status}`);
                const json = (await res.json()) as BackendHealth;
                if (cancelled) return;
                setHealth(json);
                setReachable(true);
            } catch {
                if (cancelled) return;
                setReachable(false);
            } finally {
                if (!cancelled) setLastCheck(Date.now());
            }
        };
        void tick();
        const id = setInterval(tick, POLL_MS);
        return () => {
            cancelled = true;
            clearInterval(id);
        };
    }, [baseUrl]);

    return { health, reachable, lastCheck };
}
