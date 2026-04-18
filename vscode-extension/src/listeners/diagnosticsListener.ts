import * as vscode from 'vscode';

import { emit } from '../detector/eventEmitter';
import { EventType } from '../model/events';
import { PathRedactor } from '../util/pathRedactor';
import { log } from '../util/logger';

/**
 * Emits `INSPECTION` events for Error + Warning diagnostics produced by any
 * VSCode language server / linter. Mirrors the IntelliJ plugin's
 * [`InspectionListener.kt`](../../../plugin/src/main/kotlin/com/rubberduck/live/listeners/InspectionListener.kt:1).
 *
 * Subscribes to `vscode.languages.onDidChangeDiagnostics`. When diagnostics
 * change for a URI, we read them via `vscode.languages.getDiagnostics(uri)`,
 * dedup against the previous snapshot by `line:severity:message` digest,
 * and emit one `INSPECTION` event per NEW issue.
 *
 * Information / Hint severities are suppressed to keep the event stream
 * signal-dense — they'd otherwise drown the dashboard.
 */
export function registerDiagnosticsListener(context: vscode.ExtensionContext): void {
  // Per-URI dedup set of digests seen on the last pass.
  const seen = new Map<string, Set<string>>();

  const sub = vscode.languages.onDidChangeDiagnostics((event) => {
    for (const uri of event.uris) {
      processUri(uri, seen);
    }
  });
  context.subscriptions.push(sub);
  log.info('DiagnosticsListener installed');
}

function processUri(uri: vscode.Uri, seen: Map<string, Set<string>>): void {
  const redacted = PathRedactor.redact(uri.fsPath);
  if (!redacted) return;

  const diagnostics = vscode.languages.getDiagnostics(uri);
  const currentPass = new Set<string>();
  const prev = seen.get(uri.toString()) ?? new Set<string>();

  for (const diag of diagnostics) {
    if (
      diag.severity !== vscode.DiagnosticSeverity.Error &&
      diag.severity !== vscode.DiagnosticSeverity.Warning
    ) {
      continue;
    }
    const digest = digestOf(diag);
    currentPass.add(digest);
    if (!prev.has(digest)) {
      emitOne(redacted, diag, uri);
    }
  }

  seen.set(uri.toString(), currentPass);
}

function digestOf(diag: vscode.Diagnostic): string {
  const severityName = severityName_(diag.severity);
  return `${diag.range.start.line}:${severityName}:${diag.message}`;
}

function severityName_(s: vscode.DiagnosticSeverity): string {
  switch (s) {
    case vscode.DiagnosticSeverity.Error: return 'ERROR';
    case vscode.DiagnosticSeverity.Warning: return 'WARNING';
    case vscode.DiagnosticSeverity.Information: return 'INFO';
    case vscode.DiagnosticSeverity.Hint: return 'HINT';
    default: return 'UNKNOWN';
  }
}

function emitOne(redactedFile: string, diag: vscode.Diagnostic, uri: vscode.Uri): void {
  const inspectionId = typeof diag.code === 'object' && diag.code !== null
    ? String(diag.code.value)
    : (diag.code != null ? String(diag.code) : null);

  emit({
    type: EventType.INSPECTION,
    file: redactedFile,
    language: languageOf(uri),
    payload: {
      severity: severityName_(diag.severity),
      description: diag.message.slice(0, 200),
      line: diag.range.start.line,
      inspection_id: inspectionId,
    },
  });
}

function languageOf(uri: vscode.Uri): string | null {
  const ext = uri.path.split('.').pop()?.toLowerCase();
  switch (ext) {
    case 'py': return 'python';
    case 'kt':
    case 'kts': return 'kotlin';
    case 'java': return 'java';
    case 'ts':
    case 'tsx': return 'typescript';
    case 'js':
    case 'jsx': return 'javascript';
    default: return ext ?? null;
  }
}
