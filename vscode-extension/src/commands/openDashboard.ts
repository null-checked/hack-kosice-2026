import * as vscode from 'vscode';
import { Config } from '../settings/config';

/**
 * `rubberduck.openDashboard` — open the configured dashboard URL in the
 * external browser.
 */
export function registerOpenDashboardCommand(context: vscode.ExtensionContext): void {
  const cmd = vscode.commands.registerCommand('rubberduck.openDashboard', async () => {
    const url = Config.dashboardUrl;
    await vscode.env.openExternal(vscode.Uri.parse(url));
  });
  context.subscriptions.push(cmd);
}
