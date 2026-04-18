import * as vscode from 'vscode';

import { BackendClient } from '../net/backendClient';
import { Config } from '../settings/config';
import { PathRedactor } from '../util/pathRedactor';
import { showInfo } from '../intervention/socraticNotifier';
import { log } from '../util/logger';

/**
 * `rubberDuck.explainOldCode` — mirrors the IntelliJ plugin's
 * [`ExplainOldCodeAction.kt`](../../../plugin/src/main/kotlin/com/rubberduck/live/actions/ExplainOldCodeAction.kt:1).
 *
 * Takes the current selection (or the enclosing function-ish block inferred
 * from the caret line) and POSTs to `/api/v1/explain-code`. Shows the
 * response in a modal message box.
 *
 * Respects the strict-privacy gate — the command is a no-op when privacy
 * mode is on.
 */
export function registerExplainOldCodeCommand(context: vscode.ExtensionContext): void {
  const cmd = vscode.commands.registerCommand('rubberDuck.explainOldCode', async () => {
    if (Config.privacyMode) {
      showInfo(
        'RubberDuck Live — privacy mode',
        'Strict privacy is on — "Explain old code" is disabled because it would send code to the backend.',
      );
      return;
    }
    const editor = vscode.window.activeTextEditor;
    if (!editor) {
      void vscode.window.showWarningMessage('🦆 RubberDuck: open a file first.');
      return;
    }
    const doc = editor.document;
    const code = editor.selection.isEmpty
      ? guessFunctionAt(doc, editor.selection.active.line)
      : doc.getText(editor.selection);
    if (!code || code.trim().length === 0) {
      void vscode.window.showInformationMessage(
        '🦆 RubberDuck: no function detected. Select some code and try again.',
      );
      return;
    }
    const redacted = PathRedactor.redact(doc.uri.fsPath);

    await vscode.window.withProgress(
      { location: vscode.ProgressLocation.Notification, title: '🦆 Explaining code…' },
      async () => {
        try {
          const response = await BackendClient.getInstance().explainCode({
            code,
            file_path: redacted,
            language: doc.languageId,
            context: '',
          });
          const suggestions = response.suggestions.length > 0
            ? response.suggestions.map((s) => `• ${s}`).join('\n')
            : '(none)';
          const message =
            `${response.explanation}\n\n` +
            `Complexity: ${response.complexity}\n\n` +
            `Suggestions:\n${suggestions}`;
          await vscode.window.showInformationMessage(message, { modal: true });
        } catch (err) {
          log.warn(`explain-code failed: ${err instanceof Error ? err.message : String(err)}`);
          void vscode.window.showErrorMessage(
            `RubberDuck: Explain Old Code failed — ${err instanceof Error ? err.message : String(err)}`,
          );
        }
      },
    );
  });

  context.subscriptions.push(cmd);
  log.info('rubberDuck.explainOldCode command registered');
}

/**
 * Cheap function-boundary heuristic. For Python we walk up to the nearest
 * `def`/`class` and down until the indent drops. For brace-based languages
 * we walk up to the nearest non-indented line that looks like a header
 * then brace-match to find the end. Doesn't try to be perfect — if it
 * misses, the user can always select explicitly.
 */
function guessFunctionAt(doc: vscode.TextDocument, line: number): string {
  if (doc.languageId === 'python') {
    const headerRe = /^\s*(def|class)\s+\w/;
    let headerLine = line;
    while (headerLine >= 0 && !headerRe.test(doc.lineAt(headerLine).text)) headerLine--;
    if (headerLine < 0) return doc.lineAt(line).text;
    const headerIndent = leadingSpaces(doc.lineAt(headerLine).text);
    let endLine = headerLine;
    for (let i = headerLine + 1; i < doc.lineCount; i++) {
      const text = doc.lineAt(i).text;
      if (text.trim().length === 0) continue;
      if (leadingSpaces(text) <= headerIndent) break;
      endLine = i;
    }
    return doc.getText(
      new vscode.Range(
        new vscode.Position(headerLine, 0),
        new vscode.Position(endLine, doc.lineAt(endLine).text.length),
      ),
    );
  }

  // Brace-based fallback: find nearest "function-ish" header above, then
  // match braces. Good enough for JS/TS/Java/Kotlin.
  const headerRe = /\b(function|fun|def|class|public|private|protected|async|export)\b/;
  let headerLine = line;
  while (headerLine >= 0 && !headerRe.test(doc.lineAt(headerLine).text)) headerLine--;
  if (headerLine < 0) headerLine = line;

  let depth = 0;
  let endLine = headerLine;
  let sawOpen = false;
  for (let i = headerLine; i < doc.lineCount; i++) {
    const text = doc.lineAt(i).text;
    for (const ch of text) {
      if (ch === '{') { depth++; sawOpen = true; }
      else if (ch === '}') { depth--; if (sawOpen && depth === 0) { endLine = i; return doc.getText(new vscode.Range(new vscode.Position(headerLine, 0), new vscode.Position(endLine, doc.lineAt(endLine).text.length))); } }
    }
  }
  // No braces found — just return the current line.
  return doc.lineAt(line).text;
}

function leadingSpaces(s: string): number {
  let n = 0;
  for (const c of s) {
    if (c === ' ') n++;
    else if (c === '\t') n += 4;
    else break;
  }
  return n;
}
