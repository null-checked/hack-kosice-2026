import { randomUUID } from 'node:crypto';
import * as vscode from 'vscode';
import { EventType } from '../model/events';
import { emit } from '../detector/eventEmitter';

const SOCRATIC_FEED_LIMIT = 20;
type FeedEntry = { ts: number; rule: string; question: string };
const feed: FeedEntry[] = [];
const feedListeners: Array<() => void> = [];

export function getSocraticFeed(): readonly FeedEntry[] {
  return feed;
}

export function onSocraticFeedChanged(handler: () => void): vscode.Disposable {
  feedListeners.push(handler);
  return new vscode.Disposable(() => {
    const i = feedListeners.indexOf(handler);
    if (i >= 0) feedListeners.splice(i, 1);
  });
}

export function pushSocratic(rule: string, question: string): void {
  feed.unshift({ ts: Date.now(), rule, question });
  while (feed.length > SOCRATIC_FEED_LIMIT) feed.pop();
  for (const h of feedListeners) {
    try { h(); } catch { /* ignore */ }
  }
}

/**
 * Show a non-modal notification offering "Ask me" / "Not now". Returns the
 * `interventionId` so the coordinator can correlate accept/dismiss events.
 */
export async function showInvitation(
  stuckReason: string,
  onAccept: () => void,
  onDismiss: () => void,
): Promise<string> {
  const interventionId = randomUUID();
  emit({
    type: EventType.INTERVENTION_SHOWN,
    payload: { intervention_id: interventionId, kind: 'socratic', rule: stuckReason },
  });
  // showInformationMessage returns the chosen action label, or undefined on dismissal.
  const choice = await vscode.window.showInformationMessage(
    `🦆 RubberDuck noticed a "${stuckReason}" pattern. Want a rubber-duck question?`,
    'Ask me',
    'Not now',
  );
  if (choice === 'Ask me') {
    emit({
      type: EventType.INTERVENTION_ACCEPTED,
      payload: { intervention_id: interventionId, kind: 'socratic', rule: stuckReason },
    });
    onAccept();
  } else {
    // 'Not now' OR dismissed (undefined) → both treated as dismissal.
    emit({
      type: EventType.INTERVENTION_DISMISSED,
      payload: { intervention_id: interventionId, kind: 'socratic', rule: stuckReason },
    });
    onDismiss();
  }
  return interventionId;
}

export function showInfo(title: string, message: string): void {
  void vscode.window.showInformationMessage(`${title}: ${message}`);
}
