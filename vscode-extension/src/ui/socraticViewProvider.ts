import * as vscode from 'vscode';
import { getSocraticFeed, onSocraticFeedChanged } from '../intervention/socraticNotifier';

/** Tree view "RubberDuck Live · Socratic Feed" — the questions the duck asked. */
export class SocraticViewProvider implements vscode.TreeDataProvider<SocraticNode> {
  private readonly _onDidChange = new vscode.EventEmitter<SocraticNode | undefined | void>();
  readonly onDidChangeTreeData = this._onDidChange.event;

  constructor(context: vscode.ExtensionContext) {
    const sub = onSocraticFeedChanged(() => this._onDidChange.fire());
    context.subscriptions.push(sub);
  }

  getTreeItem(node: SocraticNode): vscode.TreeItem {
    return node;
  }

  getChildren(): SocraticNode[] {
    return getSocraticFeed().map((entry) => new SocraticNode(entry.ts, entry.rule, entry.question));
  }
}

class SocraticNode extends vscode.TreeItem {
  constructor(ts: number, rule: string, question: string) {
    const time = new Date(ts).toLocaleTimeString();
    super(`${time} · ${rule}`, vscode.TreeItemCollapsibleState.None);
    this.description = question;
    this.tooltip = question;
  }
}
