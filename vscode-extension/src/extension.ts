import * as vscode from 'vscode';
import { log } from './util/logger';
import { EventLog } from './storage/eventLog';
import { getStuckDetector } from './detector/stuckDetector';
import { EventsWebSocket } from './net/eventsWebSocket';
import { registerEditorActivityListener } from './listeners/editorActivityListener';
import { registerFileFocusListener } from './listeners/fileFocusListener';
import { registerCaretDwellTracker } from './listeners/caretDwellTracker';
import { registerTestExecutionListener } from './listeners/testExecutionListener';
import { registerGenerateTestsCommand } from './commands/generateTests';
import { registerSuggestRefactorCommand } from './commands/suggestRefactor';
import { registerOpenDashboardCommand } from './commands/openDashboard';
import { registerTestConnectionCommand } from './commands/testConnection';
import { registerStatusBarItem } from './ui/statusBarItem';
import { EventsViewProvider } from './ui/eventsViewProvider';
import { SocraticViewProvider } from './ui/socraticViewProvider';

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

  // 4) Commands
  registerGenerateTestsCommand(context);
  registerSuggestRefactorCommand(context);
  registerOpenDashboardCommand(context);
  registerTestConnectionCommand(context);

  // 5) UI: status bar + two tree views.
  registerStatusBarItem(context);
  const eventsView = new EventsViewProvider(context);
  const socraticView = new SocraticViewProvider(context);
  context.subscriptions.push(
    vscode.window.registerTreeDataProvider('rubberduck.events', eventsView),
    vscode.window.registerTreeDataProvider('rubberduck.socratic', socraticView),
  );

  log.info(`🦆 RubberDuck Live — active. EventLog: ${EventLog.getInstance().currentMode()}`);
}

export function deactivate(): void {
  log.info('🦆 RubberDuck Live — deactivating');
}
