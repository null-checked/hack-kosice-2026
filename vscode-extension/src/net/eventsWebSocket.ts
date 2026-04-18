import WebSocket from 'ws';
import type { DeveloperEvent } from '../model/events';
import { Config } from '../settings/config';
import { EventBus } from '../detector/eventBus';
import { SessionContext } from '../detector/sessionContext';
import { log } from '../util/logger';

/**
 * Persistent WebSocket bridge from the extension to the backend's `/events`
 * endpoint. Mirrors [`EventsWebSocket.kt`](../../../plugin/src/main/kotlin/com/rubberduck/live/net/EventsWebSocket.kt:1):
 *
 * - Sends the `hello` envelope: `{type:"hello", payload:{role:"plugin", session_id}}`.
 * - Bridges [EventBus] outbound: wraps each [DeveloperEvent] in the `event`
 *   envelope (path-redaction must already be done upstream).
 * - 20 s heartbeat ping; on incoming `ping`, replies with `pong`.
 * - Exponential reconnect with backoff 1s → 2s → 4s → 8s → 16s → 30s and
 *   ±250 ms jitter.
 *
 * The role is always `"plugin"` — the backend doesn't care if the producer
 * is JetBrains or VS Code (Phase 2 notes confirm).
 */
class EventsWebSocketImpl {
  private ws: WebSocket | null = null;
  private running = false;
  private connected = false;
  private heartbeat: NodeJS.Timeout | null = null;
  private reconnectTimer: NodeJS.Timeout | null = null;
  private currentBackoff = 1000;
  private unsubscribe?: () => void;
  /** Lazy listener registry for connectivity changes (used by status bar). */
  private statusHandlers: Array<(connected: boolean) => void> = [];

  isConnected(): boolean {
    return this.connected;
  }

  onStatusChange(handler: (connected: boolean) => void): () => void {
    this.statusHandlers.push(handler);
    return () => { this.statusHandlers = this.statusHandlers.filter((h) => h !== handler); };
  }

  start(): void {
    if (this.running) return;
    this.running = true;
    this.unsubscribe = EventBus.getInstance().subscribe((ev) => this.sendEvent(ev));
    this.connect();
  }

  stop(): void {
    this.running = false;
    this.unsubscribe?.();
    this.unsubscribe = undefined;
    this.clearTimers();
    try { this.ws?.close(1000, 'extension stopping'); } catch { /* ignore */ }
    this.ws = null;
    this.setConnected(false);
  }

  private connect(): void {
    if (!this.running) return;
    const url = Config.backendWsUrl;
    log.info(`WS connecting to ${url}`);
    let ws: WebSocket;
    try {
      ws = new WebSocket(url);
    } catch (err) {
      log.warn(`WS construct failed: ${err instanceof Error ? err.message : String(err)}`);
      this.scheduleReconnect();
      return;
    }
    this.ws = ws;

    ws.on('open', () => {
      this.setConnected(true);
      this.currentBackoff = 1000; // reset after a healthy open
      this.sendHello();
      this.startHeartbeat();
    });

    ws.on('message', (raw: WebSocket.RawData) => {
      this.onMessage(String(raw));
    });

    ws.on('close', (code: number, reason: Buffer) => {
      log.debug(`WS closed: ${code} ${reason.toString()}`);
      this.setConnected(false);
      this.clearTimers();
      this.scheduleReconnect();
    });

    ws.on('error', (err: Error) => {
      log.debug(`WS error: ${err.message}`);
      this.setConnected(false);
      // 'close' will fire next; reconnection handled there.
    });
  }

  private scheduleReconnect(): void {
    if (!this.running) return;
    if (this.reconnectTimer) return;
    const jitter = Math.floor(Math.random() * 500) - 250; // ±250 ms
    const delay = Math.max(0, this.currentBackoff + jitter);
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null;
      this.currentBackoff = Math.min(this.currentBackoff * 2, 30_000);
      this.connect();
    }, delay);
  }

  private startHeartbeat(): void {
    this.clearHeartbeat();
    this.heartbeat = setInterval(() => {
      if (!this.connected || !this.ws) return;
      try {
        this.ws.send(this.envelope('ping', {}));
      } catch (err) {
        log.warn(`WS ping failed: ${err instanceof Error ? err.message : String(err)}`);
      }
    }, 20_000);
  }

  private clearHeartbeat(): void {
    if (this.heartbeat) { clearInterval(this.heartbeat); this.heartbeat = null; }
  }

  private clearTimers(): void {
    this.clearHeartbeat();
    if (this.reconnectTimer) { clearTimeout(this.reconnectTimer); this.reconnectTimer = null; }
  }

  private sendHello(): void {
    if (!this.ws) return;
    const payload = {
      role: 'plugin',
      session_id: SessionContext.getInstance().sessionId,
    };
    try { this.ws.send(this.envelope('hello', payload)); }
    catch (err) { log.warn(`WS hello send failed: ${err instanceof Error ? err.message : String(err)}`); }
  }

  private sendEvent(event: DeveloperEvent): void {
    if (!this.ws || !this.connected) return;
    if (!Config.sendEventsToDashboard) return;
    try {
      // The DeveloperEvent itself is the payload (matches the plugin shape).
      this.ws.send(this.envelope('event', event as unknown as Record<string, unknown>));
    } catch (err) {
      log.warn(`WS event send failed: ${err instanceof Error ? err.message : String(err)}`);
    }
  }

  private onMessage(text: string): void {
    let msg: unknown;
    try { msg = JSON.parse(text); }
    catch { log.debug(`WS non-JSON frame: ${text.slice(0, 80)}`); return; }
    if (!msg || typeof msg !== 'object') return;
    const m = msg as { type?: unknown };
    if (m.type === 'ping') {
      try { this.ws?.send(this.envelope('pong', {})); } catch { /* ignore */ }
    }
    // 'ack' / 'broadcast' / 'error' frames are informational for the producer.
  }

  private envelope(type: string, payload: Record<string, unknown>): string {
    return JSON.stringify({ type, timestamp: Date.now(), payload });
  }

  private setConnected(c: boolean): void {
    if (this.connected === c) return;
    this.connected = c;
    for (const h of this.statusHandlers) {
      try { h(c); } catch { /* ignore */ }
    }
  }
}

let _instance: EventsWebSocketImpl | undefined;
export const EventsWebSocket = {
  getInstance(): EventsWebSocketImpl {
    if (!_instance) _instance = new EventsWebSocketImpl();
    return _instance;
  },
};
