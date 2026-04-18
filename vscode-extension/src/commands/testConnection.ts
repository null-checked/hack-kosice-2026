import * as vscode from 'vscode';
import { BackendClient } from '../net/backendClient';
import { log } from '../util/logger';

/**
 * `rubberduck.testConnection` — pings `/health` and surfaces a toast with
 * provider/model/version info. Useful for the Setup Guide's troubleshooting.
 */
export function registerTestConnectionCommand(context: vscode.ExtensionContext): void {
  const cmd = vscode.commands.registerCommand('rubberduck.testConnection', async () => {
    try {
      const h = await BackendClient.getInstance().health();
      void vscode.window.showInformationMessage(
        `🦆 Backend OK · ${h.llm_provider}/${h.llm_model} · v${h.version}${h.demo_mock ? ' · MOCK' : ''}`,
      );
    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err);
      log.warn(`Health probe failed: ${msg}`);
      void vscode.window.showErrorMessage(`🦆 Backend unreachable: ${msg}`);
    }
  });
  context.subscriptions.push(cmd);
}
