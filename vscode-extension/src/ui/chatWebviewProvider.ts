import * as vscode from 'vscode';
import * as path from 'path';
import { TextDecoder, TextEncoder } from 'util';

import { Config } from '../settings/config';
import { log } from '../util/logger';

/**
 * Chat sidebar, parity edition.
 *
 * Hosts the shared `chat-webui` bundle that lives under
 * `vscode-extension/media/chat/` (synced from the top-level `chat-webui/`
 * project — the same bytes the IntelliJ JCEF host loads). All IDE-specific
 * behavior is exposed to the web UI through a `window.rubberduck` bridge
 * injected via a bootstrap script: every call becomes a `postMessage` to
 * the extension host, and the host replies by request id.
 *
 * Bridge surface mirrors
 * [`ChatBridge.kt`](../../../plugin/src/main/kotlin/com/rubberduck/live/chat/ChatBridge.kt:1)
 * method-for-method, so the shared `app.js` runs unchanged in both IDEs.
 */
export class ChatWebviewProvider implements vscode.WebviewViewProvider {
  public static readonly VIEW_TYPE = 'rubberduck.chat';

  private view: vscode.WebviewView | undefined;
  private readonly disposables: vscode.Disposable[] = [];

  constructor(private readonly extensionUri: vscode.Uri) {}

  // ---------------------------------------------------------- Lifecycle

  async resolveWebviewView(
    webviewView: vscode.WebviewView,
    _context: vscode.WebviewViewResolveContext,
    _token: vscode.CancellationToken,
  ): Promise<void> {
    this.view = webviewView;

    const mediaRoot = vscode.Uri.joinPath(this.extensionUri, 'media');
    const roots: vscode.Uri[] = [mediaRoot];
    for (const folder of vscode.workspace.workspaceFolders ?? []) {
      roots.push(folder.uri);
    }

    webviewView.webview.options = {
      enableScripts: true,
      localResourceRoots: roots,
    };

    webviewView.webview.html = await this.renderHtml(webviewView.webview);

    // --- Bridge dispatcher -------------------------------------------------
    const onMessage = webviewView.webview.onDidReceiveMessage(
      (msg: BridgeMessage) => this.handleMessage(msg),
    );
    this.disposables.push(onMessage);

    // --- Theme sync --------------------------------------------------------
    this.postTheme();
    const themeSub = vscode.window.onDidChangeActiveColorTheme(() => this.postTheme());
    this.disposables.push(themeSub);

    webviewView.onDidDispose(() => this.dispose(), undefined, this.disposables);
  }

  private dispose(): void {
    while (this.disposables.length) {
      const d = this.disposables.pop();
      try {
        d?.dispose();
      } catch {
        // swallow — disposal is best-effort
      }
    }
    this.view = undefined;
  }

  // ------------------------------------------------------- HTML rendering

  private async renderHtml(webview: vscode.Webview): Promise<string> {
    const indexUri = vscode.Uri.joinPath(this.extensionUri, 'media', 'chat', 'index.html');
    const raw = await vscode.workspace.fs.readFile(indexUri);
    let html = new TextDecoder('utf-8').decode(raw);

    const nonce = randomNonce();
    const cspSource = webview.cspSource;

    // Rewrite local resource URLs → vscode-webview URIs.
    const toWebviewUri = (rel: string): string => {
      const abs = vscode.Uri.joinPath(this.extensionUri, 'media', 'chat', ...rel.split('/'));
      return webview.asWebviewUri(abs).toString();
    };

    // href="..." / src="..." for styles.css, app.js, vendor/*
    html = html.replace(
      /\b(href|src)="(?!https?:|data:|vscode-webview:)([^"#?]+)"/g,
      (_m, attr: string, url: string) => `${attr}="${toWebviewUri(url)}"`,
    );

    // Inject nonce on every <script> tag so the CSP script-src allows them.
    html = html.replace(/<script\b([^>]*)>/g, (_m, attrs: string) => {
      if (/\bnonce=/.test(attrs)) return `<script${attrs}>`;
      return `<script${attrs} nonce="${nonce}">`;
    });

    // Bootstrap — must run BEFORE app.js so window.rubberduck is populated
    // when app.js executes. index.html uses <script defer> on all scripts
    // which execute in document order, so we simply emit the bootstrap as
    // another <script defer nonce=...> and place it right before the
    // <script defer src="...app.js"> tag.
    const bootstrap = this.bootstrapScript();
    const bootstrapTag = `<script defer nonce="${nonce}">${bootstrap}</script>`;
    const appJsTagRegex = /<script\b[^>]*src="[^"]*app\.js"[^>]*>\s*<\/script>/;
    if (appJsTagRegex.test(html)) {
      html = html.replace(appJsTagRegex, (m) => `${bootstrapTag}\n${m}`);
    } else {
      html = html.replace('</body>', `${bootstrapTag}\n</body>`);
    }

    // Inject CSP meta tag (replace any existing one, or prepend in <head>).
    const csp =
      `default-src 'none'; ` +
      `img-src ${cspSource} data:; ` +
      `script-src 'nonce-${nonce}' ${cspSource}; ` +
      `style-src ${cspSource} 'unsafe-inline'; ` +
      `font-src ${cspSource}; ` +
      `connect-src http://127.0.0.1:* http://localhost:*;`;
    const cspMeta = `<meta http-equiv="Content-Security-Policy" content="${csp}">`;
    if (/<meta[^>]+http-equiv="Content-Security-Policy"[^>]*>/i.test(html)) {
      html = html.replace(/<meta[^>]+http-equiv="Content-Security-Policy"[^>]*>/i, cspMeta);
    } else {
      html = html.replace(/<head(\s[^>]*)?>/i, (m) => `${m}\n${cspMeta}`);
    }

    return html;
  }

  /** JS injected before app.js to expose the `window.rubberduck` bridge. */
  private bootstrapScript(): string {
    // Kept as a template string. No `${}` from host side — must stay inert.
    return `
(() => {
  const vscode = acquireVsCodeApi();
  const pending = new Map();
  let seq = 0;
  function call(method, args) {
    return new Promise((resolve, reject) => {
      const id = ++seq;
      pending.set(id, { resolve, reject });
      vscode.postMessage({ kind: 'bridge', id, method, args: args || [] });
    });
  }
  window.addEventListener('message', (ev) => {
    const m = ev.data;
    if (!m) return;
    if (m.kind === 'bridge-reply' && pending.has(m.id)) {
      const { resolve, reject } = pending.get(m.id);
      pending.delete(m.id);
      if (m.ok) resolve(m.result); else reject(new Error(m.error || 'bridge error'));
      return;
    }
    if (m.kind === 'execContextAction') {
      try { window.rubberduckUI && window.rubberduckUI.execContextAction &&
            window.rubberduckUI.execContextAction(m.name, m.payload); } catch (_) {}
      return;
    }
    if (m.kind === 'set-theme') {
      try { document.body.dataset.theme = m.theme; } catch (_) {}
      return;
    }
    if (m.kind === 'set-open-files') {
      try { window.rubberduckUI && window.rubberduckUI.execContextAction &&
            window.rubberduckUI.execContextAction('set-open-files', m.files); } catch (_) {}
      return;
    }
  });
  window.rubberduck = {
    getConfig:        ()    => call('getConfig'),
    getSelection:     ()    => call('getSelection'),
    getOpenFiles:     ()    => call('getOpenFiles'),
    getDiagnostics:   ()    => call('getDiagnostics'),
    readFile:         (p)   => call('readFile', [p]),
    pickFile:         ()    => call('pickFile'),
    insertAtCursor:   (t)   => call('insertAtCursor', [t]),
    replaceSelection: (t)   => call('replaceSelection', [t]),
    copyToClipboard:  (t)   => call('copyToClipboard', [t]),
    listSkills:       ()    => call('listSkills'),
    getSkillBody:     (n)   => call('getSkillBody', [n]),
    listModels:       ()    => call('listModels'),
    saveHistory:      (m)   => call('saveHistory', [m]),
    log:              (m)   => call('log', [m]),
    notify:           (m,k) => call('notify', [m, k]),
    onReady:          ()    => call('onReady'),
  };
})();
    `.trim();
  }

  // ---------------------------------------------------- Host → UI pushes

  newChat(): void           { this.postExec('new-chat'); }
  clear(): void             { this.postExec('clear'); }
  focusInput(): void        { this.postExec('focus-input'); }
  stopGeneration(): void    { this.postExec('stop'); }
  attachSelection(): void   { this.postExec('attach-selection'); }
  sendMessage(text: string): void { this.postExec('send', text); }
  insertText(text: string): void  { this.postExec('insert-text', text); }

  private postExec(name: string, payload?: unknown): void {
    this.view?.webview.postMessage({ kind: 'execContextAction', name, payload });
  }

  private postTheme(): void {
    this.view?.webview.postMessage({ kind: 'set-theme', theme: currentTheme() });
  }

  // ----------------------------------------------------- Bridge dispatch

  private async handleMessage(msg: BridgeMessage): Promise<void> {
    if (!msg || msg.kind !== 'bridge') return;
    const { id, method } = msg;
    const args = Array.isArray(msg.args) ? msg.args : [];
    const handler = HANDLERS[method];
    if (!handler) {
      this.reply(id, false, undefined, `unknown bridge method: ${method}`);
      return;
    }
    try {
      const result = await handler(args);
      this.reply(id, true, result ?? null);
    } catch (e) {
      const err = e instanceof Error ? e.message : String(e);
      log.warn(`[chat] bridge.${method} threw: ${err}`);
      this.reply(id, false, undefined, err);
    }
  }

  private reply(id: number, ok: boolean, result?: unknown, error?: string): void {
    this.view?.webview.postMessage({ kind: 'bridge-reply', id, ok, result, error });
  }
}

// ============================================================ Host handlers

type Handler = (args: unknown[]) => unknown | Promise<unknown>;

const HANDLERS: Record<string, Handler> = {
  getConfig:        () => getConfig(),
  getSelection:     () => getSelection(),
  getOpenFiles:     () => getOpenFiles(),
  getDiagnostics:   () => getDiagnostics(),
  readFile:         ([p]) => readWorkspaceFile(String(p ?? '')),
  pickFile:         () => pickFile(),
  insertAtCursor:   ([t]) => insertAtCursor(String(t ?? '')),
  replaceSelection: ([t]) => replaceSelection(String(t ?? '')),
  copyToClipboard:  async ([t]) => { await vscode.env.clipboard.writeText(String(t ?? '')); return true; },
  listSkills:       () => listSkills(),
  getSkillBody:     ([n]) => getSkillBody(String(n ?? '')),
  listModels:       () => listModels(),
  saveHistory:      ([m]) => saveHistory(m),
  log:              ([m]) => { log.info(`[chat] ${stringify(m)}`); return true; },
  notify:           ([m, k]) => notify(String(m ?? ''), String(k ?? 'info')),
  onReady:          () => { log.info('[chat] webview ready'); return true; },
};

function getConfig(): Record<string, unknown> {
  const cfg = vscode.workspace.getConfiguration('rubberduck');
  const legacyBase = Config.backendApiBase;
  const backendBase = cfg.get<string>('backendBaseUrl', legacyBase);
  const defaultModel = cfg.get<string>('chatModel', 'gpt-4o-mini');
  return { backendBase, theme: currentTheme(), defaultModel };
}

function getSelection(): Record<string, unknown> | null {
  const ed = vscode.window.activeTextEditor;
  if (!ed || ed.selection.isEmpty) return null;
  return {
    filePath: vscode.workspace.asRelativePath(ed.document.uri),
    language: ed.document.languageId,
    code: ed.document.getText(ed.selection),
  };
}

function getOpenFiles(): string[] {
  const out: string[] = [];
  const seen = new Set<string>();
  for (const group of vscode.window.tabGroups.all) {
    for (const tab of group.tabs) {
      const input = tab.input as { uri?: vscode.Uri } | undefined;
      if (input && input.uri && input.uri.scheme === 'file') {
        const rel = vscode.workspace.asRelativePath(input.uri);
        if (!seen.has(rel)) {
          seen.add(rel);
          out.push(rel);
        }
      }
    }
  }
  return out;
}

function getDiagnostics(): Array<Record<string, unknown>> {
  const ed = vscode.window.activeTextEditor;
  if (!ed) return [];
  const diags = vscode.languages.getDiagnostics(ed.document.uri);
  const sevMap = ['error', 'warning', 'info', 'hint'] as const;
  return diags.slice(0, 50).map((d) => ({
    filePath: vscode.workspace.asRelativePath(ed.document.uri),
    line: d.range.start.line,
    severity: sevMap[d.severity] ?? 'info',
    message: d.message,
  }));
}

async function readWorkspaceFile(relOrAbs: string): Promise<string | null> {
  if (!relOrAbs) return null;
  const root = vscode.workspace.workspaceFolders?.[0]?.uri;
  if (!root) return null;
  const rootFsPath = path.resolve(root.fsPath);
  const candidateFs = path.isAbsolute(relOrAbs)
    ? path.resolve(relOrAbs)
    : path.resolve(rootFsPath, relOrAbs);
  // Path-traversal guard: must live under workspace root.
  if (candidateFs !== rootFsPath && !candidateFs.startsWith(rootFsPath + path.sep)) {
    log.warn(`[chat] rejected readFile outside workspace: ${candidateFs}`);
    return null;
  }
  try {
    const bytes = await vscode.workspace.fs.readFile(vscode.Uri.file(candidateFs));
    return new TextDecoder('utf-8').decode(bytes);
  } catch {
    return null;
  }
}

async function pickFile(): Promise<Record<string, unknown> | null> {
  const picked = await vscode.window.showOpenDialog({
    canSelectMany: false,
    openLabel: 'Attach to chat',
  });
  if (!picked || picked.length === 0) return null;
  const uri = picked[0];
  if (!uri) return null;
  try {
    const bytes = await vscode.workspace.fs.readFile(uri);
    const content = new TextDecoder('utf-8').decode(bytes);
    const language = languageFromPath(uri.fsPath);
    return {
      filePath: vscode.workspace.asRelativePath(uri),
      content,
      language,
    };
  } catch (e) {
    log.warn(`[chat] pickFile read failed: ${e instanceof Error ? e.message : String(e)}`);
    return null;
  }
}

async function insertAtCursor(text: string): Promise<boolean> {
  const ed = vscode.window.activeTextEditor;
  if (!ed) return false;
  await ed.edit((b) => b.insert(ed.selection.active, text));
  return true;
}

async function replaceSelection(text: string): Promise<boolean> {
  const ed = vscode.window.activeTextEditor;
  if (!ed) return false;
  if (ed.selection.isEmpty) {
    await ed.edit((b) => b.insert(ed.selection.active, text));
  } else {
    await ed.edit((b) => b.replace(ed.selection, text));
  }
  return true;
}

async function listSkills(): Promise<Array<Record<string, unknown>>> {
  const root = vscode.workspace.workspaceFolders?.[0]?.uri;
  if (!root) return [];
  const skillsDir = vscode.Uri.joinPath(root, '.skills');
  let entries: [string, vscode.FileType][];
  try {
    entries = await vscode.workspace.fs.readDirectory(skillsDir);
  } catch {
    return [];
  }
  const out: Array<Record<string, unknown>> = [];
  for (const [name, type] of entries) {
    if (type !== vscode.FileType.Directory) continue;
    const skillFile = vscode.Uri.joinPath(skillsDir, name, 'SKILL.md');
    try {
      const bytes = await vscode.workspace.fs.readFile(skillFile);
      const { frontmatter } = parseFrontmatter(new TextDecoder('utf-8').decode(bytes));
      out.push({
        name: frontmatter.name ?? name,
        description: frontmatter.description ?? '',
        trigger: frontmatter.trigger ?? '',
      });
    } catch {
      // skip unreadable skill
    }
  }
  return out;
}

async function getSkillBody(name: string): Promise<string | null> {
  if (!name) return null;
  const root = vscode.workspace.workspaceFolders?.[0]?.uri;
  if (!root) return null;
  // Try by directory name first.
  const candidates: vscode.Uri[] = [
    vscode.Uri.joinPath(root, '.skills', name, 'SKILL.md'),
  ];
  // Also scan for a skill whose frontmatter name matches.
  try {
    const entries = await vscode.workspace.fs.readDirectory(vscode.Uri.joinPath(root, '.skills'));
    for (const [dir, type] of entries) {
      if (type === vscode.FileType.Directory && dir !== name) {
        candidates.push(vscode.Uri.joinPath(root, '.skills', dir, 'SKILL.md'));
      }
    }
  } catch {
    // .skills/ not present
  }
  for (const uri of candidates) {
    try {
      const bytes = await vscode.workspace.fs.readFile(uri);
      const text = new TextDecoder('utf-8').decode(bytes);
      const { frontmatter, body } = parseFrontmatter(text);
      const fmName = frontmatter.name ?? '';
      if (uri.path.endsWith(`/${name}/SKILL.md`) || fmName === name) {
        return body;
      }
    } catch {
      // keep trying
    }
  }
  return null;
}

function listModels(): string[] {
  const cfg = vscode.workspace.getConfiguration('rubberduck');
  const models = cfg.get<string[]>('chatAvailableModels', []);
  if (models && models.length > 0) return models;
  return ['gpt-4o-mini', 'gpt-4o', 'claude-sonnet-4', 'ollama/llama3.1'];
}

async function saveHistory(messages: unknown): Promise<boolean> {
  const root = vscode.workspace.workspaceFolders?.[0]?.uri;
  if (!root) return false;
  try {
    const dir = vscode.Uri.joinPath(root, '.rubberduck');
    try {
      await vscode.workspace.fs.createDirectory(dir);
    } catch {
      // already exists
    }
    const file = vscode.Uri.joinPath(dir, 'chat-history.jsonl');
    const line = JSON.stringify(messages).replace(/\n/g, ' ') + '\n';
    let existing: Uint8Array = new Uint8Array(0);
    try {
      const read = await vscode.workspace.fs.readFile(file);
      existing = new Uint8Array(read);
    } catch {
      // new file
    }
    const appended = new TextEncoder().encode(line);
    const combined = new Uint8Array(existing.length + appended.length);
    combined.set(existing, 0);
    combined.set(appended, existing.length);
    await vscode.workspace.fs.writeFile(file, combined);
    return true;
  } catch (e) {
    log.warn(`[chat] saveHistory failed: ${e instanceof Error ? e.message : String(e)}`);
    return false;
  }
}

function notify(message: string, kind: string): boolean {
  const k = (kind || 'info').toLowerCase();
  if (k === 'error') {
    void vscode.window.showErrorMessage(message);
  } else if (k === 'warn' || k === 'warning') {
    void vscode.window.showWarningMessage(message);
  } else {
    void vscode.window.showInformationMessage(message);
  }
  return true;
}

// ============================================================== Helpers

interface BridgeMessage {
  kind: string;
  id: number;
  method: string;
  args?: unknown[];
}

function currentTheme(): 'light' | 'dark' {
  const kind = vscode.window.activeColorTheme.kind;
  // Light + HighContrastLight → 'light'; everything else → 'dark'.
  if (kind === vscode.ColorThemeKind.Light || kind === vscode.ColorThemeKind.HighContrastLight) {
    return 'light';
  }
  return 'dark';
}

function randomNonce(): string {
  const chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789';
  let out = '';
  for (let i = 0; i < 32; i++) out += chars.charAt(Math.floor(Math.random() * chars.length));
  return out;
}

function stringify(v: unknown): string {
  if (typeof v === 'string') return v;
  try {
    return JSON.stringify(v);
  } catch {
    return String(v);
  }
}

function languageFromPath(p: string): string {
  const ext = path.extname(p).toLowerCase().replace(/^\./, '');
  const map: Record<string, string> = {
    ts: 'typescript', tsx: 'typescript', js: 'javascript', jsx: 'javascript',
    py: 'python', rs: 'rust', go: 'go', java: 'java', kt: 'kotlin',
    rb: 'ruby', php: 'php', cs: 'csharp', cpp: 'cpp', cc: 'cpp', c: 'c',
    h: 'c', hpp: 'cpp', swift: 'swift', scala: 'scala', sh: 'shell',
    md: 'markdown', json: 'json', yml: 'yaml', yaml: 'yaml', toml: 'toml',
    html: 'html', css: 'css', scss: 'scss', sql: 'sql', xml: 'xml',
  };
  return map[ext] ?? (ext || '');
}

/**
 * Tiny YAML-frontmatter parser — matches the regex the IntelliJ side uses:
 * `^---\r?\n(.*?)\r?\n---\r?\n?(.*)$` with DOTALL. Accepts only flat
 * `key: value` pairs which is all our SKILL.md files use.
 */
function parseFrontmatter(text: string): { frontmatter: Record<string, string>; body: string } {
  const m = /^---\r?\n([\s\S]*?)\r?\n---\r?\n?([\s\S]*)$/.exec(text);
  if (!m) return { frontmatter: {}, body: text };
  const header = m[1] ?? '';
  const body = m[2] ?? '';
  const out: Record<string, string> = {};
  for (const line of header.split(/\r?\n/)) {
    const idx = line.indexOf(':');
    if (idx < 0) continue;
    const key = line.slice(0, idx).trim();
    let val = line.slice(idx + 1).trim();
    if ((val.startsWith('"') && val.endsWith('"')) || (val.startsWith("'") && val.endsWith("'"))) {
      val = val.slice(1, -1);
    }
    if (key) out[key] = val;
  }
  return { frontmatter: out, body };
}

// ============================================================ Registration

let activeProvider: ChatWebviewProvider | undefined;

export function registerChatWebviewProvider(context: vscode.ExtensionContext): ChatWebviewProvider {
  const provider = new ChatWebviewProvider(context.extensionUri);
  activeProvider = provider;
  context.subscriptions.push(
    vscode.window.registerWebviewViewProvider(ChatWebviewProvider.VIEW_TYPE, provider, {
      webviewOptions: { retainContextWhenHidden: true },
    }),
  );
  log.info('ChatWebviewProvider registered');
  return provider;
}

export function getChatWebviewProvider(): ChatWebviewProvider | undefined {
  return activeProvider;
}
