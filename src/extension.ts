import { randomUUID } from 'node:crypto';
import * as path from 'node:path';
import * as vscode from 'vscode';

import { MetricsWriter } from './metricsWriter';
import type { DocumentRef, MetricsEvent, MetricsEventType } from './types';

type ChangeKind = 'insert' | 'delete' | 'replace';
type ChangeReason = 'undo' | 'redo' | 'other';
type SelectionKind = 'keyboard' | 'mouse' | 'command' | 'unknown';

interface MetricsTrackerConfig {
  outputFile: string;
  captureRawText: boolean;
  captureCursorSelections: boolean;
  excludeOutputFileFromTracking: boolean;
  maxEvents: number;
  writerFlushIntervalMs: number;
  textChangeDebounceMs: number;
  cursorDebounceMs: number;
  maxRawTextChars: number;
}

interface PreviewBuffer {
  text: string;
  truncated: boolean;
}

interface PendingTextBatch {
  document: DocumentRef;
  startedAt: number;
  lastUpdatedAt: number;
  changeCount: number;
  insertedLength: number;
  removedLength: number;
  kinds: Record<ChangeKind, number>;
  reasons: Record<ChangeReason, number>;
  insertedPreview: PreviewBuffer;
  removedPreview: PreviewBuffer;
  timer?: NodeJS.Timeout;
}

interface PositionSnapshot {
  line: number;
  character: number;
}

interface SelectionSnapshot {
  anchor: PositionSnapshot;
  active: PositionSnapshot;
  start: PositionSnapshot;
  end: PositionSnapshot;
  isEmpty: boolean;
  isReversed: boolean;
}

interface PendingCursorBatch {
  document: DocumentRef;
  startedAt: number;
  lastUpdatedAt: number;
  kind: SelectionKind;
  selections: SelectionSnapshot[];
  timer?: NodeJS.Timeout;
}

class MetricsSession implements vscode.Disposable {
  private readonly disposables: vscode.Disposable[] = [];
  private readonly snapshots = new Map<string, string>();
  private readonly pendingTextBatches = new Map<string, PendingTextBatch>();
  private readonly pendingCursorBatches = new Map<string, PendingCursorBatch>();
  private readonly sessionId = randomUUID();
  private readonly workspaceName: string | null;
  private readonly normalizedOutputPath: string;

  constructor(
    private readonly context: vscode.ExtensionContext,
    private readonly writer: MetricsWriter,
    private readonly logChannel: vscode.LogOutputChannel,
    private readonly config: MetricsTrackerConfig,
    outputPath: string
  ) {
    this.workspaceName = vscode.workspace.workspaceFolders?.[0]?.name ?? null;
    this.normalizedOutputPath = normalizeFileSystemPath(outputPath);

    this.seedSnapshots();
    this.registerListeners();

    this.emit('session-start', null, {
      extensionVersion: String(this.context.extension.packageJSON.version ?? 'unknown'),
      outputPath: this.writer.getOutputPath(),
      captureRawText: this.config.captureRawText,
      captureCursorSelections: this.config.captureCursorSelections,
      excludeOutputFileFromTracking: this.config.excludeOutputFileFromTracking,
      maxEvents: this.config.maxEvents,
      writerFlushIntervalMs: this.config.writerFlushIntervalMs,
      textChangeDebounceMs: this.config.textChangeDebounceMs,
      cursorDebounceMs: this.config.cursorDebounceMs,
      maxRawTextChars: this.config.maxRawTextChars
    });
  }

  public async dispose(): Promise<void> {
    this.flushAllPending();
    this.emit('session-end', null, {});
    this.disposables.forEach((disposable) => disposable.dispose());
    await this.writer.dispose();
  }

  private seedSnapshots(): void {
    for (const document of vscode.workspace.textDocuments) {
      if (!this.shouldTrackDocument(document)) {
        continue;
      }

      this.snapshots.set(document.uri.toString(), document.getText());
    }
  }

  private registerListeners(): void {
    this.disposables.push(
      vscode.workspace.onDidOpenTextDocument((document) => {
        if (!this.shouldTrackDocument(document)) {
          return;
        }

        this.snapshots.set(document.uri.toString(), document.getText());
        this.emit('document-open', this.toDocumentRef(document), {
          lineCount: document.lineCount
        });
      })
    );

    this.disposables.push(
      vscode.workspace.onDidCloseTextDocument((document) => {
        if (!this.shouldTrackDocument(document)) {
          return;
        }

        this.flushPendingForDocument(document.uri.toString());
        this.snapshots.delete(document.uri.toString());
        this.emit('document-close', this.toDocumentRef(document), {});
      })
    );

    this.disposables.push(
      vscode.workspace.onDidChangeTextDocument((event) => {
        const document = event.document;
        if (!this.shouldTrackDocument(document)) {
          return;
        }

        const key = document.uri.toString();
        const hasSnapshot = this.snapshots.has(key);
        let beforeText = this.snapshots.get(key) ?? document.getText();

        if (!hasSnapshot) {
          this.logChannel.warn(`Missing snapshot for ${key}. Removed text may be inaccurate.`);
        }

        const changeReason = toDocumentChangeReason(event.reason);

        event.contentChanges.forEach((change) => {
          const removedText = beforeText.slice(
            change.rangeOffset,
            change.rangeOffset + change.rangeLength
          );
          const insertedText = change.text;
          const changeKind = classifyChange(change);
          this.queueTextChange(document, changeKind, changeReason, insertedText, removedText);

          beforeText =
            beforeText.slice(0, change.rangeOffset) +
            insertedText +
            beforeText.slice(change.rangeOffset + change.rangeLength);
        });

        this.snapshots.set(key, document.getText());
      })
    );

    this.disposables.push(
      vscode.window.onDidChangeTextEditorSelection((event) => {
        if (!this.config.captureCursorSelections) {
          return;
        }

        const document = event.textEditor.document;
        if (!this.shouldTrackDocument(document)) {
          return;
        }

        this.queueCursorSelection(document, event);
      })
    );
  }

  private queueTextChange(
    document: vscode.TextDocument,
    changeKind: ChangeKind,
    reason: ChangeReason,
    insertedText: string,
    removedText: string
  ): void {
    const key = document.uri.toString();
    const now = Date.now();

    let batch = this.pendingTextBatches.get(key);
    if (!batch) {
      batch = {
        document: this.toDocumentRef(document),
        startedAt: now,
        lastUpdatedAt: now,
        changeCount: 0,
        insertedLength: 0,
        removedLength: 0,
        kinds: { insert: 0, delete: 0, replace: 0 },
        reasons: { undo: 0, redo: 0, other: 0 },
        insertedPreview: { text: '', truncated: false },
        removedPreview: { text: '', truncated: false }
      };

      this.pendingTextBatches.set(key, batch);
    }

    batch.document = this.toDocumentRef(document);
    batch.lastUpdatedAt = now;
    batch.changeCount += 1;
    batch.insertedLength += insertedText.length;
    batch.removedLength += removedText.length;
    batch.kinds[changeKind] += 1;
    batch.reasons[reason] += 1;

    if (this.config.captureRawText) {
      appendPreview(batch.insertedPreview, insertedText, this.config.maxRawTextChars);
      appendPreview(batch.removedPreview, removedText, this.config.maxRawTextChars);
    }

    if (batch.timer) {
      clearTimeout(batch.timer);
    }

    batch.timer = setTimeout(() => {
      this.flushTextBatch(key);
    }, this.config.textChangeDebounceMs);
  }

  private flushTextBatch(key: string): void {
    const batch = this.pendingTextBatches.get(key);
    if (!batch) {
      return;
    }

    if (batch.timer) {
      clearTimeout(batch.timer);
      batch.timer = undefined;
    }

    const payload: Record<string, unknown> = {
      changeCount: batch.changeCount,
      durationMs: Math.max(0, batch.lastUpdatedAt - batch.startedAt),
      insertedLength: batch.insertedLength,
      removedLength: batch.removedLength,
      kinds: batch.kinds,
      reasons: batch.reasons
    };

    if (this.config.captureRawText) {
      payload.insertedText = batch.insertedPreview.text;
      payload.removedText = batch.removedPreview.text;
      payload.insertedTextTruncated = batch.insertedPreview.truncated;
      payload.removedTextTruncated = batch.removedPreview.truncated;
      payload.maxRawTextChars = this.config.maxRawTextChars;
    }

    this.emit('text-change', batch.document, payload);
    this.pendingTextBatches.delete(key);
  }

  private queueCursorSelection(
    document: vscode.TextDocument,
    event: vscode.TextEditorSelectionChangeEvent
  ): void {
    const key = document.uri.toString();
    const now = Date.now();

    let batch = this.pendingCursorBatches.get(key);
    if (!batch) {
      batch = {
        document: this.toDocumentRef(document),
        startedAt: now,
        lastUpdatedAt: now,
        kind: toSelectionChangeKind(event.kind),
        selections: event.selections.map(toSelectionSnapshot)
      };

      this.pendingCursorBatches.set(key, batch);
    }

    batch.document = this.toDocumentRef(document);
    batch.lastUpdatedAt = now;
    batch.kind = toSelectionChangeKind(event.kind);
    batch.selections = event.selections.map(toSelectionSnapshot);

    if (batch.timer) {
      clearTimeout(batch.timer);
    }

    batch.timer = setTimeout(() => {
      this.flushCursorBatch(key);
    }, this.config.cursorDebounceMs);
  }

  private flushCursorBatch(key: string): void {
    const batch = this.pendingCursorBatches.get(key);
    if (!batch) {
      return;
    }

    if (batch.timer) {
      clearTimeout(batch.timer);
      batch.timer = undefined;
    }

    this.emit('cursor-selection', batch.document, {
      kind: batch.kind,
      selectionCount: batch.selections.length,
      settleWindowMs: Math.max(0, batch.lastUpdatedAt - batch.startedAt),
      debounceMs: this.config.cursorDebounceMs,
      selections: batch.selections
    });

    this.pendingCursorBatches.delete(key);
  }

  private flushPendingForDocument(documentKey: string): void {
    this.flushTextBatch(documentKey);
    this.flushCursorBatch(documentKey);
  }

  private flushAllPending(): void {
    for (const documentKey of Array.from(this.pendingTextBatches.keys())) {
      this.flushTextBatch(documentKey);
    }

    for (const documentKey of Array.from(this.pendingCursorBatches.keys())) {
      this.flushCursorBatch(documentKey);
    }
  }

  private shouldTrackDocument(document: vscode.TextDocument): boolean {
    if (document.uri.scheme !== 'file' && document.uri.scheme !== 'untitled') {
      return false;
    }

    if (
      this.config.excludeOutputFileFromTracking &&
      document.uri.scheme === 'file' &&
      normalizeFileSystemPath(document.uri.fsPath) === this.normalizedOutputPath
    ) {
      return false;
    }

    if (document.uri.scheme === 'untitled') {
      return true;
    }

    if (!vscode.workspace.workspaceFolders || vscode.workspace.workspaceFolders.length === 0) {
      return true;
    }

    return vscode.workspace.getWorkspaceFolder(document.uri) !== undefined;
  }

  private toDocumentRef(document: vscode.TextDocument): DocumentRef {
    return {
      uri: document.uri.toString(),
      languageId: document.languageId,
      version: document.version,
      isUntitled: document.isUntitled
    };
  }

  private emit(
    eventType: MetricsEventType,
    document: DocumentRef | null,
    payload: Record<string, unknown>
  ): void {
    const event: MetricsEvent = {
      schemaVersion: 1,
      eventId: randomUUID(),
      sessionId: this.sessionId,
      timestamp: new Date().toISOString(),
      eventType,
      workspaceName: this.workspaceName,
      document,
      payload
    };

    this.writer.append(event);
  }
}

let activeSession: MetricsSession | undefined;
let metricsOutputPath = '';

export async function activate(context: vscode.ExtensionContext): Promise<void> {
  const logChannel = vscode.window.createOutputChannel('Metrics Tracker', { log: true });
  context.subscriptions.push(logChannel);

  const config = readMetricsTrackerConfig();
  metricsOutputPath = resolveOutputPath(context, config.outputFile);
  const writer = new MetricsWriter(metricsOutputPath, logChannel, {
    maxEvents: config.maxEvents,
    flushIntervalMs: config.writerFlushIntervalMs
  });

  activeSession = new MetricsSession(context, writer, logChannel, config, metricsOutputPath);

  const openMetricsFileCommand = vscode.commands.registerCommand(
    'metricsTracker.openMetricsFile',
    async () => {
      try {
        const outputUri = vscode.Uri.file(metricsOutputPath);
        await vscode.workspace.fs.createDirectory(vscode.Uri.file(path.dirname(metricsOutputPath)));

        try {
          await vscode.workspace.fs.stat(outputUri);
        } catch {
          await vscode.workspace.fs.writeFile(outputUri, Buffer.from(''));
        }

        const document = await vscode.workspace.openTextDocument(outputUri);
        await vscode.window.showTextDocument(document, { preview: false });
      } catch (error: unknown) {
        const message = error instanceof Error ? error.message : String(error);
        logChannel.error(`Unable to open metrics file: ${message}`);
        void vscode.window.showErrorMessage(`Metrics Tracker: Failed to open metrics file. ${message}`);
      }
    }
  );

  context.subscriptions.push(openMetricsFileCommand);
  logChannel.info(`Metrics tracker active. Writing to ${metricsOutputPath}`);
}

export async function deactivate(): Promise<void> {
  if (activeSession) {
    await activeSession.dispose();
    activeSession = undefined;
  }
}

function resolveOutputPath(context: vscode.ExtensionContext, configuredPath: string): string {
  const normalizedConfiguredPath = configuredPath.trim();
  const targetPath = normalizedConfiguredPath.length > 0 ? normalizedConfiguredPath : '.metrics/events.ndjson';

  if (path.isAbsolute(targetPath)) {
    return targetPath;
  }

  const workspaceFolder = vscode.workspace.workspaceFolders?.[0];
  if (workspaceFolder && workspaceFolder.uri.scheme === 'file') {
    return path.join(workspaceFolder.uri.fsPath, targetPath);
  }

  return path.join(context.globalStorageUri.fsPath, targetPath);
}

function readMetricsTrackerConfig(): MetricsTrackerConfig {
  const configuration = vscode.workspace.getConfiguration('metricsTracker');
  const configuredOutputFile = configuration
    .get<string>('outputFile', '.metrics/events.ndjson')
    .trim();

  return {
    outputFile: configuredOutputFile.length > 0 ? configuredOutputFile : '.metrics/events.ndjson',
    captureRawText: configuration.get<boolean>('captureRawText', true),
    captureCursorSelections: configuration.get<boolean>('captureCursorSelections', true),
    excludeOutputFileFromTracking: configuration.get<boolean>('excludeOutputFileFromTracking', true),
    maxEvents: toBoundedInteger(configuration.get<number>('maxEvents', 5000), 5000, 10, 200000),
    writerFlushIntervalMs: toBoundedInteger(
      configuration.get<number>('writerFlushIntervalMs', 1000),
      1000,
      50,
      60000
    ),
    textChangeDebounceMs: toBoundedInteger(
      configuration.get<number>('textChangeDebounceMs', 500),
      500,
      25,
      30000
    ),
    cursorDebounceMs: toBoundedInteger(
      configuration.get<number>('cursorDebounceMs', 300),
      300,
      25,
      10000
    ),
    maxRawTextChars: toBoundedInteger(
      configuration.get<number>('maxRawTextChars', 400),
      400,
      0,
      100000
    )
  };
}

function normalizeFileSystemPath(fileSystemPath: string): string {
  return path.normalize(fileSystemPath).toLowerCase();
}

function classifyChange(change: vscode.TextDocumentContentChangeEvent): ChangeKind {
  if (change.rangeLength === 0 && change.text.length > 0) {
    return 'insert';
  }

  if (change.rangeLength > 0 && change.text.length === 0) {
    return 'delete';
  }

  return 'replace';
}

function toDocumentChangeReason(reason: vscode.TextDocumentChangeReason | undefined): ChangeReason {
  if (reason === vscode.TextDocumentChangeReason.Undo) {
    return 'undo';
  }

  if (reason === vscode.TextDocumentChangeReason.Redo) {
    return 'redo';
  }

  return 'other';
}

function toSelectionChangeKind(
  kind: vscode.TextEditorSelectionChangeKind | undefined
): SelectionKind {
  if (kind === vscode.TextEditorSelectionChangeKind.Keyboard) {
    return 'keyboard';
  }

  if (kind === vscode.TextEditorSelectionChangeKind.Mouse) {
    return 'mouse';
  }

  if (kind === vscode.TextEditorSelectionChangeKind.Command) {
    return 'command';
  }

  return 'unknown';
}

function toPosition(position: vscode.Position): PositionSnapshot {
  return {
    line: position.line,
    character: position.character
  };
}

function toSelectionSnapshot(selection: vscode.Selection): SelectionSnapshot {
  return {
    anchor: toPosition(selection.anchor),
    active: toPosition(selection.active),
    start: toPosition(selection.start),
    end: toPosition(selection.end),
    isEmpty: selection.isEmpty,
    isReversed: selection.isReversed
  };
}

function appendPreview(preview: PreviewBuffer, fragment: string, maxChars: number): void {
  if (fragment.length === 0) {
    return;
  }

  if (maxChars <= 0) {
    preview.truncated = true;
    return;
  }

  const remaining = maxChars - preview.text.length;
  if (remaining <= 0) {
    preview.truncated = true;
    return;
  }

  if (fragment.length > remaining) {
    preview.text += fragment.slice(0, remaining);
    preview.truncated = true;
    return;
  }

  preview.text += fragment;
}

function toBoundedInteger(
  value: number | undefined,
  fallback: number,
  min: number,
  max: number
): number {
  if (typeof value !== 'number' || !Number.isFinite(value)) {
    return fallback;
  }

  return Math.min(max, Math.max(min, Math.trunc(value)));
}
