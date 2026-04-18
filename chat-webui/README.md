# 🦆 RubberDuck Chat Web UI

Zero-build, dependency-free chat UI shared by the IntelliJ plugin (JCEF) and
the VS Code extension (webview). One HTML + one JS + one CSS + three vendored
libraries — total payload <200 KB.

## Directory layout

```
chat-webui/
├── README.md            — this file
├── index.html           — entry point
├── app.js               — behavior (IIFE; no modules, no build)
├── styles.css           — tokens + bubbles + composer + popovers + print
└── vendor/
    ├── marked.min.js        (marked@12.0.2, MIT)
    ├── dompurify.min.js     (DOMPurify@3.0.11, MPL-2.0)
    ├── highlight.min.js     (highlight.js@11.9.0 common bundle, BSD-3)
    ├── highlight.css        (highlight.js github-dark theme, BSD-3)
    └── FETCH.sh             — re-download script (pinned URLs)
```

## Standalone preview

```bash
# from the repo root
make chat-webui-serve    # python3 -m http.server 8080 --directory chat-webui
open http://127.0.0.1:8080
```

When no host bridge is present (plain browser), the UI uses default config
— `backendBase: http://127.0.0.1:8765/api/v1`, `theme: dark`, 4 placeholder
models — and IDE-only actions like "Insert" / "Replace Selection" are no-ops.

## Consumption by the IDE shells

```bash
make sync-chat-webui
```

Copies `chat-webui/*` into both:
- `plugin/src/main/resources/webui/chat/`   (Step 4 wires JCEF to load it)
- `vscode-extension/media/chat/`            (Step 5 wires the webview)

This target is idempotent. Steps 4 and 5 will declare their respective builds
as dependents of `sync-chat-webui` so the latest UI is always packaged.

## Host bridge contract

The host (IntelliJ plugin or VS Code extension) **may** attach
`window.rubberduck` *before* the page executes. Every method is optional —
absent methods fall back to browser defaults. Methods may return a value
directly or a `Promise`.

| Method | Signature | Purpose |
| --- | --- | --- |
| `getConfig()` | `→ { backendBase, theme, defaultModel? }` | Initial config before first render. |
| `getSelection()` | `→ { file_path, language, code } \| null` | Active editor selection, used when attaching. |
| `getOpenFiles()` | `→ string[]` | File paths open in the IDE, fuels the `@` mention menu. |
| `getDiagnostics()` | `→ [{ file_path, line, severity, message }]` | Problems list, attached via `@problems`. |
| `readFile(path)` | `(path) → string` | Read a file the user mentioned. |
| `pickFile()` | `→ string \| null` | Show native file picker; return selected path. |
| `insertAtCursor(text)` | `(text) → void` | Insert code block at the active editor caret. |
| `replaceSelection(text)` | `(text) → void` | Replace the active editor selection. |
| `copyToClipboard(text)` | `(text) → void` | Optional override of `navigator.clipboard`. |
| `listSkills()` | `→ string[]` | Names of available `.skills/<name>/SKILL.md` packs. |
| `getSkillBody(name)` | `(name) → string` | Markdown body of a skill, attached inline. |
| `listModels()` | `→ string[]` | Populates the model picker. |
| `log(msg)` | `(msg) → void` | Route UI logs to the IDE's idea.log / OUTPUT channel. |
| `notify(msg, kind)` | `(msg, "info"\|"warn"\|"error") → void` | User-visible IDE notification. |
| `saveHistory(messages)` | `(msgs[]) → void` | Persist the chat log (called after every turn). |
| `onReady()` | `→ void` | Fired once after the UI has wired its listeners. |

## Host → UI entry point

Mirroring the pattern established in
`TO_BE_REMOVED/jetbrainsccgui`'s `ChatToolWindowAction` subclasses
(`window.execContextAction('copy')`, etc.), the UI exposes exactly one
function for the host to invoke — no other DOM pokes required:

```js
window.rubberduckUI.execContextAction(name, payload?)
```

| `name`              | `payload`                 | Effect |
| --- | --- | --- |
| `copy`              | —                         | Copy the current selection (or last assistant message) to clipboard. |
| `paste`             | `string` (optional)       | Insert text at caret in the composer; if omitted, tries native paste. |
| `new-chat`          | —                         | Abort any active stream and start a fresh conversation. |
| `clear`             | —                         | Remove all messages without resetting state. |
| `focus-input`       | —                         | Focus the composer textarea. |
| `stop`              | —                         | Abort the in-flight SSE stream. |
| `set-theme`         | `"dark"` \| `"light"`     | Toggle theme tokens. |
| `attach-selection`  | —                         | Call `getSelection()` and add a selection chip. |
| `attach-file`       | —                         | Call `pickFile()` and add a file chip. |
| `send`              | `string` (optional)       | Send the given message (or current input). |
| `insert-text`       | `string`                  | Append text into the composer. |
| `set-open-files`    | `string[]`                | Update the `@` mention file list. |
| `set-diagnostics`   | `[{…}]`                   | Pre-attach diagnostics as a chip. |

The UI also exposes these convenience methods on `window.rubberduckUI`:

- `setTheme(t)`, `setModel(m)`, `setModels(list)`
- `addAttachment(att)` — programmatic chip addition
- `newChat()`, `clearMessages()`
- `getMessages()` — snapshot of the in-memory conversation

## Theming

Two token sets live on `body[data-theme="dark"]` and `body[data-theme="light"]`.
The host drives this via `execContextAction('set-theme', 'light')` or directly
`document.body.dataset.theme = 'light'`. Key tokens:

```
--bg, --bg-alt, --bg-inset, --fg, --fg-muted, --fg-faint
--border, --border-soft
--accent, --accent-hov, --accent-2
--user-bubble (gradient), --assistant-bg
--error, --success, --warn
--code-bg, --code-fg
--shadow-lg, --shadow-md, --scrollbar, --selection
--diff-add-bg, --diff-add-fg, --diff-del-bg, --diff-del-fg
```

Override any token in a host-injected `<style>` to match the IDE's current
color scheme exactly — e.g., IntelliJ's JBColor keys or VS Code's
`--vscode-editor-background`.

## Security

- **Every** LLM-authored markdown passes through `DOMPurify.sanitize()`
  before reaching `innerHTML`. If `DOMPurify` fails to load, we fall back
  to plain-text rendering rather than unsanitized HTML.
- `marked` is configured with `{gfm:true, breaks:true, mangle:false, headerIds:false}`.
- `highlight.js` is the "common" bundle — auto-detect only over well-known
  languages; no arbitrary-language loader.
- There are **no remote asset loads**: the HTML only references files under
  `./vendor/`. This keeps VS Code webview CSP (`'self'` + nonce) happy and
  works offline in IntelliJ JCEF.
- No inline `<script>` bodies and no inline event handlers — everything is
  wired in `app.js`. Acceptable under `script-src 'self'`.

## Keyboard shortcuts (inside the webview)

| Keys | Action |
| --- | --- |
| `Enter` | Send |
| `Shift+Enter` | Newline |
| `Esc` | Close slash/mention menu |
| `↑` / `↓` (menu open) | Navigate suggestions |
| `Tab` (menu open) | Accept highlighted suggestion |
| `Ctrl/Cmd+K` | Focus the composer |

IDE-level shortcuts (Ctrl+C copy while chat is focused, Ctrl+L clear,
Ctrl+N new chat, …) are bound on the native side and forwarded via
`execContextAction`.

## Versions / licenses

| Library | Version | License |
| --- | --- | --- |
| marked | 12.0.2 | MIT |
| DOMPurify | 3.0.11 | MPL-2.0 / Apache-2.0 |
| highlight.js | 11.9.0 | BSD-3-Clause |

Re-fetch with `bash chat-webui/vendor/FETCH.sh`.
