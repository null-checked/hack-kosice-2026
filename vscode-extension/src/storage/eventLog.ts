import * as fs from 'node:fs';
import * as os from 'node:os';
import * as path from 'node:path';
import type { DeveloperEvent } from '../model/events';
import { log } from '../util/logger';

/**
 * Append-only event log for the VS Code extension. Mirrors
 * [`EventLog.kt`](../../../plugin/src/main/kotlin/com/rubberduck/live/storage/EventLog.kt:1)
 * conceptually but with a portable storage strategy:
 *
 * 1. Try `better-sqlite3` (loaded lazily). If the native binding is present
 *    we use the same SQLite schema as the plugin and run a 30-day prune on
 *    startup.
 * 2. If `better-sqlite3` cannot load (binary mismatch, no compiler, sandbox),
 *    fall back to a JSON-lines file at
 *    `~/.rubberduck-live/events.ndjson`. Same retention semantics: lines
 *    older than 30 days are dropped on startup.
 *
 * All operations are intentionally synchronous (cheap appends), wrapped in
 * try/catch so they never break the event hot path.
 */
class EventLogImpl {
  private mode: 'sqlite' | 'ndjson' | 'disabled' = 'disabled';
  // Internal native handle; intentionally typed loose to avoid hard dep.
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  private db: any | null = null;
  private ndjsonPath: string | null = null;

  init(): void {
    const dir = path.join(os.homedir(), '.rubberduck-live');
    try { fs.mkdirSync(dir, { recursive: true }); } catch { /* ignore */ }

    // Try sqlite first.
    try {
      const Database = require('better-sqlite3');
      const dbPath = path.join(dir, 'events.db');
      this.db = new Database(dbPath);
      this.db.pragma('journal_mode = WAL');
      this.db.exec(`
        CREATE TABLE IF NOT EXISTS events (
          id TEXT PRIMARY KEY,
          session_id TEXT NOT NULL,
          ts INTEGER NOT NULL,
          type TEXT NOT NULL,
          file TEXT,
          payload_json TEXT NOT NULL
        );
        CREATE INDEX IF NOT EXISTS idx_events_ts ON events(ts);
        CREATE INDEX IF NOT EXISTS idx_events_type ON events(type);
      `);
      const cutoff = Date.now() - 30 * 24 * 60 * 60 * 1000;
      this.db.prepare('DELETE FROM events WHERE ts < ?').run(cutoff);
      this.mode = 'sqlite';
      log.info(`EventLog: SQLite ready at ${dbPath}`);
      return;
    } catch (err) {
      log.warn(`EventLog: SQLite unavailable, falling back to ndjson (${err instanceof Error ? err.message : String(err)})`);
    }

    // Fallback: ndjson.
    try {
      const filePath = path.join(dir, 'events.ndjson');
      this.ndjsonPath = filePath;
      this.pruneNdjson();
      this.mode = 'ndjson';
      log.info(`EventLog: ndjson ready at ${filePath}`);
    } catch (err) {
      log.error('EventLog: ndjson init failed', err);
      this.mode = 'disabled';
    }
  }

  /** Append [event] to the log. Safe to call from any thread. */
  append(event: DeveloperEvent): void {
    try {
      switch (this.mode) {
        case 'sqlite':
          this.db.prepare(
            'INSERT OR REPLACE INTO events (id, session_id, ts, type, file, payload_json) VALUES (?,?,?,?,?,?)',
          ).run(
            event.event_id,
            event.session_id,
            event.ts,
            event.type,
            event.file ?? null,
            JSON.stringify(event.payload),
          );
          break;
        case 'ndjson':
          if (this.ndjsonPath) fs.appendFileSync(this.ndjsonPath, `${JSON.stringify(event)}\n`);
          break;
        case 'disabled':
          break;
      }
    } catch (err) {
      log.warn(`EventLog.append failed (mode=${this.mode}): ${err instanceof Error ? err.message : String(err)}`);
    }
  }

  shutdown(): void {
    try { this.db?.close(); } catch { /* ignore */ }
    this.db = null;
    this.mode = 'disabled';
  }

  /** Diagnostic: which backend ended up active. */
  currentMode(): 'sqlite' | 'ndjson' | 'disabled' {
    return this.mode;
  }

  private pruneNdjson(): void {
    if (!this.ndjsonPath) return;
    if (!fs.existsSync(this.ndjsonPath)) return;
    const cutoff = Date.now() - 30 * 24 * 60 * 60 * 1000;
    try {
      const raw = fs.readFileSync(this.ndjsonPath, 'utf8');
      const kept: string[] = [];
      for (const line of raw.split('\n')) {
        if (line.length === 0) continue;
        try {
          const obj = JSON.parse(line) as { ts?: number };
          if (typeof obj.ts === 'number' && obj.ts >= cutoff) kept.push(line);
        } catch { /* drop bad row */ }
      }
      fs.writeFileSync(this.ndjsonPath, kept.length === 0 ? '' : `${kept.join('\n')}\n`);
    } catch (err) {
      log.warn(`EventLog: ndjson prune failed: ${err instanceof Error ? err.message : String(err)}`);
    }
  }
}

let _instance: EventLogImpl | undefined;
export const EventLog = {
  getInstance(): EventLogImpl {
    if (!_instance) _instance = new EventLogImpl();
    return _instance;
  },
};
