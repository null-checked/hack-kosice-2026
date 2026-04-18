import * as vscode from 'vscode';

/**
 * Socratic notification helpers.
 *
 * v0.3: the old `showInvitation` (`Ask me / Not now`) flow was removed in
 * favor of passive balloons. The coordinator now calls `showInfo` directly
 * and drives the feed via `pushSocratic`.
 */

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

/** Show a plain info balloon. Used by the passive Socratic flow and by
 *  action-command handlers for success/failure toasts. */
export function showInfo(title: string, message: string): void {
  void vscode.window.showInformationMessage(`${title}: ${message}`);
}
