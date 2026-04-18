import * as vscode from 'vscode';
import type { DeveloperEvent } from '../model/events';
import { EventBus } from '../detector/eventBus';

const RECENT_LIMIT = 50;

/**
 * Tree view "RubberDuck Live · Recent Events" backed by the in-process
 * event bus. Newest event on top.
 */
export class EventsViewProvider implements vscode.TreeDataProvider<EventNode> {
  private readonly _onDidChange = new vscode.EventEmitter<EventNode | undefined | void>();
  readonly onDidChangeTreeData = this._onDidChange.event;
  private readonly events: DeveloperEvent[] = [];

  constructor(context: vscode.ExtensionContext) {
    const unsub = EventBus.getInstance().subscribe((ev) => {
      this.events.unshift(ev);
      while (this.events.length > RECENT_LIMIT) this.events.pop();
      this._onDidChange.fire();
    });
    context.subscriptions.push(new vscode.Disposable(() => unsub()));
  }

  getTreeItem(node: EventNode): vscode.TreeItem {
    return node;
  }

  getChildren(): EventNode[] {
    return this.events.map((e) => new EventNode(e));
  }
}

class EventNode extends vscode.TreeItem {
  constructor(event: DeveloperEvent) {
    const time = new Date(event.ts).toLocaleTimeString();
    const file = event.file ?? '—';
    super(`${time} · ${event.type}`, vscode.TreeItemCollapsibleState.None);
    this.description = file;
    this.tooltip = JSON.stringify(event.payload, null, 2);
    this.contextValue = event.type;
  }
}
