import * as vscode from 'vscode';

/** Singleton VS Code OutputChannel — replaces `console.log` everywhere. */
let channel: vscode.OutputChannel | undefined;

export function getLogger(): vscode.OutputChannel {
  if (!channel) channel = vscode.window.createOutputChannel('RubberDuck Live');
  return channel;
}

function ts(): string {
  const d = new Date();
  return d.toISOString();
}

export const log = {
  info(msg: string): void {
    getLogger().appendLine(`[${ts()}] [INFO ] ${msg}`);
  },
  warn(msg: string): void {
    getLogger().appendLine(`[${ts()}] [WARN ] ${msg}`);
  },
  error(msg: string, err?: unknown): void {
    const tail = err instanceof Error ? `: ${err.message}` : err !== undefined ? `: ${String(err)}` : '';
    getLogger().appendLine(`[${ts()}] [ERROR] ${msg}${tail}`);
  },
  debug(msg: string): void {
    getLogger().appendLine(`[${ts()}] [DEBUG] ${msg}`);
  },
};
