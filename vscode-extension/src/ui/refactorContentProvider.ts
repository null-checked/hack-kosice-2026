import * as vscode from 'vscode';

/**
 * Registers a `rubberduck:` text document content provider used by
 * `vscode.diff` in the Suggest Refactor command. The refactored code is
 * served as a virtual document so VS Code's native diff viewer can render
 * it side-by-side with the original.
 */
export const RUBBERDUCK_SCHEME = 'rubberduck';

const contentByUri = new Map<string, string>();

class Provider implements vscode.TextDocumentContentProvider {
  private readonly emitter = new vscode.EventEmitter<vscode.Uri>();
  readonly onDidChange = this.emitter.event;

  provideTextDocumentContent(uri: vscode.Uri): string {
    return contentByUri.get(uri.toString()) ?? '';
  }

  notifyChanged(uri: vscode.Uri): void {
    this.emitter.fire(uri);
  }
}

let provider: Provider | undefined;

export function registerRefactorContent(context: vscode.ExtensionContext): void {
  if (provider) return;
  provider = new Provider();
  context.subscriptions.push(
    vscode.workspace.registerTextDocumentContentProvider(RUBBERDUCK_SCHEME, provider),
  );
}

export function setRefactorContent(uri: vscode.Uri, content: string): void {
  contentByUri.set(uri.toString(), content);
  provider?.notifyChanged(uri);
}
