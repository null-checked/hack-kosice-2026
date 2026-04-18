import * as vscode from 'vscode';
import { log } from './util/logger';
import { EventLog } from './storage/eventLog';
import { getStuckDetector } from './detector/stuckDetector';
import { EventsWebSocket } from './net/eventsWebSocket';
import { registerEditorActivityListener } from './listeners/editorActivityListener';
import { registerFileFocusListener } from './listeners/fileFocusListener';
import { registerCaretDwellTracker } from './listeners/caretDwellTracker';
import { registerTestExecutionListener } from './listeners/testExecutionListener';
import { registerDiagnosticsListener } from './listeners/diagnosticsListener';
import { registerGenerateTestsCommand } from './commands/generateTests';
import { registerSuggestRefactorCommand } from './commands/suggestRefactor';
import { registerOpenDashboardCommand } from './commands/openDashboard';
import { registerTestConnectionCommand } from './commands/testConnection';
import { registerExplainOldCodeCommand } from './commands/explainOldCode';
import { registerStatusBarItem } from './ui/statusBarItem';
import { EventsViewProvider } from './ui/eventsViewProvider';
import { SocraticViewProvider } from './ui/socraticViewProvider';
import { registerChatWebviewProvider } from './ui/chatWebviewProvider';
import { registerShowDashboardCommand } from './ui/dashboardWebviewProvider';
import { registerInlineCompletionProvider } from './completion/inlineCompletionProvider';

/**
 * Extension entry point. Activates on `onStartupFinished`. We wire everything
 * up and let the disposable subscriptions handle teardown.
 */
export function activate(context: vscode.ExtensionContext): void {
  log.info('🦆 RubberDuck Live — activating');

  // 1) Storage
  EventLog.getInstance().init();
  context.subscriptions.push(new vscode.Disposable(() => EventLog.getInstance().shutdown()));

  // 2) Detector + WebSocket bridge
  const detector = getStuckDetector();
  detector.start();
  EventsWebSocket.getInstance().start();
  context.subscriptions.push(
    new vscode.Disposable(() => detector.stop()),
    new vscode.Disposable(() => EventsWebSocket.getInstance().stop()),
  );

  // 3) Listeners
  registerEditorActivityListener(context);
  registerFileFocusListener(context);
  registerCaretDwellTracker(context);
  registerTestExecutionListener(context);
  registerDiagnosticsListener(context);

  // 4) Commands
  registerGenerateTestsCommand(context);
  registerSuggestRefactorCommand(context);
  registerOpenDashboardCommand(context);
  registerTestConnectionCommand(context);
  registerExplainOldCodeCommand(context);
  registerShowDashboardCommand(context);

  // 5) UI: status bar + two tree views + the new Chat webview.
  registerStatusBarItem(context);
  const eventsView = new EventsViewProvider(context);
  const socraticView = new SocraticViewProvider(context);
  context.subscriptions.push(
    vscode.window.registerTreeDataProvider('rubberduck.events', eventsView),
    vscode.window.registerTreeDataProvider('rubberduck.socratic', socraticView),
  );
  registerChatWebviewProvider(context);

  // 6) Inline completion (ghost text).
  registerInlineCompletionProvider(context);

  log.info(`🦆 RubberDuck Live — active. EventLog: ${EventLog.getInstance().currentMode()}`);
}

export function deactivate(): void {
  log.info('🦆 RubberDuck Live — deactivating');
}
