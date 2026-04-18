import * as vscode from 'vscode';
import { EventType } from '../model/events';
import { EventBus } from '../detector/eventBus';
import { SessionContext } from '../detector/sessionContext';
import { EventsWebSocket } from '../net/eventsWebSocket';

/**
 * Bottom status bar item — `🦆 Live • <wellness>`.
 *
 * Wellness score starts at 100 and decays by 5 per `STUCK_DETECTED` event,
 * clamped to [0, 100]. It slowly recovers (+1 / minute) so a quiet hour
 * heals back to full. Click → `rubberduck.openDashboard`.
 */
export function registerStatusBarItem(context: vscode.ExtensionContext): void {
  const item = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Right, 0);
  item.command = 'rubberduck.openDashboard';
  item.tooltip = 'Open RubberDuck Live dashboard';
  item.show();
  context.subscriptions.push(item);

  const ctx = SessionContext.getInstance();
  function render(): void {
    const dot = EventsWebSocket.getInstance().isConnected() ? 'Live' : 'Offline';
    item.text = `🦆 ${dot} • ${ctx.wellnessScore}`;
  }
  render();

  // Decay on STUCK_DETECTED.
  const unsub = EventBus.getInstance().subscribe((ev) => {
    if (ev.type === EventType.STUCK_DETECTED) {
      ctx.wellnessScore = Math.max(0, ctx.wellnessScore - 5);
      render();
    }
  });
  context.subscriptions.push(new vscode.Disposable(() => unsub()));

  // Slow recovery + WS status repaint.
  const heal = setInterval(() => {
    ctx.wellnessScore = Math.min(100, ctx.wellnessScore + 1);
    render();
  }, 60_000);
  const wsSub = EventsWebSocket.getInstance().onStatusChange(() => render());
  context.subscriptions.push(
    new vscode.Disposable(() => clearInterval(heal)),
    new vscode.Disposable(() => wsSub()),
  );
}
