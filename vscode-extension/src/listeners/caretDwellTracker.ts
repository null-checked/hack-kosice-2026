import * as vscode from 'vscode';
import { EventType } from '../model/events';
import { emit } from '../detector/eventEmitter';
import { PathRedactor } from '../util/pathRedactor';

/**
 * Fires a `CARET_DWELL` event when the caret sits on the same line for ≥ 90 s.
 * Payload includes `has_error_marker`, computed from
 * `vscode.languages.getDiagnostics(uri)` filtered to `DiagnosticSeverity.Error`
 * intersecting the caret line. Mirrors
 * [`CaretDwellTracker.kt`](../../../plugin/src/main/kotlin/com/rubberduck/live/listeners/CaretDwellTracker.kt:1).
 *
 * One event per dwell period — we don't emit intermediate ticks.
 */
const DWELL_MS = 90_000;

export function registerCaretDwellTracker(context: vscode.ExtensionContext): void {
  let lastLine = -1;
  let lastUri: vscode.Uri | null = null;
  let timer: NodeJS.Timeout | null = null;

  function clear(): void {
    if (timer) { clearTimeout(timer); timer = null; }
  }

  function arm(uri: vscode.Uri, line: number): void {
    clear();
    timer = setTimeout(() => {
      const file = PathRedactor.redactUri(uri);
      if (!file) return;
      const hasError = hasErrorOnLine(uri, line);
      emit({
        type: EventType.CARET_DWELL,
        file,
        payload: { line, duration_ms: DWELL_MS, has_error_marker: hasError },
      });
    }, DWELL_MS);
  }

  const selSub = vscode.window.onDidChangeTextEditorSelection((event) => {
    const sel = event.selections[0];
    if (!sel) return;
    const editor = event.textEditor;
    if (editor.document.uri.scheme !== 'file') return;
    const line = sel.active.line;
    if (line === lastLine && lastUri && uriEqual(lastUri, editor.document.uri)) return;
    lastLine = line;
    lastUri = editor.document.uri;
    arm(editor.document.uri, line);
  });

  // Also clear when the active editor changes — caret position is no longer ours.
  const editorSub = vscode.window.onDidChangeActiveTextEditor(() => {
    lastLine = -1;
    lastUri = null;
    clear();
  });

  // Re-arm if diagnostics change while we're dwelling — the user might have
  // landed on the line BEFORE the diagnostic appeared.
  const diagSub = vscode.languages.onDidChangeDiagnostics((_e) => {
    if (lastUri && lastLine >= 0) {
      // Don't reset the timer — just let it run; severity is checked at fire time.
    }
  });

  context.subscriptions.push(selSub, editorSub, diagSub, new vscode.Disposable(() => clear()));
}

function uriEqual(a: vscode.Uri, b: vscode.Uri): boolean {
  return a.toString() === b.toString();
}

function hasErrorOnLine(uri: vscode.Uri, line: number): boolean {
  try {
    const diagnostics = vscode.languages.getDiagnostics(uri);
    return diagnostics.some(
      (d) =>
        d.severity === vscode.DiagnosticSeverity.Error &&
        d.range.start.line <= line &&
        d.range.end.line >= line,
    );
  } catch {
    return false;
  }
}
