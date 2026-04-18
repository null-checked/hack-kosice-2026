import { promises as fs } from 'node:fs';
import * as path from 'node:path';
import * as vscode from 'vscode';

import type { MetricsEvent } from './types';

const BOOTSTRAP_READ_LIMIT_BYTES = 5 * 1024 * 1024;

export interface MetricsWriterOptions {
  maxEvents: number;
  flushIntervalMs: number;
}

export class MetricsWriter implements vscode.Disposable {
  private readonly bufferedEvents: string[] = [];
  private flushTimer: NodeJS.Timeout | undefined;
  private flushQueue: Promise<void> = Promise.resolve();
  private readonly ready: Promise<void>;

  constructor(
    private readonly outputPath: string,
    private readonly logChannel: vscode.LogOutputChannel,
    private readonly options: MetricsWriterOptions
  ) {
    this.ready = this.initialize();
  }

  public append(event: MetricsEvent): void {
    const serializedEvent = JSON.stringify(event);

    void this.ready
      .then(() => {
        this.bufferedEvents.push(serializedEvent);
        this.trimToCapacity();
        this.scheduleFlush();
      })
      .catch((error: unknown) => {
        this.logError('Failed to queue metrics event', error);
      });
  }

  public getOutputPath(): string {
    return this.outputPath;
  }

  public async dispose(): Promise<void> {
    if (this.flushTimer) {
      clearTimeout(this.flushTimer);
      this.flushTimer = undefined;
    }

    await this.ready;
    await this.flushToDisk();
  }

  private async initialize(): Promise<void> {
    await fs.mkdir(path.dirname(this.outputPath), { recursive: true });
    await this.bootstrapFromExistingFile();
  }

  private async bootstrapFromExistingFile(): Promise<void> {
    try {
      const stats = await fs.stat(this.outputPath);
      if (stats.size > BOOTSTRAP_READ_LIMIT_BYTES) {
        this.logChannel.warn(
          `Existing metrics file is larger than ${BOOTSTRAP_READ_LIMIT_BYTES} bytes. Starting fresh with bounded buffering.`
        );
        return;
      }

      const existingContent = await fs.readFile(this.outputPath, 'utf8');
      const lines = existingContent.split(/\r?\n/).filter((line) => line.length > 0);
      if (lines.length === 0) {
        return;
      }

      const capacity = this.getCapacity();
      this.bufferedEvents.push(...lines.slice(-capacity));
    } catch (error: unknown) {
      if (isFileNotFoundError(error)) {
        return;
      }

      this.logError('Failed to load existing metrics events', error);
    }
  }

  private scheduleFlush(): void {
    if (this.flushTimer) {
      clearTimeout(this.flushTimer);
    }

    this.flushTimer = setTimeout(() => {
      this.flushTimer = undefined;
      void this.flushToDisk();
    }, this.getFlushIntervalMs());
  }

  private async flushToDisk(): Promise<void> {
    this.flushQueue = this.flushQueue
      .then(async () => {
        await this.ready;
        const serialized =
          this.bufferedEvents.length === 0 ? '' : `${this.bufferedEvents.join('\n')}\n`;
        await fs.writeFile(this.outputPath, serialized, 'utf8');
      })
      .catch((error: unknown) => {
        this.logError('Failed to flush metrics events', error);
      });

    await this.flushQueue;
  }

  private trimToCapacity(): void {
    const capacity = this.getCapacity();
    if (this.bufferedEvents.length <= capacity) {
      return;
    }

    this.bufferedEvents.splice(0, this.bufferedEvents.length - capacity);
  }

  private getCapacity(): number {
    return Math.max(1, Math.trunc(this.options.maxEvents));
  }

  private getFlushIntervalMs(): number {
    return Math.max(25, Math.trunc(this.options.flushIntervalMs));
  }

  private logError(prefix: string, error: unknown): void {
    const message = error instanceof Error ? error.message : String(error);
    this.logChannel.error(`${prefix}: ${message}`);
  }
}

function isFileNotFoundError(error: unknown): boolean {
  return (
    typeof error === 'object' &&
    error !== null &&
    'code' in error &&
    (error as { code?: string }).code === 'ENOENT'
  );
}
