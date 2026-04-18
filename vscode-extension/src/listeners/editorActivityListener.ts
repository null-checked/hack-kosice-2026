import * as vscode from 'vscode';
import { EventType } from '../model/events';
import { emit } from '../detector/eventEmitter';
import { PathRedactor } from '../util/pathRedactor';
import { languageFor } from '../util/codeSelection';
import { SessionContext } from '../detector/sessionContext';

/**
 * Captures document edits + undo/redo via [`onDidChangeTextDocument`]. Mirrors
 * [`EditorActivityListener.kt`](../../../plugin/src/main/kotlin/com/rubberduck/live/listeners/EditorActivityListener.kt:1).
 *
 * Implementation notes specific to VS Code:
 * - `TextDocumentChangeReason.Undo` / `.Redo` is set when the change came
 *   from the IDE's undo stack. Anything else (including no reason) is a
 *   plain EDIT.
 * - **Bulk-edit suppression**: if `event.contentChanges.length > 5`, treat
 *   the whole change as a single paste/refactor — emit ONE EDIT.
 * - We ignore changes to virtual schemes (e.g. `output:`, `git:`,
 *   `rubberduck:`).
 */
export function registerEditorActivityListener(context: vscode.ExtensionContext): void {
  const sub = vscode.workspace.onDidChangeTextDocument((event) => {
    const doc = event.document;
    if (doc.uri.scheme !== 'file') return;
    if (event.contentChanges.length === 0) return;

    const file = PathRedactor.redactUri(doc.uri);
    if (!file) return;

    if (event.reason === vscode.TextDocumentChangeReason.Undo) {
      emit({
        type: EventType.UNDO,
        file,
        payload: { line: event.contentChanges[0]?.range.start.line ?? 0 },
      });
      return;
    }
    if (event.reason === vscode.TextDocumentChangeReason.Redo) {
      emit({
        type: EventType.REDO,
        file,
        payload: { line: event.contentChanges[0]?.range.start.line ?? 0 },
      });
      return;
    }

    // Aggregate plain edits (incl. bulk-edit suppression).
    let added = 0;
    let removed = 0;
    for (const change of event.contentChanges) {
      added += change.text.length;
      removed += change.rangeLength;
    }
    const firstChange = event.contentChanges[0];
    const line = firstChange?.range.start.line ?? 0;
    emit({
      type: EventType.EDIT,
      file,
      language: languageFor(doc.languageId, doc.fileName),
      payload: {
        chars_added: added,
        chars_removed: removed,
        line,
        bulk: event.contentChanges.length > 5,
      },
    });
    SessionContext.getInstance().activeFile = file;
  });

  context.subscriptions.push(sub);
}
