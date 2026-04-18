import * as vscode from 'vscode';

export type InterventionFrequency = 'MINIMAL' | 'NORMAL' | 'CHATTY';

/**
 * Typed wrapper around `vscode.workspace.getConfiguration('rubberduckLive')`.
 * Mirrors [`RubberDuckSettings.kt`](../../../plugin/src/main/kotlin/com/rubberduck/live/settings/RubberDuckSettings.kt:1).
 */
export class Config {
  private static section(): vscode.WorkspaceConfiguration {
    return vscode.workspace.getConfiguration('rubberduckLive');
  }

  static get backendUrl(): string {
    return Config.section().get<string>('backendUrl', 'http://127.0.0.1:8765').replace(/\/+$/, '');
  }

  static get backendApiBase(): string {
    return `${Config.backendUrl}/api/v1`;
  }

  static get backendWsUrl(): string {
    // http://host -> ws://host  ; https://host -> wss://host
    const base = Config.backendApiBase;
    const wsBase = base.startsWith('https://')
      ? `wss://${base.slice('https://'.length)}`
      : `ws://${base.slice(base.indexOf('://') + 3)}`;
    return `${wsBase}/events`;
  }

  static get sendEventsToDashboard(): boolean {
    return Config.section().get<boolean>('sendEventsToDashboard', true);
  }

  static get interventionFrequency(): InterventionFrequency {
    const raw = Config.section().get<string>('interventionFrequency', 'NORMAL');
    if (raw === 'MINIMAL' || raw === 'NORMAL' || raw === 'CHATTY') return raw;
    return 'NORMAL';
  }

  static get privacyMode(): boolean {
    return Config.section().get<boolean>('privacyMode', false);
  }

  static get dashboardUrl(): string {
    return Config.section().get<string>('dashboardUrl', 'http://localhost:3000');
  }

  /** Subscribe to changes under the `rubberduckLive` namespace. */
  static onDidChange(handler: () => void): vscode.Disposable {
    return vscode.workspace.onDidChangeConfiguration((e) => {
      if (e.affectsConfiguration('rubberduckLive')) handler();
    });
  }
}
