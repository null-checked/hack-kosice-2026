import * as vscode from 'vscode';

import { BackendClient } from '../net/backendClient';
import { Config } from '../settings/config';
import { PathRedactor } from '../util/pathRedactor';
import type { ChatTurn } from '../model/api';
import { log } from '../util/logger';

/**
 * Interactive chat panel registered as a VSCode webview view in the side
 * bar ("rubberduck.chat"). Posts each user turn to `POST /api/v1/code-chat`
 * with the text of the currently-active editor. Mirrors the IntelliJ
 * plugin's Chat tab (`RubberDuckToolWindow` in
 * [`RubberDuckToolWindowFactory.kt`](../../../plugin/src/main/kotlin/com/rubberduck/live/toolwindow/RubberDuckToolWindowFactory.kt:1)).
 *
 * State is ephemeral per-webview — same policy as the IntelliJ chat.
 */
export class ChatWebviewProvider implements vscode.WebviewViewProvider {
  public static readonly VIEW_TYPE = 'rubberduck.chat';

  private view: vscode.WebviewView | undefined;
  private conversation: ChatTurn[] = [];

  resolveWebviewView(
    webviewView: vscode.WebviewView,
    _context: vscode.WebviewViewResolveContext,
    _token: vscode.CancellationToken,
  ): void {
    this.view = webviewView;
    webviewView.webview.options = {
      enableScripts: true,
      localResourceRoots: [],
    };
    webviewView.webview.html = this.renderHtml();

    webviewView.webview.onDidReceiveMessage(async (msg: { kind: string; text?: string }) => {
      switch (msg.kind) {
        case 'send':
          await this.handleSend(msg.text ?? '');
          break;
        case 'clear':
          this.conversation = [];
          this.postUpdate();
          break;
      }
    });

    this.postUpdate();
  }

  private async handleSend(text: string): Promise<void> {
    const question = text.trim();
    if (!question) return;
    if (Config.privacyMode) {
      this.view?.webview.postMessage({
        kind: 'error',
        text: 'Strict privacy is on — the chat would send your file to the backend. Disable in settings.',
      });
      return;
    }

    this.conversation.push({ role: 'user', text: question });
    this.postUpdate();

    const editor = vscode.window.activeTextEditor;
    const currentCode = editor?.document.getText() ?? '';
    const language = editor?.document.languageId ?? 'text';
    const filePath = editor ? PathRedactor.redact(editor.document.uri.fsPath) : null;

    try {
      const response = await BackendClient.getInstance().codeChat({
        conversation: [...this.conversation],
        current_code: currentCode,
        file_path: filePath,
        language,
      });
      this.conversation.push({ role: 'assistant', text: response.answer });
    } catch (err) {
      log.warn(`code-chat failed: ${err instanceof Error ? err.message : String(err)}`);
      this.conversation.push({
        role: 'assistant',
        text: '(backend unreachable — try again in a moment)',
      });
    }
    this.postUpdate();
  }

  private postUpdate(): void {
    this.view?.webview.postMessage({ kind: 'state', conversation: this.conversation });
  }

  private renderHtml(): string {
    const nonce = randomNonce();
    // Inline CSS + inline script behind a nonce so the CSP stays tight.
    return `<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta http-equiv="Content-Security-Policy"
      content="default-src 'none'; style-src 'unsafe-inline'; script-src 'nonce-${nonce}';">
<title>RubberDuck Chat</title>
<style>
  body { margin: 0; padding: 8px; font-family: var(--vscode-font-family); font-size: 12px; color: var(--vscode-foreground); }
  .transcript { display: flex; flex-direction: column; gap: 6px; margin-bottom: 8px; max-height: 60vh; overflow-y: auto; }
  .turn { padding: 6px 8px; border-left: 3px solid transparent; }
  .turn.user { background: rgba(34,211,238,0.08); border-left-color: #22d3ee; }
  .turn.assistant { background: rgba(139,92,246,0.08); border-left-color: #8b5cf6; }
  .hint { font-style: italic; color: var(--vscode-descriptionForeground); }
  .input-row { display: flex; gap: 4px; }
  input[type=text] { flex: 1; padding: 4px 6px; background: var(--vscode-input-background); color: var(--vscode-input-foreground); border: 1px solid var(--vscode-input-border); font-family: inherit; font-size: inherit; }
  button { padding: 4px 8px; background: var(--vscode-button-background); color: var(--vscode-button-foreground); border: none; cursor: pointer; font-family: inherit; font-size: inherit; }
  button:hover { background: var(--vscode-button-hoverBackground); }
  button:disabled { opacity: 0.5; cursor: not-allowed; }
</style>
</head>
<body>
  <div id="transcript" class="transcript">
    <div class="hint">Ask me anything about your code.</div>
  </div>
  <div class="input-row">
    <input id="input" type="text" placeholder="Ask a question about the file you're looking at">
    <button id="send">Send</button>
    <button id="clear">Clear</button>
  </div>
<script nonce="${nonce}">
  const vscode = acquireVsCodeApi();
  const transcript = document.getElementById('transcript');
  const input = document.getElementById('input');
  const sendBtn = document.getElementById('send');
  const clearBtn = document.getElementById('clear');

  function escapeHtml(s) {
    return s.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;')
            .replace(/"/g,'&quot;').replace(/'/g,'&#39;').replace(/\\n/g,'<br/>');
  }

  function render(state) {
    if (!state.conversation || state.conversation.length === 0) {
      transcript.innerHTML = '<div class="hint">Ask me anything about your code.</div>';
      return;
    }
    transcript.innerHTML = state.conversation.map(function (t) {
      const icon = t.role === 'assistant' ? '🦆' : '👤';
      return '<div class="turn ' + t.role + '">' + icon + ' ' + escapeHtml(t.text) + '</div>';
    }).join('');
    transcript.scrollTop = transcript.scrollHeight;
  }

  function send() {
    const text = input.value.trim();
    if (!text) return;
    input.value = '';
    vscode.postMessage({ kind: 'send', text: text });
  }

  sendBtn.addEventListener('click', send);
  clearBtn.addEventListener('click', function () { vscode.postMessage({ kind: 'clear' }); });
  input.addEventListener('keydown', function (e) { if (e.key === 'Enter') send(); });

  window.addEventListener('message', function (event) {
    const msg = event.data;
    if (msg.kind === 'state') render(msg);
    else if (msg.kind === 'error') {
      transcript.innerHTML = '<div class="hint">' + escapeHtml(msg.text) + '</div>' + transcript.innerHTML;
    }
  });
</script>
</body>
</html>`;
  }
}

function randomNonce(): string {
  return Math.random().toString(36).slice(2) + Date.now().toString(36);
}

export function registerChatWebviewProvider(context: vscode.ExtensionContext): void {
  const provider = new ChatWebviewProvider();
  context.subscriptions.push(
    vscode.window.registerWebviewViewProvider(ChatWebviewProvider.VIEW_TYPE, provider),
  );
  log.info('ChatWebviewProvider registered');
}
