"use client";

/**
 * WebSocket client for the dashboard.
 *
 * Connects to the backend events feed (see `docs/ARCHITECTURE.md` §4 and
 * `docs/PHASE2_NOTES.md` for the exact handshake semantics):
 *   1. Open `ws://127.0.0.1:8765/api/v1/events`.
 *   2. Send `{type:"hello", timestamp, payload:{role:"dashboard", session_id:"*"}}`.
 *   3. Receive one snapshot `broadcast` with `payload.kind === "snapshot"`.
 *   4. Receive live `broadcast` frames with `payload.kind === "event"`.
 *   5. Reply to `ping` with `pong`.
 *   6. On disconnect: exponential backoff reconnect (1→2→4→8→16→30 s).
 *
 * Exposed state is intentionally minimal so panels can memoise freely.
 */

import { useCallback, useEffect, useMemo, useRef, useState } from "react";

import type {
    BroadcastPayload,
    DeveloperEvent,
    SocketStatus,
    WSEnvelope,
} from "@/lib/types";
import { uniqueSessions } from "@/lib/metrics";

const DEFAULT_WS_URL =
    process.env.NEXT_PUBLIC_BACKEND_WS_URL ?? "ws://127.0.0.1:8765/api/v1/events";
const MAX_EVENTS = 2000;
const BACKOFF_STEPS = [1_000, 2_000, 4_000, 8_000, 16_000, 30_000];

export interface UseEventsSocketResult {
    status: SocketStatus;
    events: DeveloperEvent[];
    sessions: string[];
    selectedSession: string;
    switchSession: (id: string) => void;
    lastError: string | null;
    url: string;
    /** Manually inject synthetic events (used by the demo-replay button). */
    injectEvents: (evs: DeveloperEvent[]) => void;
    clear: () => void;
}

export function useEventsSocket(url: string = DEFAULT_WS_URL): UseEventsSocketResult {
    const [status, setStatus] = useState<SocketStatus>("connecting");
    const [events, setEvents] = useState<DeveloperEvent[]>([]);
    const [selectedSession, setSelectedSession] = useState<string>("*");
    const [lastError, setLastError] = useState<string | null>(null);

    const wsRef = useRef<WebSocket | null>(null);
    const attemptRef = useRef(0);
    const closedByUserRef = useRef(false);
    const reconnectTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

    const sessions = useMemo(() => uniqueSessions(events), [events]);

    const push = useCallback((e: DeveloperEvent) => {
        setEvents((prev) => {
            const next = prev.length >= MAX_EVENTS ? prev.slice(-MAX_EVENTS + 1) : prev.slice();
            next.push(e);
            return next;
        });
    }, []);

    const injectEvents = useCallback((evs: DeveloperEvent[]) => {
        setEvents((prev) => {
            const merged = [...prev, ...evs];
            merged.sort((a, b) => a.ts - b.ts);
            return merged.length > MAX_EVENTS ? merged.slice(-MAX_EVENTS) : merged;
        });
    }, []);

    const clear = useCallback(() => setEvents([]), []);

    useEffect(() => {
        closedByUserRef.current = false;

        const connect = () => {
            setStatus(attemptRef.current === 0 ? "connecting" : "reconnecting");
            let ws: WebSocket;
            try {
                ws = new WebSocket(url);
            } catch (err) {
                setLastError((err as Error).message);
                scheduleReconnect();
                return;
            }
            wsRef.current = ws;

            ws.onopen = () => {
                attemptRef.current = 0;
                setStatus("open");
                setLastError(null);
                const hello: WSEnvelope = {
                    type: "hello",
                    timestamp: Date.now(),
                    payload: { role: "dashboard", session_id: "*" },
                };
                ws.send(JSON.stringify(hello));
            };

            ws.onmessage = (ev) => {
                let frame: WSEnvelope<unknown>;
                try {
                    frame = JSON.parse(typeof ev.data === "string" ? ev.data : "") as WSEnvelope;
                } catch {
                    return;
                }
                switch (frame.type) {
                    case "ack":
                        // no-op
                        break;
                    case "ping": {
                        const pong: WSEnvelope = {
                            type: "pong",
                            timestamp: Date.now(),
                            payload: {},
                        };
                        try {
                            ws.send(JSON.stringify(pong));
                        } catch {
                            /* ignore */
                        }
                        break;
                    }
                    case "error":
                        setLastError(
                            typeof (frame.payload as { message?: string })?.message === "string"
                                ? (frame.payload as { message: string }).message
                                : "backend error",
                        );
                        break;
                    case "broadcast": {
                        const p = frame.payload as BroadcastPayload;
                        if (p && "kind" in p) {
                            if (p.kind === "snapshot") {
                                setEvents(p.events.slice(-MAX_EVENTS));
                            } else if (p.kind === "event") {
                                push(p.event);
                            }
                        }
                        break;
                    }
                    default:
                        break;
                }
            };

            ws.onerror = () => {
                setLastError("WebSocket error");
            };

            ws.onclose = () => {
                wsRef.current = null;
                if (closedByUserRef.current) {
                    setStatus("closed");
                    return;
                }
                scheduleReconnect();
            };
        };

        const scheduleReconnect = () => {
            const idx = Math.min(attemptRef.current, BACKOFF_STEPS.length - 1);
            const delay = BACKOFF_STEPS[idx] + Math.floor(Math.random() * 250);
            attemptRef.current += 1;
            setStatus("reconnecting");
            reconnectTimerRef.current = setTimeout(connect, delay);
        };

        connect();

        return () => {
            closedByUserRef.current = true;
            if (reconnectTimerRef.current) clearTimeout(reconnectTimerRef.current);
            wsRef.current?.close();
            wsRef.current = null;
        };
    }, [url, push]);

    const visibleEvents = useMemo(() => {
        if (selectedSession === "*") return events;
        return events.filter((e) => e.session_id === selectedSession);
    }, [events, selectedSession]);

    return {
        status,
        events: visibleEvents,
        sessions,
        selectedSession,
        switchSession: setSelectedSession,
        lastError,
        url,
        injectEvents,
        clear,
    };
}
