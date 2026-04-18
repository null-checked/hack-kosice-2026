import * as vscode from 'vscode';
import { EventType } from '../model/events';
import { emit } from '../detector/eventEmitter';
import { PathRedactor } from '../util/pathRedactor';
import { SessionContext } from '../detector/sessionContext';

/** Mirrors [`FileFocusListener.kt`](../../../plugin/src/main/kotlin/com/rubberduck/live/listeners/FileFocusListener.kt:1). */
export function registerFileFocusListener(context: vscode.ExtensionContext): void {
  let lastFile: string | null = null;
  let lastFocusTs: number = Date.now();

  // Prime from current selection if any.
  const initial = vscode.window.activeTextEditor?.document.uri;
  if (initial && initial.scheme === 'file') {
    const p = PathRedactor.redactUri(initial);
    if (p) {
      SessionContext.getInstance().activeFile = p;
      lastFile = p;
    }
  }

  const sub = vscode.window.onDidChangeActiveTextEditor((editor) => {
    const newUri = editor?.document.uri;
    if (!newUri || newUri.scheme !== 'file') return;
    const newPath = PathRedactor.redactUri(newUri);
    if (!newPath) return;
    const now = Date.now();
    const prev = lastFile;
    const duration = prev !== null ? now - lastFocusTs : null;

    emit({
      type: EventType.FILE_FOCUS,
      file: newPath,
      payload: {
        previous_file: prev,
        duration_in_previous_ms: duration,
      },
    });
    SessionContext.getInstance().activeFile = newPath;
    lastFile = newPath;
    lastFocusTs = now;
  });

  context.subscriptions.push(sub);
}
