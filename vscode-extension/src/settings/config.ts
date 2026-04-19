import * as vscode from 'vscode';

export type InterventionFrequency = 'MINIMAL' | 'NORMAL' | 'CHATTY';

/**
 * Typed wrapper around the two namespaces RubberDuck uses in VS Code:
 *
 *   - `rubberduck.*`       — the canonical, forward-looking namespace
 *                            shared with the JetBrains plugin. New code
 *                            should read AND write here.
 *   - `rubberduckLive.*`   — legacy Phase-1 keys. We continue to read
 *                            these as a fallback for one version so
 *                            users upgrading don't lose their settings;
 *                            they're marked `deprecationMessage` in
 *                            `package.json` so the UI nudges users over.
 *
 * Mirrors
 * [`RubberDuckSettings.kt`](../../../plugin/src/main/kotlin/com/rubberduck/live/settings/RubberDuckSettings.kt:1).
 */
export class Config {
  private static rd(): vscode.WorkspaceConfiguration {
    return vscode.workspace.getConfiguration('rubberduck');
  }
  private static legacy(): vscode.WorkspaceConfiguration {
    return vscode.workspace.getConfiguration('rubberduckLive');
  }

  /**
   * Preferred-namespace accessor. If `rubberduck.<newKey>` is explicitly
   * set (user/workspace), use it; otherwise fall back to
   * `rubberduckLive.<legacyKey>` (then the provided default).
   */
  private static pick<T>(newKey: string, legacyKey: string, dflt: T): T {
    const cur = Config.rd().inspect<T>(newKey);
    const hasNew =
      cur !== undefined &&
      (cur.globalValue !== undefined ||
        cur.workspaceValue !== undefined ||
        cur.workspaceFolderValue !== undefined);
    if (hasNew) return Config.rd().get<T>(newKey, dflt);
    return Config.legacy().get<T>(legacyKey, dflt);
  }

  static get backendUrl(): string {
    // `rubberduck.backendBaseUrl` already includes `/api/v1` — strip it
    // so `backendUrl` remains the bare host (Phase-1 shape) and
    // `backendApiBase` appends the path exactly once.
    const newVal = Config.rd().inspect<string>('backendBaseUrl');
    const hasNew =
      newVal !== undefined &&
      (newVal.globalValue !== undefined ||
        newVal.workspaceValue !== undefined ||
        newVal.workspaceFolderValue !== undefined);
    if (hasNew) {
      const v = Config.rd().get<string>('backendBaseUrl', 'http://127.0.0.1:8765/api/v1').trim();
      return v.replace(/\/api\/v1\/*$/, '').replace(/\/+$/, '');
    }
    return Config.legacy()
      .get<string>('backendUrl', 'http://127.0.0.1:8765')
      .replace(/\/+$/, '');
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
    return Config.legacy().get<boolean>('sendEventsToDashboard', true);
  }

  static get interventionFrequency(): InterventionFrequency {
    const raw = Config.legacy().get<string>('interventionFrequency', 'NORMAL');
    if (raw === 'MINIMAL' || raw === 'NORMAL' || raw === 'CHATTY') return raw;
    return 'NORMAL';
  }

  static get privacyMode(): boolean {
    return Config.legacy().get<boolean>('privacyMode', false);
  }

  static get dashboardUrl(): string {
    return Config.pick<string>('dashboardBaseUrl', 'dashboardUrl', 'http://localhost:3000');
  }

  /** v0.3: ON by default — users who install the extension and type should
   *  see ghost text immediately. Opt-out via the setting. */
  static get enableInlineCompletion(): boolean {
    return Config.legacy().get<boolean>('enableInlineCompletion', true);
  }

  // ---------- Chat namespace (rubberduck.*) --------------------------------

  static get chatModel(): string {
    return Config.rd().get<string>('chatModel', 'gpt-4o-mini');
  }

  static get chatAvailableModels(): string[] {
    const v = Config.rd().get<string[]>('chatAvailableModels', []);
    if (Array.isArray(v) && v.length > 0) return v;
    return ['gpt-4o-mini', 'gpt-4o', 'claude-3-5-sonnet-20241022', 'llama3.1:8b', 'qwen2.5-coder:7b'];
  }

  static get chatMaxHistoryTokens(): number {
    return Config.rd().get<number>('chatMaxHistoryTokens', 8000);
  }

  static get chatAttachOpenFiles(): boolean {
    return Config.rd().get<boolean>('chatAttachOpenFiles', false);
  }

  static get chatAttachDiagnostics(): boolean {
    return Config.rd().get<boolean>('chatAttachDiagnostics', false);
  }

  /** Persist the chat model, writing to the canonical `rubberduck.*` key. */
  static async setChatModel(model: string): Promise<void> {
    if (!model) return;
    await Config.rd().update('chatModel', model, vscode.ConfigurationTarget.Global);
  }

  /** Subscribe to changes under *either* RubberDuck namespace. */
  static onDidChange(handler: () => void): vscode.Disposable {
    return vscode.workspace.onDidChangeConfiguration((e) => {
      if (e.affectsConfiguration('rubberduckLive') || e.affectsConfiguration('rubberduck')) {
        handler();
      }
    });
  }
}
