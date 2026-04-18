import * as path from 'node:path';
import * as vscode from 'vscode';
import { randomUUID } from 'node:crypto';
import { EventType } from '../model/events';
import type { GenerateTestsRequest } from '../model/api';
import { BackendClient } from '../net/backendClient';
import { Config } from '../settings/config';
import { emit } from '../detector/eventEmitter';
import { extractFunction, languageFor } from '../util/codeSelection';
import { PathRedactor } from '../util/pathRedactor';
import { showInfo } from '../intervention/socraticNotifier';
import { log } from '../util/logger';

/**
 * `rubberduck.generateTests`. Mirrors
 * [`GenerateTestsAction.kt`](../../../plugin/src/main/kotlin/com/rubberduck/live/actions/GenerateTestsAction.kt:1):
 * extract the function under caret, POST to `/generate-tests`, then write a
 * sibling `test_<module>.<ext>` and open it.
 */
export function registerGenerateTestsCommand(context: vscode.ExtensionContext): void {
  const cmd = vscode.commands.registerCommand('rubberduck.generateTests', async () => {
    if (Config.privacyMode) {
      showInfo(
        'RubberDuck Live — privacy mode',
        'Strict privacy is on — Generate Tests is disabled because it would send code to the backend.',
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
    const language = languageFor(doc.languageId, doc.fileName);
    const wholeText = doc.getText();
    const caretOffset = doc.offsetAt(editor.selection.active);
    const selection = extractFunction(wholeText, caretOffset, language);
    const code = selection.code.trim().length === 0 ? wholeText : selection.code;
    const redactedFile = PathRedactor.redactUri(doc.uri);

    const request: GenerateTestsRequest = {
      language,
      code,
      function_name: selection.functionName ?? null,
      file_path: redactedFile ?? null,
    };
    const interventionId = randomUUID();
    emit({
      type: EventType.INTERVENTION_SHOWN,
      file: redactedFile,
      payload: { intervention_id: interventionId, kind: 'tests' },
    });

    await vscode.window.withProgress(
      { location: vscode.ProgressLocation.Notification, title: '🦆 Generating tests…', cancellable: false },
      async () => {
        try {
          const response = await BackendClient.getInstance().generateTests(request);
          const targetUri = await writeSibling(doc.uri, response.test_file_name, response.test_code);
          if (targetUri) {
            const openedDoc = await vscode.workspace.openTextDocument(targetUri);
            await vscode.window.showTextDocument(openedDoc, { preview: false });
          }
          emit({
            type: EventType.INTERVENTION_ACCEPTED,
            file: redactedFile,
            payload: { intervention_id: interventionId, kind: 'tests' },
          });
          showInfo('✅ Tests generated', response.reasoning.slice(0, 200));
        } catch (err) {
          log.warn(`Test generation failed: ${err instanceof Error ? err.message : String(err)}`);
          showInfo('RubberDuck Live', `Test generation failed: ${err instanceof Error ? err.message : String(err)}`);
        }
      },
    );
  });

  context.subscriptions.push(cmd);
}

async function writeSibling(sourceUri: vscode.Uri, fileName: string, content: string): Promise<vscode.Uri | null> {
  const dir = path.dirname(sourceUri.fsPath);
  const safeName = fileName.length > 0 ? fileName : `test_${path.basename(sourceUri.fsPath)}`;
  const targetUri = vscode.Uri.file(path.join(dir, safeName));
  try {
    const buffer = Buffer.from(content, 'utf8');
    await vscode.workspace.fs.writeFile(targetUri, buffer);
    return targetUri;
  } catch (err) {
    log.warn(`Failed to write ${targetUri.fsPath}: ${err instanceof Error ? err.message : String(err)}`);
    return null;
  }
}
