import * as vscode from 'vscode';

import { Config } from '../settings/config';
import { log } from '../util/logger';

/**
 * Opens the RubberDuck Live dashboard inside a VSCode webview panel.
 *
 * The panel hosts a single `<iframe>` pointed at the Next.js dashboard
 * URL (configured via `rubberduckLive.dashboardUrl`, default
 * `http://localhost:3000`). Same function as the IntelliJ plugin's JCEF
 * dashboard tab — users can see wellness score / heatmap / anti-patterns
 * without leaving the editor.
 *
 * Trade-off vs. `openDashboard` (external browser): the webview feels
 * more integrated but has stricter CSP rules. We allow the configured
 * dashboard URL as a frame-src explicitly.
 */
const PANEL_VIEW_TYPE = 'rubberduck.dashboardPanel';
let currentPanel: vscode.WebviewPanel | undefined;

export function registerShowDashboardCommand(context: vscode.ExtensionContext): void {
  const sub = vscode.commands.registerCommand('rubberDuck.showDashboard', () => {
    openDashboardPanel();
  });
  context.subscriptions.push(sub);
  log.info('rubberDuck.showDashboard command registered');
}

/**
 * Sidebar tab version — lives next to the Chat tab under the RubberDuck
 * activity-bar container. Smaller than the full-panel view but always on
 * screen. Re-renders when the dashboard URL changes in settings.
 */
export class DashboardViewProvider implements vscode.WebviewViewProvider {
  public static readonly VIEW_TYPE = 'rubberduck.dashboard';
  private view: vscode.WebviewView | undefined;

  resolveWebviewView(
    webviewView: vscode.WebviewView,
    _context: vscode.WebviewViewResolveContext,
    _token: vscode.CancellationToken,
  ): void {
    this.view = webviewView;
    webviewView.webview.options = { enableScripts: true, localResourceRoots: [] };
    webviewView.webview.html = renderHtml(Config.dashboardUrl);
  }

  refresh(): void {
    if (this.view) this.view.webview.html = renderHtml(Config.dashboardUrl);
  }
}

export function registerDashboardViewProvider(context: vscode.ExtensionContext): void {
  const provider = new DashboardViewProvider();
  context.subscriptions.push(
    vscode.window.registerWebviewViewProvider(DashboardViewProvider.VIEW_TYPE, provider),
    Config.onDidChange(() => provider.refresh()),
  );
  log.info('DashboardViewProvider registered');
}

function openDashboardPanel(): void {
  const dashboardUrl = Config.dashboardUrl;

  if (currentPanel) {
    currentPanel.reveal(vscode.ViewColumn.Two);
    // Re-fresh the iframe src in case the URL changed.
    currentPanel.webview.html = renderHtml(dashboardUrl);
    return;
  }

  currentPanel = vscode.window.createWebviewPanel(
    PANEL_VIEW_TYPE,
    'RubberDuck Live — Dashboard',
    vscode.ViewColumn.Two,
    {
      enableScripts: true,
      retainContextWhenHidden: true,
      localResourceRoots: [],
    },
  );
  currentPanel.webview.html = renderHtml(dashboardUrl);
  currentPanel.onDidDispose(() => { currentPanel = undefined; });
}

function renderHtml(dashboardUrl: string): string {
  // Escape the URL for attribute use.
  const safeUrl = dashboardUrl.replace(/"/g, '&quot;');
  // CSP: allow the dashboard origin in frame-src. Style + script are inline
  // to keep the bundle tiny; unsafe-inline on style is fine for a harness page.
  const csp =
    `default-src 'none'; ` +
    `style-src 'unsafe-inline'; ` +
    `frame-src ${escapeCspValue(dashboardUrl)}; ` +
    `script-src 'nonce-dashboard';`;
  return `<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta http-equiv="Content-Security-Policy" content="${csp}">
<title>RubberDuck Dashboard</title>
<style>
  html, body { margin: 0; padding: 0; height: 100%; background: var(--vscode-editor-background); }
  .bar { padding: 4px 8px; font: 11px/1.4 var(--vscode-font-family); color: var(--vscode-descriptionForeground); background: var(--vscode-editorWidget-background); border-bottom: 1px solid var(--vscode-panel-border); }
  iframe { width: 100%; height: calc(100% - 24px); border: 0; }
</style>
</head>
<body>
  <div class="bar">Loading ${safeUrl}  ·  if blank, confirm the dashboard is running.</div>
  <iframe src="${safeUrl}" sandbox="allow-scripts allow-same-origin allow-forms"></iframe>
</body>
</html>`;
}

function escapeCspValue(s: string): string {
  // Strip trailing slash; extract origin only for frame-src.
  try {
    const u = new URL(s);
    return `${u.protocol}//${u.host}`;
  } catch {
    return "'self'";
  }
}
