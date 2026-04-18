import * as vscode from 'vscode';
import { randomUUID } from 'node:crypto';
import { EventType } from '../model/events';
import type { SuggestRefactorRequest } from '../model/api';
import { BackendClient } from '../net/backendClient';
import { Config } from '../settings/config';
import { emit } from '../detector/eventEmitter';
import { languageFor } from '../util/codeSelection';
import { PathRedactor } from '../util/pathRedactor';
import { showInfo } from '../intervention/socraticNotifier';
import { log } from '../util/logger';
import { RUBBERDUCK_SCHEME, registerRefactorContent, setRefactorContent } from '../ui/refactorContentProvider';

/**
 * `rubberduck.suggestRefactor`. Mirrors
 * [`SuggestRefactorAction.kt`](../../../plugin/src/main/kotlin/com/rubberduck/live/actions/SuggestRefactorAction.kt:1):
 * send the file (or selection) to `/suggest-refactor`, then open VS Code's
 * native diff viewer (`vscode.diff`) on a virtual `rubberduck:` document.
 */
export function registerSuggestRefactorCommand(context: vscode.ExtensionContext): void {
  registerRefactorContent(context);

  const cmd = vscode.commands.registerCommand('rubberduck.suggestRefactor', async () => {
    if (Config.privacyMode) {
      showInfo(
        'RubberDuck Live — privacy mode',
        'Strict privacy is on — Suggest Refactor is disabled because it would send code to the backend.',
      );
      return;
    }
    const editor = vscode.window.activeTextEditor;
    if (!editor) {
      void vscode.window.showWarningMessage('🦆 RubberDuck: no active editor.');
      return;
    }
    const doc = editor.document;
    if (doc.uri.scheme !== 'file') {
      void vscode.window.showWarningMessage('🦆 RubberDuck: this file is virtual — save it first.');
      return;
    }
    const fullText = doc.getText();
    const sel = editor.selection;
    const range = sel.isEmpty
      ? null
      : { start_line: sel.start.line, end_line: sel.end.line };
    const redacted = PathRedactor.redactUri(doc.uri);
    const language = languageFor(doc.languageId, doc.fileName);
    const request: SuggestRefactorRequest = {
      language,
      code: fullText,
      file_path: redacted ?? null,
      selection_range: range,
    };
    const interventionId = randomUUID();
    emit({
      type: EventType.INTERVENTION_SHOWN,
      file: redacted,
      payload: { intervention_id: interventionId, kind: 'refactor' },
    });

    await vscode.window.withProgress(
      { location: vscode.ProgressLocation.Notification, title: '🦆 Analyzing & refactoring…', cancellable: false },
      async () => {
        try {
          const response = await BackendClient.getInstance().suggestRefactor(request);
          const virtualUri = vscode.Uri.parse(`${RUBBERDUCK_SCHEME}:/refactor/${encodeURIComponent(doc.fileName)}.suggested`);
          setRefactorContent(virtualUri, response.refactored_code);
          const summary = response.smells_detected
            .map((s) => `• ${s.type} (line ${s.line}): ${s.description}`)
            .join('\n');
          const title = `🦆 RubberDuck refactor — ${doc.fileName.split('/').pop() ?? 'file'}`;
          await vscode.commands.executeCommand('vscode.diff', doc.uri, virtualUri, title);
          if (summary.length > 0) showInfo('Smells detected', summary);
          emit({
            type: EventType.INTERVENTION_ACCEPTED,
            file: redacted,
            payload: { intervention_id: interventionId, kind: 'refactor' },
          });
        } catch (err) {
          log.warn(`Refactor request failed: ${err instanceof Error ? err.message : String(err)}`);
          showInfo('RubberDuck Live', `Refactor request failed: ${err instanceof Error ? err.message : String(err)}`);
        }
      },
    );
  });

  context.subscriptions.push(cmd);
}
