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

  constructor(private readonly extensionUri: vscode.Uri) {
    // Expose to module-level bridge handlers (bundled-skill fallback path).
    activeExtensionUri = extensionUri;
  }

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
    setModel:         (m)   => call('setModel', [m]),
    saveHistory:      (m)   => call('saveHistory', [m]),
    log:              (m)   => call('log', [m]),
    notify:           (m,k) => call('notify', [m, k]),
    onReady:          ()    => call('onReady'),
    applyEdit:        (p)   => call('applyEdit', [p]),
    rejectEdit:       (p)   => call('rejectEdit', [p]),
    emitIntervention: (p)   => call('emitIntervention', [p]),
    searchFiles:      (p)   => call('searchFiles', [p]),
    searchSemantic:   (p)   => call('searchSemantic', [p]),
    listSessions:     ()    => call('listSessions'),
    loadSession:      (p)   => call('loadSession', [p]),
    deleteSession:    (p)   => call('deleteSession', [p]),
    renameSession:    (p)   => call('renameSession', [p]),
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
  setModel:         ([m]) => setModel(String(m ?? '')),
  saveHistory:      ([m]) => saveHistory(m),
  log:              ([m]) => { log.info(`[chat] ${stringify(m)}`); return true; },
  notify:           ([m, k]) => notify(String(m ?? ''), String(k ?? 'info')),
  onReady:          () => { log.info('[chat] webview ready'); return true; },
  applyEdit:        ([p]) => applyEdit(p),
  rejectEdit:       ([p]) => rejectEdit(p),
  emitIntervention: ([p]) => emitIntervention(p),
  searchFiles:      ([p]) => searchFiles(p),
  searchSemantic:   ([p]) => searchSemantic(p),
  listSessions:     () => listSessions(),
  loadSession:      ([p]) => loadSession(p),
  deleteSession:    ([p]) => deleteSession(p),
  renameSession:    ([p]) => renameSession(p),
};

/**
 * Merge the canonical `rubberduck.*` namespace with any legacy
 * `rubberduckLive.*` keys (`rubberduck.*` wins when both set).  The
 * returned shape is what the shared web UI consumes from
 * `window.rubberduck.getConfig()`.
 */
function getConfig(): Record<string, unknown> {
  const backendBase = Config.backendApiBase;
  const chatModel = Config.chatModel;
  const chatAvailableModels = Config.chatAvailableModels;
  return {
    backendBase,
    theme: currentTheme(),
    defaultModel: chatModel,
    chatModel,
    chatAvailableModels,
    chatMaxHistoryTokens: Config.chatMaxHistoryTokens,
    chatAttachOpenFiles: Config.chatAttachOpenFiles,
    chatAttachDiagnostics: Config.chatAttachDiagnostics,
  };
}

async function setModel(model: string): Promise<boolean> {
  const m = (model ?? '').trim();
  if (!m) return false;
  try {
    await Config.setChatModel(m);
    return true;
  } catch (e) {
    log.warn(`[chat] setModel failed: ${e instanceof Error ? e.message : String(e)}`);
    return false;
  }
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

/**
 * List skills visible to the chat UI.
 *
 * Resolution order:
 *   1. Workspace `.skills/<dir>/SKILL.md` (project-scoped, wins on name match).
 *   2. Bundled `media/skills/*.md` shipped inside the extension (fallback
 *      so `@skill:...` works in workspaces that haven't authored skills).
 * Results are filtered by the `rubberduck.enabledSkills` user setting.
 */
async function listSkills(): Promise<Array<Record<string, unknown>>> {
  const cfg = vscode.workspace.getConfiguration('rubberduck');
  const enabled = cfg.get<string[]>('enabledSkills', [
    'Test Generator', 'Refactor Suggester', 'Code Explainer', 'Socratic Coach',
  ]);
  const enabledSet = new Set(enabled);
  const collected = await collectSkillMetas();
  // Dedup by `name` — workspace wins over bundled.
  const byName = new Map<string, Record<string, unknown>>();
  for (const s of collected) {
    const n = String(s.name ?? '');
    if (!n) continue;
    if (!byName.has(n)) byName.set(n, s);
  }
  const out: Array<Record<string, unknown>> = [];
  for (const s of byName.values()) {
    if (enabledSet.size === 0 || enabledSet.has(String(s.name))) out.push(s);
  }
  return out;
}

async function getSkillBody(name: string): Promise<string | null> {
  if (!name) return null;
  const all = await collectSkillEntries();
  // Match by frontmatter `name`, then directory/file stem (case-insensitive).
  const lower = name.toLowerCase();
  for (const e of all) {
    if (e.frontmatter.name === name) return e.body;
  }
  for (const e of all) {
    if ((e.frontmatter.name ?? '').toLowerCase() === lower) return e.body;
  }
  for (const e of all) {
    if (e.stem.toLowerCase() === lower) return e.body;
  }
  return null;
}

/** Discover every SKILL.md / bundled skill markdown with parsed frontmatter. */
interface SkillEntry {
  stem: string; // directory name (workspace) or filename stem (bundled)
  frontmatter: Record<string, string>;
  body: string;
  source: 'workspace' | 'bundled';
}
async function collectSkillEntries(): Promise<SkillEntry[]> {
  const out: SkillEntry[] = [];
  // 1. Workspace `.skills/` (directory-per-skill, SKILL.md inside).
  const root = vscode.workspace.workspaceFolders?.[0]?.uri;
  if (root) {
    const skillsDir = vscode.Uri.joinPath(root, '.skills');
    try {
      const entries = await vscode.workspace.fs.readDirectory(skillsDir);
      for (const [dir, type] of entries) {
        if (type !== vscode.FileType.Directory) continue;
        const uri = vscode.Uri.joinPath(skillsDir, dir, 'SKILL.md');
        try {
          const bytes = await vscode.workspace.fs.readFile(uri);
          const { frontmatter, body } = parseFrontmatter(new TextDecoder('utf-8').decode(bytes));
          out.push({ stem: dir, frontmatter, body, source: 'workspace' });
        } catch { /* skip unreadable */ }
      }
    } catch { /* .skills/ missing — fine, we have the bundled fallback */ }
  }
  // 2. Bundled `media/skills/*.md` shipped with the extension.
  if (activeExtensionUri) {
    const bundledDir = vscode.Uri.joinPath(activeExtensionUri, 'media', 'skills');
    try {
      const entries = await vscode.workspace.fs.readDirectory(bundledDir);
      for (const [file, type] of entries) {
        if (type !== vscode.FileType.File || !file.endsWith('.md')) continue;
        const uri = vscode.Uri.joinPath(bundledDir, file);
        try {
          const bytes = await vscode.workspace.fs.readFile(uri);
          const { frontmatter, body } = parseFrontmatter(new TextDecoder('utf-8').decode(bytes));
          const stem = file.replace(/\.md$/i, '');
          out.push({ stem, frontmatter, body, source: 'bundled' });
        } catch { /* skip unreadable */ }
      }
    } catch { /* bundled missing — nothing to do */ }
  }
  return out;
}
async function collectSkillMetas(): Promise<Array<Record<string, unknown>>> {
  const entries = await collectSkillEntries();
  return entries.map((e) => ({
    name: e.frontmatter.name ?? e.stem,
    description: e.frontmatter.description ?? '',
    trigger: e.frontmatter.trigger ?? '',
  }));
}

function listModels(): string[] {
  return Config.chatAvailableModels;
}

// ---------------------------------------------- Session store (per-file JSON)

/**
 * A safe session id is a UUID-shaped token: letters, digits, dash,
 * underscore; between 1 and 64 characters. Rejects `..`, slashes, and
 * Windows drive letters — nothing caller-supplied ever escapes the
 * sessions directory.
 */
const SESSION_ID_RE = /^[A-Za-z0-9_-]{1,64}$/;

function isSafeSessionId(id: unknown): id is string {
  return typeof id === 'string' && SESSION_ID_RE.test(id);
}

function sessionsDirUri(): vscode.Uri | null {
  const root = vscode.workspace.workspaceFolders?.[0]?.uri;
  if (!root) return null;
  return vscode.Uri.joinPath(root, '.rubberduck', 'sessions');
}

function randomSessionId(): string {
  // VS Code extension host ships Node's crypto — `randomUUID` is on v14.17+.
  // The dynamic require avoids a top-of-file type import on a module we
  // only touch from this one place.
  // eslint-disable-next-line @typescript-eslint/no-var-requires
  const { randomUUID } = require('crypto') as { randomUUID: () => string };
  return randomUUID();
}

function readableFromUnknown(v: unknown): string {
  if (v == null) return '';
  if (typeof v === 'string') return v;
  try {
    return JSON.stringify(v);
  } catch {
    return String(v);
  }
}

interface SessionHeader {
  id: string;
  title: string;
  created_at: number;
  updated_at: number;
  message_count: number;
}

interface SessionFull extends SessionHeader {
  model?: string;
  messages: unknown[];
}

function deriveTitle(messages: unknown[] | undefined): string {
  for (const m of messages ?? []) {
    if (m && typeof m === 'object') {
      const rec = m as Record<string, unknown>;
      if (rec.role !== 'user') continue;
      const content = readableFromUnknown(rec.content).trim().replace(/\s+/g, ' ');
      if (content) return content.slice(0, 80);
    }
  }
  return 'New chat';
}

async function readSessionFile(uri: vscode.Uri): Promise<Record<string, unknown> | null> {
  try {
    const buf = await vscode.workspace.fs.readFile(uri);
    const text = new TextDecoder('utf-8').decode(buf);
    const parsed = JSON.parse(text);
    if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
      return parsed as Record<string, unknown>;
    }
    return null;
  } catch {
    return null;
  }
}

/**
 * Write a session JSON atomically: serialise to a sibling `.tmp`, then
 * rename over the target. `workspace.fs.rename` with `overwrite: true`
 * gives us the same "never expose a torn file" guarantee as the Kotlin
 * side's `Files.move(ATOMIC_MOVE)`.
 */
async function writeSessionAtomic(target: vscode.Uri, body: string): Promise<void> {
  const tmp = vscode.Uri.parse(target.toString() + '.tmp');
  const bytes = new TextEncoder().encode(body);
  await vscode.workspace.fs.writeFile(tmp, bytes);
  try {
    await vscode.workspace.fs.rename(tmp, target, { overwrite: true });
  } catch (e) {
    // Fallback path: some filesystems don't support rename-over-existing.
    // Fall back to direct-write + delete tmp; the window of a torn file
    // is narrow and readSessionFile returns null on JSON.parse failures.
    log.info(`[chat] atomic rename unavailable (${e instanceof Error ? e.message : String(e)}); falling back to direct write`);
    await vscode.workspace.fs.writeFile(target, bytes);
    try { await vscode.workspace.fs.delete(tmp); } catch { /* best effort */ }
  }
}

/**
 * Persist a chat session. Payload shape mirrors the Kotlin side:
 *   `{id?, title?, model?, messages}`.
 *
 * Fixes the previous whole-file RMW hazard — each session lives in its
 * own `.rubberduck/sessions/<id>.json`, so two concurrent saves only
 * collide if they target the same session (same webview tab = single
 * writer = no race in practice).
 */
async function saveHistory(payload: unknown): Promise<Record<string, unknown>> {
  const dir = sessionsDirUri();
  if (!dir) return { ok: false, error: 'no-workspace-root' };

  // Allow both shapes — structured object (preferred) or a bare array of
  // messages (legacy fallback). The legacy path auto-generates an id.
  let session: Record<string, unknown>;
  if (Array.isArray(payload)) {
    session = { messages: payload };
  } else if (payload && typeof payload === 'object') {
    session = payload as Record<string, unknown>;
  } else {
    return { ok: false, error: 'invalid-payload' };
  }

  const rawId = typeof session.id === 'string' ? session.id : null;
  let id: string;
  if (rawId && rawId.trim()) {
    if (!isSafeSessionId(rawId)) return { ok: false, error: 'unsafe-id' };
    id = rawId;
  } else {
    id = randomSessionId();
  }

  try {
    await vscode.workspace.fs.createDirectory(dir);
  } catch {
    // createDirectory is idempotent on missing dirs; real errors surface on the writeFile below
  }

  const target = vscode.Uri.joinPath(dir, `${id}.json`);
  const now = Date.now();
  const existing = await readSessionFile(target);

  const createdAt =
    (typeof session.created_at === 'number' && session.created_at > 0 && session.created_at) ||
    (existing && typeof existing.created_at === 'number' && existing.created_at) ||
    now;

  const titleFromPayload =
    typeof session.title === 'string' && session.title.trim() ? session.title.trim() : null;
  const titleFromExisting =
    existing && typeof existing.title === 'string' && (existing.title as string).trim()
      ? (existing.title as string).trim()
      : null;
  const title =
    titleFromPayload ??
    titleFromExisting ??
    deriveTitle(Array.isArray(session.messages) ? (session.messages as unknown[]) : undefined);

  const messages = Array.isArray(session.messages) ? (session.messages as unknown[]) : [];
  const model = typeof session.model === 'string' ? session.model : undefined;

  const full: SessionFull = {
    id,
    title,
    created_at: createdAt,
    updated_at: now,
    message_count: messages.length,
    ...(model ? { model } : {}),
    messages,
  };

  try {
    await writeSessionAtomic(target, JSON.stringify(full));
    return {
      ok: true,
      id,
      title,
      created_at: createdAt,
      updated_at: now,
    };
  } catch (e) {
    log.warn(`[chat] saveHistory failed: ${e instanceof Error ? e.message : String(e)}`);
    return { ok: false, error: 'write-failed' };
  }
}

async function listSessions(): Promise<SessionHeader[]> {
  const dir = sessionsDirUri();
  if (!dir) return [];
  let entries: [string, vscode.FileType][];
  try {
    entries = await vscode.workspace.fs.readDirectory(dir);
  } catch {
    return [];
  }
  const summaries: SessionHeader[] = [];
  for (const [name, type] of entries) {
    if (type !== vscode.FileType.File) continue;
    if (!name.endsWith('.json') || name.endsWith('.tmp.json')) continue;
    const id = name.slice(0, -5);
    if (!isSafeSessionId(id)) continue;
    const o = await readSessionFile(vscode.Uri.joinPath(dir, name));
    if (!o) continue;
    const storedId = typeof o.id === 'string' ? o.id : id;
    const title = typeof o.title === 'string' ? o.title : 'Untitled';
    const createdAt = typeof o.created_at === 'number' ? o.created_at : 0;
    const updatedAt = typeof o.updated_at === 'number' ? o.updated_at : createdAt;
    const messages = Array.isArray(o.messages) ? (o.messages as unknown[]) : [];
    summaries.push({
      id: storedId,
      title,
      created_at: createdAt,
      updated_at: updatedAt,
      message_count: messages.length,
    });
  }
  summaries.sort((a, b) => b.updated_at - a.updated_at);
  return summaries;
}

async function loadSession(payload: unknown): Promise<Record<string, unknown> | null> {
  const id = extractSessionId(payload);
  if (!id) return null;
  const dir = sessionsDirUri();
  if (!dir) return null;
  return readSessionFile(vscode.Uri.joinPath(dir, `${id}.json`));
}

async function deleteSession(payload: unknown): Promise<boolean> {
  const id = extractSessionId(payload);
  if (!id) return false;
  const dir = sessionsDirUri();
  if (!dir) return false;
  try {
    await vscode.workspace.fs.delete(vscode.Uri.joinPath(dir, `${id}.json`));
    return true;
  } catch (e) {
    log.warn(`[chat] deleteSession failed: ${e instanceof Error ? e.message : String(e)}`);
    return false;
  }
}

async function renameSession(payload: unknown): Promise<boolean> {
  if (!payload || typeof payload !== 'object') return false;
  const rec = payload as Record<string, unknown>;
  const id = typeof rec.id === 'string' ? rec.id : '';
  const title = typeof rec.title === 'string' ? rec.title.trim().slice(0, 200) : '';
  if (!isSafeSessionId(id) || !title) return false;
  const dir = sessionsDirUri();
  if (!dir) return false;
  const target = vscode.Uri.joinPath(dir, `${id}.json`);
  const existing = await readSessionFile(target);
  if (!existing) return false;
  const rewritten = { ...existing, title, updated_at: Date.now() };
  try {
    await writeSessionAtomic(target, JSON.stringify(rewritten));
    return true;
  } catch (e) {
    log.warn(`[chat] renameSession failed: ${e instanceof Error ? e.message : String(e)}`);
    return false;
  }
}

/**
 * Pull an id out of either `{id: "..."}` or a bare `"..."` payload. Empty
 * string is returned when the id is missing or fails the safety pattern.
 */
function extractSessionId(payload: unknown): string {
  if (typeof payload === 'string') {
    return isSafeSessionId(payload) ? payload : '';
  }
  if (payload && typeof payload === 'object') {
    const rec = payload as Record<string, unknown>;
    const id = rec.id;
    return isSafeSessionId(id) ? id : '';
  }
  return '';
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

// ----------------------------------------------------- Apply/Reject edits

interface ApplyEditPayload {
  edit_id?: string;
  file_path?: string;
  original_code?: string;
  proposed_code?: string;
  explanation?: string;
  start_line?: number | null;
  end_line?: number | null;
  diff_unified?: string;
}

/**
 * Preview a proposed code edit in a native VS Code diff viewer, then apply
 * it on user confirmation. Standard Cmd/Ctrl+Z undoes cleanly since we go
 * through `WorkspaceEdit.applyEdit`.
 *
 * The flow is intentionally two-step (show diff, then confirm): the built-in
 * `vscode.diff` command only shows the diff — it doesn't accept/reject it —
 * so we stack a modal confirmation afterwards. Users who don't want to see
 * the diff first can click "Apply" straight from the information dialog.
 */
async function applyEdit(rawPayload: unknown): Promise<{ applied: boolean; edit_id?: string; error?: string }> {
  const p = coercePayloadObject(rawPayload) as ApplyEditPayload | null;
  if (!p) return { applied: false, error: 'invalid-payload' };
  const filePath = String(p.file_path ?? '').trim();
  const proposed = String(p.proposed_code ?? '');
  if (!filePath || !proposed) return { applied: false, error: 'missing-fields' };

  const root = vscode.workspace.workspaceFolders?.[0]?.uri;
  if (!root) return { applied: false, error: 'no-workspace' };

  const rootFsPath = path.resolve(root.fsPath);
  const candidateFs = path.isAbsolute(filePath)
    ? path.resolve(filePath)
    : path.resolve(rootFsPath, filePath);
  if (candidateFs !== rootFsPath && !candidateFs.startsWith(rootFsPath + path.sep)) {
    log.warn(`[chat] rejected applyEdit outside workspace: ${candidateFs}`);
    return { applied: false, error: 'outside-workspace' };
  }

  const fileUri = vscode.Uri.file(candidateFs);
  let doc: vscode.TextDocument;
  try {
    doc = await vscode.workspace.openTextDocument(fileUri);
  } catch (e) {
    log.warn(`[chat] applyEdit: openTextDocument failed: ${e instanceof Error ? e.message : String(e)}`);
    return { applied: false, error: 'file-not-found', edit_id: p.edit_id };
  }

  const name = path.basename(candidateFs);
  const explanation = String(p.explanation ?? '').trim();

  // Write the proposed version to an in-memory `untitled:` URI so the
  // built-in diff viewer can render it without touching disk.
  const proposedUri = vscode.Uri.parse(`untitled:${name}.proposed`);
  try {
    const proposedDoc = await vscode.workspace.openTextDocument(proposedUri);
    const fullRange = new vscode.Range(
      proposedDoc.positionAt(0),
      proposedDoc.positionAt(proposedDoc.getText().length),
    );
    const seedEdit = new vscode.WorkspaceEdit();
    if (proposedDoc.getText().length) seedEdit.delete(proposedUri, fullRange);
    seedEdit.insert(proposedUri, new vscode.Position(0, 0), proposed);
    await vscode.workspace.applyEdit(seedEdit);
  } catch {
    // If we can't stage the proposed buffer we still proceed — the user
    // will just see a blank right-hand pane.
  }

  const diffTitle = explanation
    ? `RubberDuck chat edit — ${name}: ${explanation}`
    : `RubberDuck chat edit — ${name}`;
  await vscode.commands.executeCommand('vscode.diff', fileUri, proposedUri, diffTitle, {
    preview: true,
  });

  const choice = await vscode.window.showInformationMessage(
    `Apply this chat edit to ${name}?${explanation ? `\n\n${explanation}` : ''}`,
    { modal: true },
    'Apply',
  );
  const shouldApply = choice === 'Apply';

  if (shouldApply) {
    const edit = new vscode.WorkspaceEdit();
    const fullRange = new vscode.Range(
      doc.positionAt(0),
      doc.positionAt(doc.getText().length),
    );
    edit.replace(fileUri, fullRange, proposed);
    const ok = await vscode.workspace.applyEdit(edit);
    if (ok) {
      void doc.save();
    }
    return { applied: ok, edit_id: p.edit_id };
  }
  return { applied: false, edit_id: p.edit_id };
}

function rejectEdit(rawPayload: unknown): boolean {
  const p = coercePayloadObject(rawPayload);
  log.info(`[chat] rejectEdit edit_id=${p && 'edit_id' in p ? String(p.edit_id) : ''}`);
  return true;
}

function emitIntervention(rawPayload: unknown): boolean {
  // VS Code doesn't emit intervention events to the dashboard today — the
  // IntelliJ plugin owns that contract. We log for parity debugging and
  // return true so the JS side keeps its optimistic flow.
  const p = coercePayloadObject(rawPayload);
  log.info(`[chat] emitIntervention ${stringify(p)}`);
  return true;
}

// ------------------------------------------------------------ @-file search

/**
 * Fuzzy-search workspace files for the web UI's `@` autocomplete.
 *
 * Mirrors the contract of `ChatBridge.searchFiles` on the IntelliJ side:
 *   - accepts `{query, limit}` as a single object payload
 *   - returns `[{path, score}, ...]` ranked high-to-low
 *   - honours `rubberduck.privacyMode` — returns `[]` when strict privacy is on
 *
 * Impl notes:
 *   - `workspace.findFiles` already respects VS Code's "files.exclude" and
 *     "search.exclude" settings, so node_modules/.git/etc are skipped for
 *     free. We add an explicit `**\/node_modules/**` exclude as a belt-and-
 *     braces default for workspaces that forgot to configure it.
 *   - Scoring mirrors what a user intuitively expects: exact filename match
 *     beats filename-prefix beats filename-substring beats path-substring
 *     beats a character-subsequence fallback. Ties break by shorter paths,
 *     then alphabetical.
 */
async function searchFiles(rawPayload: unknown): Promise<Array<{ path: string; score: number }>> {
  if (Config.privacyMode) return [];
  const obj = coercePayloadObject(rawPayload) ?? {};
  const query = String(obj['query'] ?? '').trim();
  const rawLimit = Number(obj['limit']);
  const limit = Math.max(1, Math.min(100, Number.isFinite(rawLimit) && rawLimit > 0 ? rawLimit : 30));

  const root = vscode.workspace.workspaceFolders?.[0]?.uri;
  if (!root) return [];

  let uris: vscode.Uri[];
  try {
    // Cap at 2000 matches to keep the IPC payload bounded on huge monorepos —
    // `findFiles` returns in no particular order, so an explicit cap + client
    // sort is fine.
    uris = await vscode.workspace.findFiles('**/*', '**/node_modules/**', 2000);
  } catch (e) {
    log.warn(`[chat] searchFiles findFiles failed: ${e instanceof Error ? e.message : String(e)}`);
    return [];
  }

  const lowerQuery = query.toLowerCase();
  const hits: Array<{ path: string; score: number }> = [];
  for (const uri of uris) {
    // asRelativePath uses OS-native separators — normalise to forward slashes
    // so the JS side renders identically in both IDEs.
    const rel = vscode.workspace.asRelativePath(uri, false).replace(/\\/g, '/');
    const name = path.basename(rel);
    const score = scoreFuzzyPath(lowerQuery, rel.toLowerCase(), name.toLowerCase());
    if (query && score === 0) continue;
    hits.push({ path: rel, score });
  }
  hits.sort(
    (a, b) =>
      b.score - a.score ||
      a.path.length - b.path.length ||
      a.path.localeCompare(b.path),
  );
  return hits.slice(0, limit);
}

// --------------------------------------------------- Semantic index (BM25)

/**
 * Whether we've pushed the workspace corpus to the backend's BM25 index at
 * least once. Lazily primed on the first `searchSemantic` call so the user
 * pays the indexing cost only when they actually use the feature.
 */
let semanticIndexPrimed = false;

/**
 * Semantic file search via the backend's BM25 endpoint.
 *
 * Unlike the IntelliJ side (which has a dedicated `CodeIndexer` project
 * service that pushes on startup + debounces on VFS changes), VS Code
 * doesn't run a background indexer. Instead, we lazily push the corpus
 * on the first semantic query — good enough for a demo-scale workspace.
 *
 * Privacy: respects `rubberduck.privacyMode`. Returns `[]` on any failure
 * (backend down, privacy on, empty workspace) — the UI degrades to the
 * plain `@` fuzzy filename search.
 */
async function searchSemantic(
  rawPayload: unknown,
): Promise<Array<{ path: string; score: number; snippet: string }>> {
  if (Config.privacyMode) return [];
  const obj = coercePayloadObject(rawPayload) ?? {};
  const query = String(obj['query'] ?? '').trim();
  if (!query) return [];
  const rawK = Number(obj['k'] ?? obj['limit']);
  const k = Math.max(1, Math.min(50, Number.isFinite(rawK) && rawK > 0 ? rawK : 5));

  if (!semanticIndexPrimed) {
    // Fire-and-wait on the first query — subsequent calls reuse the index.
    try {
      await primeSemanticIndex();
      semanticIndexPrimed = true;
    } catch (e) {
      log.warn(`[chat] primeSemanticIndex failed: ${e instanceof Error ? e.message : String(e)}`);
      return [];
    }
  }

  try {
    const resp = await fetch(`${Config.backendApiBase}/index/search`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ query, k }),
    });
    if (!resp.ok) return [];
    const body = (await resp.json()) as { hits?: Array<{ path: string; score: number; snippet: string }> };
    return body.hits ?? [];
  } catch (e) {
    log.warn(`[chat] searchSemantic failed: ${e instanceof Error ? e.message : String(e)}`);
    return [];
  }
}

/**
 * Walk the workspace, skip binaries / huge files, push the corpus to
 * `/index/build`. Caps at 2000 files and 1 MB per file to match the
 * IntelliJ indexer's safety limits.
 */
async function primeSemanticIndex(): Promise<void> {
  const root = vscode.workspace.workspaceFolders?.[0]?.uri;
  if (!root) return;
  const uris = await vscode.workspace.findFiles('**/*', '**/node_modules/**', 2000);
  const files: Array<{ path: string; content: string }> = [];
  for (const uri of uris) {
    try {
      const stat = await vscode.workspace.fs.stat(uri);
      if (stat.size > 1_000_000) continue;
      const bytes = await vscode.workspace.fs.readFile(uri);
      if (looksBinary(bytes)) continue;
      const rel = vscode.workspace.asRelativePath(uri, false).replace(/\\/g, '/');
      files.push({ path: rel, content: new TextDecoder('utf-8').decode(bytes) });
    } catch {
      /* skip unreadable */
    }
  }
  if (!files.length) return;
  await fetch(`${Config.backendApiBase}/index/build`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ files }),
  });
  log.info(`[chat] primed semantic index with ${files.length} files`);
}

/** Heuristic: if the first 1 KB contains null bytes, treat as binary. */
function looksBinary(bytes: Uint8Array): boolean {
  const probe = bytes.length > 1024 ? bytes.subarray(0, 1024) : bytes;
  for (const b of probe) {
    if (b === 0) return true;
  }
  return false;
}

/**
 * Tiny bespoke fuzzy scorer. Not as sophisticated as VS Code's built-in
 * quickPick scorer (which is internal), but deterministic and good enough
 * for a ranked @ popup. Returns 0 when `query` doesn't match at all — the
 * caller filters those out. `query`/`rel`/`name` are all expected lowercase.
 */
function scoreFuzzyPath(query: string, rel: string, name: string): number {
  if (!query) return 0;
  if (name === query) return 1000;                       // exact filename
  if (name.startsWith(query)) return 600;                // filename prefix
  if (name.includes(query)) return 400;                  // filename substring
  if (rel.endsWith('/' + query)) return 350;             // full path ends in /<query>
  if (rel.includes('/' + query)) return 300;             // path segment substring
  if (rel.includes(query)) return 200;                   // path substring
  // Character-subsequence fallback ("ctw" → "compileTypescriptWatcher.ts").
  // Reward consecutive matches so contiguous runs beat scattered letters.
  let qi = 0;
  let runScore = 0;
  let lastWasMatch = false;
  for (let i = 0; i < name.length && qi < query.length; i++) {
    if (name[i] === query[qi]) {
      qi++;
      runScore += lastWasMatch ? 3 : 1;
      lastWasMatch = true;
    } else {
      lastWasMatch = false;
    }
  }
  return qi === query.length ? Math.max(10, runScore) : 0;
}

function coercePayloadObject(raw: unknown): Record<string, unknown> | null {
  if (raw && typeof raw === 'object' && !Array.isArray(raw)) {
    return raw as Record<string, unknown>;
  }
  if (typeof raw === 'string' && raw.trim().startsWith('{')) {
    try { return JSON.parse(raw) as Record<string, unknown>; } catch { return null; }
  }
  return null;
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
let activeExtensionUri: vscode.Uri | undefined;

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
