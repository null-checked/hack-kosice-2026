/* =========================================================================
 *  RubberDuck Chat Web UI — app.js
 *  Shared by IntelliJ (JCEF) and VS Code (webview). Zero build, ES2020 IIFE.
 *
 *  Host bridge contract:
 *    window.rubberduck = {
 *      getConfig(), getSelection(), getOpenFiles(), getDiagnostics(),
 *      readFile(path), pickFile(), insertAtCursor(text), replaceSelection(text),
 *      copyToClipboard(text), listSkills(), getSkillBody(name), listModels(),
 *      log(msg), notify(msg, kind), onReady(), saveHistory(messages)
 *    }
 *  All methods optional — when absent, the UI falls back to browser-native
 *  behavior so `make chat-webui-serve` works standalone.
 *
 *  Host → UI entry point:
 *    window.rubberduckUI.execContextAction(name, payload?)
 *  (mirrors the jetbrains-cc-gui-main pattern — IDE actions dispatch via a
 *  single string-named switch so the native side never touches the DOM.)
 * ========================================================================= */

(() => {
  'use strict';

  // ------------------------------------------------------------------ Bridge
  const HOST = window.rubberduck || null;
  const Bridge = {
    getConfig:        async ()  => (await call('getConfig'))        ?? { backendBase: 'http://127.0.0.1:8765/api/v1', theme: 'dark', defaultModel: null, chatAvailableModels: [], chatAttachOpenFiles: false, chatAttachDiagnostics: false },
    getSelection:     async ()  => (await call('getSelection'))     ?? null,
    getOpenFiles:     async ()  => (await call('getOpenFiles'))     ?? [],
    getDiagnostics:   async ()  => (await call('getDiagnostics'))   ?? [],
    readFile:         async (p) => (await call('readFile', p))      ?? '',
    pickFile:         async ()  => (await call('pickFile'))         ?? null,
    insertAtCursor:   async (t) => call('insertAtCursor', t),
    replaceSelection: async (t) => call('replaceSelection', t),
    copyToClipboard:  async (t) => {
      if (HOST?.copyToClipboard) { try { return await HOST.copyToClipboard(t); } catch (_) {} }
      try { return await navigator.clipboard.writeText(t); } catch (_) {}
    },
    listSkills:       async ()  => (await call('listSkills'))       ?? [],
    getSkillBody:     async (n) => (await call('getSkillBody', n))  ?? null,
    listModels:       async ()  => (await call('listModels'))       ?? ['gpt-4o-mini', 'gpt-4o', 'claude-3-5-sonnet-20241022', 'llama3.1:8b', 'qwen2.5-coder:7b'],
    setModel:         async (m) => (await call('setModel', m))      ?? false,
    log:              (m)       => { try { HOST?.log ? HOST.log(String(m)) : console.log('[rubberduck]', m); } catch (_) {} },
    notify:           (m, k='info') => {
      try { HOST?.notify ? HOST.notify(String(m), k) : console[k === 'error' ? 'error' : 'log']('[rubberduck:' + k + ']', m); } catch (_) {}
    },
    saveHistory:      (msgs)    => { try { HOST?.saveHistory && HOST.saveHistory(msgs); } catch (_) {} },
    onReady:          ()        => { try { HOST?.onReady && HOST.onReady(); } catch (_) {} },
  };

  async function call(name, ...args) {
    try {
      const fn = HOST && HOST[name];
      if (typeof fn !== 'function') return null;
      const r = fn.apply(HOST, args);
      return r && typeof r.then === 'function' ? await r : r;
    } catch (e) {
      Bridge.log(`Bridge.${name} threw: ${e && e.message || e}`);
      return null;
    }
  }

  // ------------------------------------------------------------------ State
  const state = {
    messages:     [],
    attachments:  [],
    skillBody:    null,
    skillName:    null,
    activeStream: null,             // AbortController while a response streams
    backendBase:  'http://127.0.0.1:8765/api/v1',
    model:        null,
    models:       [],
    autoScroll:   true,
    openFiles:    [],
    skillsCache:  [],
    attachOpenFiles:    false,
    attachDiagnostics:  false,
  };

  // -------------------------------------------------------- Slash commands
  const SLASH_COMMANDS = [
    { key: '/explain',  desc: 'Explain the selected code or current file' },
    { key: '/tests',    desc: 'Generate unit tests for the selection' },
    { key: '/refactor', desc: 'Suggest a refactor of the selection' },
    { key: '/fix',      desc: 'Diagnose and propose a fix' },
    { key: '/doc',      desc: 'Generate a doc-comment for the selection' },
  ];

  // ------------------------------------------------------------------ DOM
  const $ = (sel) => document.querySelector(sel);
  const el = {
    messages:        $('#messages'),
    scrollPill:      $('#scroll-bottom-pill'),
    contextChips:    $('#context-chips'),
    composer:        $('#composer'),
    input:           $('#composer-input'),
    sendBtn:         $('#send-btn'),
    newChatBtn:      $('#new-chat-btn'),
    attachSelBtn:    $('#attach-selection-btn'),
    attachFileBtn:   $('#attach-file-btn'),
    modelPicker:     $('#model-picker'),
    statusPill:      $('#status-pill'),
    slashMenu:       $('#slash-menu'),
    attachToggles:   $('#attach-toggles'),
  };

  // ------------------------------------------------------------------ Utils
  const uuid = () => (crypto?.randomUUID ? crypto.randomUUID()
                     : 'm-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 9));
  const escapeHtml = (s) => String(s).replace(/[&<>"']/g,
    (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const ts = (d) => {
    const h = String(d.getHours()).padStart(2,'0');
    const m = String(d.getMinutes()).padStart(2,'0');
    return `${h}:${m}`;
  };
  const nearBottom = () => {
    const m = el.messages;
    return (m.scrollHeight - m.scrollTop - m.clientHeight) < 80;
  };
  const scrollToBottom = (smooth = false) => {
    el.messages.scrollTo({ top: el.messages.scrollHeight, behavior: smooth ? 'smooth' : 'auto' });
  };

  // ------------------------------------------------------------------ Init
  async function init() {
    // Setup marked + DOMPurify if loaded (they load via <script defer>).
    try {
      if (window.marked && window.marked.setOptions) {
        window.marked.setOptions({
          gfm: true,
          breaks: true,
          mangle: false,
          headerIds: false,
        });
      }
    } catch (_) {}

    // Apply host-provided config.
    const cfg = await Bridge.getConfig();
    if (cfg?.backendBase) state.backendBase = cfg.backendBase.replace(/\/+$/,'');
    if (cfg?.theme) document.body.dataset.theme = cfg.theme;
    // Prefer config-provided list (covers both IDEs); fall back to listModels bridge.
    state.models = (Array.isArray(cfg?.chatAvailableModels) && cfg.chatAvailableModels.length)
      ? cfg.chatAvailableModels
      : (await Bridge.listModels());
    state.model = cfg?.chatModel || cfg?.defaultModel || state.models[0] || null;
    state.attachOpenFiles   = Boolean(cfg?.chatAttachOpenFiles);
    state.attachDiagnostics = Boolean(cfg?.chatAttachDiagnostics);
    state.openFiles = await Bridge.getOpenFiles();
    state.skillsCache = await Bridge.listSkills();

    // Populate model picker + attach-toggle chips.
    populateModels();
    renderAttachToggles();

    // Wire listeners.
    wireEvents();

    // Render empty state + focus.
    renderChips();
    setStatus('connected', 'ready');
    el.input.focus();
    Bridge.onReady();
    Bridge.log('chat-webui ready');
  }

  function populateModels() {
    el.modelPicker.innerHTML = '';
    for (const m of state.models) {
      const opt = document.createElement('option');
      opt.value = m; opt.textContent = m;
      if (m === state.model) opt.selected = true;
      el.modelPicker.appendChild(opt);
    }
  }

  /**
   * Render the auto-attach status chips (open-files / diagnostics) in the
   * toolbar as read-only indicators. Full edit lives in IDE settings —
   * rubberduck.chatAttachOpenFiles / rubberduck.chatAttachDiagnostics.
   */
  function renderAttachToggles() {
    if (!el.attachToggles) return;
    el.attachToggles.innerHTML = '';
    const mkChip = (label, on, title) => {
      const c = document.createElement('span');
      c.className = 'attach-chip' + (on ? ' is-on' : ' is-off');
      c.title = title;
      c.setAttribute('role', 'status');
      c.innerHTML =
        `<span class="attach-chip-dot" aria-hidden="true"></span>` +
        `<span class="attach-chip-label">${label}</span>` +
        `<span class="attach-chip-state">${on ? 'on' : 'off'}</span>`;
      return c;
    };
    el.attachToggles.appendChild(
      mkChip('open files', state.attachOpenFiles,
        'Auto-attach open files to every chat turn (configure in IDE settings).')
    );
    el.attachToggles.appendChild(
      mkChip('diagnostics', state.attachDiagnostics,
        'Auto-attach current diagnostics to every chat turn (configure in IDE settings).')
    );
  }

  // ------------------------------------------------------------------ Events
  function wireEvents() {
    el.composer.addEventListener('submit', (e) => {
      e.preventDefault();
      if (state.activeStream) {
        // Stop button — abort current stream.
        state.activeStream.abort();
        return;
      }
      const v = el.input.value.trim();
      if (!v && state.attachments.length === 0) return;
      el.input.value = '';
      autoGrow();
      send(v);
    });

    el.input.addEventListener('input', () => {
      autoGrow();
      updateSendEnabled();
      updateSlashMenu();
    });
    el.input.addEventListener('keydown', (e) => {
      // Slash menu navigation first.
      if (!el.slashMenu.hidden) {
        if (e.key === 'ArrowDown') { e.preventDefault(); moveSlashSelection(1); return; }
        if (e.key === 'ArrowUp')   { e.preventDefault(); moveSlashSelection(-1); return; }
        if (e.key === 'Enter' || e.key === 'Tab') {
          const sel = el.slashMenu.querySelector('.popover-item.is-selected');
          if (sel) { e.preventDefault(); acceptSlashItem(sel); return; }
        }
        if (e.key === 'Escape') { e.preventDefault(); hideSlashMenu(); return; }
      }
      if (e.key === 'Enter' && !e.shiftKey && !e.ctrlKey && !e.metaKey) {
        e.preventDefault();
        el.composer.requestSubmit();
      } else if (e.key === 'Escape') {
        hideSlashMenu();
      }
    });

    el.modelPicker.addEventListener('change', (e) => {
      state.model = e.target.value;
      Bridge.log('model=' + state.model);
      // Persist to IDE settings when the host bridge supports it.
      try { HOST?.setModel && HOST.setModel(state.model); } catch (_) {}
    });

    el.newChatBtn.addEventListener('click', () => newChat());

    el.attachSelBtn.addEventListener('click', attachSelection);
    el.attachFileBtn.addEventListener('click', async () => {
      // Host bridge contract (unified): pickFile() → {filePath, content, language}
      // (null when the user cancels). Legacy string shape is tolerated so old
      // IDE builds keep working but new code should assume the object shape.
      const r = await Bridge.pickFile();
      if (!r) return;
      if (typeof r === 'string') {
        await addAttachment({ kind: 'file', path: r });
      } else {
        await addAttachment({
          kind: 'file',
          path: r.filePath || r.file_path || null,
          content: r.content || '',
          language: r.language || null,
        });
      }
    });

    el.messages.addEventListener('scroll', () => {
      const atBottom = nearBottom();
      state.autoScroll = atBottom;
      el.scrollPill.hidden = atBottom;
    });
    el.scrollPill.addEventListener('click', () => {
      state.autoScroll = true;
      el.scrollPill.hidden = true;
      scrollToBottom(true);
    });

    // Global: Cmd/Ctrl+K focuses input from anywhere in the webview.
    document.addEventListener('keydown', (e) => {
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'k') {
        e.preventDefault();
        el.input.focus();
      }
    });
  }

  function autoGrow() {
    const ta = el.input;
    ta.style.height = 'auto';
    const max = 220;
    ta.style.height = Math.min(ta.scrollHeight, max) + 'px';
  }

  function updateSendEnabled() {
    if (state.activeStream) { el.sendBtn.disabled = false; return; }
    const v = el.input.value.trim();
    el.sendBtn.disabled = !(v || state.attachments.length > 0);
  }

  // --------------------------------------------------- Slash / Mention menu
  function updateSlashMenu() {
    const v = el.input.value;
    const caret = el.input.selectionStart ?? v.length;
    const prefix = v.slice(0, caret);
    // Find trigger: last '/', '@', or nothing, scoped to the current token.
    const m = prefix.match(/(^|\s)([@/])([\w\-:/.]*)$/);
    if (!m) { hideSlashMenu(); return; }
    const trigger = m[2];
    const query = m[3].toLowerCase();

    if (trigger === '/') {
      showSlashItems(SLASH_COMMANDS
        .filter(c => c.key.slice(1).startsWith(query))
        .map(c => ({ key: c.key, desc: c.desc, action: 'slash', payload: c.key })));
    } else {
      // @ mention: @problems, @skill:<name>, open files.
      const items = [];
      items.push({ key: '@problems', desc: 'Attach current diagnostics', action: 'mention', payload: 'problems' });
      for (const sk of state.skillsCache) {
        // Tolerate both legacy string shape and the richer
        // {name, description, trigger} object shape so the UI keeps
        // working whichever the host bridge emits.
        const name = (typeof sk === 'string') ? sk : (sk && (sk.name || sk.id) || '');
        if (!name) continue;
        const desc = (typeof sk === 'object' && sk && sk.description) ? sk.description : 'Use skill prompt';
        const key = '@skill:' + name;
        if (!query || key.slice(1).toLowerCase().includes(query)) {
          items.push({ key, desc, action: 'mention', payload: 'skill:' + name });
        }
      }
      for (const f of state.openFiles) {
        const name = f.split(/[\\/]/).pop();
        const key = '@' + name;
        if (!query || name.toLowerCase().includes(query) || f.toLowerCase().includes(query)) {
          items.push({ key, desc: f, action: 'mention', payload: 'file:' + f });
        }
      }
      if (items.length === 0) items.push({ key: '(no matches)', desc: '', empty: true });
      showSlashItems(items);
    }
  }

  function showSlashItems(items) {
    el.slashMenu.innerHTML = '';
    if (!items.length) { hideSlashMenu(); return; }
    items.forEach((it, i) => {
      if (it.empty) {
        const emptyEl = document.createElement('div');
        emptyEl.className = 'popover-empty';
        emptyEl.textContent = it.key;
        el.slashMenu.appendChild(emptyEl);
        return;
      }
      const n = document.createElement('div');
      n.className = 'popover-item' + (i === 0 ? ' is-selected' : '');
      n.dataset.action = it.action;
      n.dataset.payload = it.payload;
      n.innerHTML = `<span class="pi-key">${escapeHtml(it.key)}</span>` +
                    `<span class="pi-desc">${escapeHtml(it.desc || '')}</span>`;
      n.addEventListener('mousedown', (e) => {
        e.preventDefault();
        acceptSlashItem(n);
      });
      el.slashMenu.appendChild(n);
    });
    el.slashMenu.hidden = false;
  }

  function moveSlashSelection(dir) {
    const items = Array.from(el.slashMenu.querySelectorAll('.popover-item'));
    if (!items.length) return;
    let idx = items.findIndex(n => n.classList.contains('is-selected'));
    if (idx < 0) idx = 0;
    items[idx].classList.remove('is-selected');
    idx = (idx + dir + items.length) % items.length;
    items[idx].classList.add('is-selected');
    items[idx].scrollIntoView({ block: 'nearest' });
  }

  async function acceptSlashItem(node) {
    const action = node.dataset.action;
    const payload = node.dataset.payload;
    if (action === 'slash') {
      // Replace the trailing /token with the chosen command + space.
      const v = el.input.value;
      const caret = el.input.selectionStart ?? v.length;
      const before = v.slice(0, caret).replace(/(^|\s)\/[\w\-]*$/, (m) => m.replace(/\/[\w\-]*$/, payload + ' '));
      el.input.value = before + v.slice(caret);
      el.input.selectionStart = el.input.selectionEnd = before.length;
    } else if (action === 'mention') {
      if (payload === 'problems') {
        const diags = await Bridge.getDiagnostics();
        if (diags && diags.length) {
          await addAttachment({ kind: 'diagnostics', items: diags });
        } else {
          Bridge.notify('No diagnostics available', 'info');
        }
      } else if (payload.startsWith('skill:')) {
        const name = payload.slice(6);
        const body = await Bridge.getSkillBody(name);
        if (body) {
          state.skillBody = body;
          state.skillName = name;
          await addAttachment({ kind: 'skill', name });
        } else {
          Bridge.notify('Skill not found: ' + name, 'warn');
        }
      } else if (payload.startsWith('file:')) {
        const path = payload.slice(5);
        await addAttachment({ kind: 'file', path });
      }
      // Remove the trailing @token from the input.
      const v = el.input.value;
      const caret = el.input.selectionStart ?? v.length;
      const before = v.slice(0, caret).replace(/(^|\s)@[\w\-:/.]*$/, (m) => m.replace(/@[\w\-:/.]*$/, ''));
      el.input.value = before + v.slice(caret);
      el.input.selectionStart = el.input.selectionEnd = before.length;
    }
    hideSlashMenu();
    autoGrow();
    updateSendEnabled();
    el.input.focus();
  }

  function hideSlashMenu() { el.slashMenu.hidden = true; el.slashMenu.innerHTML = ''; }

  // ---------------------------------------------------------- Attachments
  async function attachSelection() {
    const sel = await Bridge.getSelection();
    if (!sel) {
      Bridge.notify('No active editor selection', 'info');
      return;
    }
    await addAttachment({
      kind: 'selection',
      path: sel.file_path || sel.filePath || null,
      language: sel.language || null,
      code: sel.code || sel.text || ''
    });
  }

  async function addAttachment(att) {
    if (!att) return;
    // De-dup by logical identity
    const key = JSON.stringify([att.kind, att.path || att.name || att.items?.length]);
    if (state.attachments.some(a => JSON.stringify([a.kind, a.path || a.name || a.items?.length]) === key)) return;

    if (att.kind === 'file' && att.path && !att.content) {
      att.content = await Bridge.readFile(att.path);
    }
    state.attachments.push(att);
    renderChips();
    updateSendEnabled();
  }

  function removeAttachment(i) {
    const a = state.attachments[i];
    if (a?.kind === 'skill') { state.skillBody = null; state.skillName = null; }
    state.attachments.splice(i, 1);
    renderChips();
    updateSendEnabled();
  }

  function renderChips() {
    el.contextChips.innerHTML = '';
    state.attachments.forEach((a, i) => {
      const chip = document.createElement('span');
      chip.className = 'chip';
      let icon = '📎', label = '';
      switch (a.kind) {
        case 'selection':
          icon = '✂️';
          label = (a.path ? a.path.split(/[\\/]/).pop() : 'selection') +
                  ` (${String(a.code || '').split('\n').length} lines)`;
          break;
        case 'file':
          icon = '📄';
          label = a.path ? a.path.split(/[\\/]/).pop() : 'file';
          break;
        case 'diagnostics':
          icon = '⚠️';
          label = `${a.items?.length ?? 0} problem${(a.items?.length ?? 0) === 1 ? '' : 's'}`;
          break;
        case 'skill':
          icon = '🧠';
          label = 'skill:' + a.name;
          break;
        default: label = a.kind;
      }
      chip.innerHTML = `<span class="chip-icon">${icon}</span>` +
                      `<span class="chip-label">${escapeHtml(label)}</span>` +
                      `<button class="chip-remove" type="button" aria-label="Remove">×</button>`;
      chip.querySelector('.chip-remove').addEventListener('click', () => removeAttachment(i));
      el.contextChips.appendChild(chip);
    });
  }

  // ------------------------------------------------------------- Messages
  function addMessage(role, content, extra = {}) {
    const msg = {
      id: uuid(),
      role,
      content,
      createdAt: new Date(),
      streaming: false,
      ...extra,
    };
    state.messages.push(msg);
    const node = buildMessageNode(msg);
    msg._node = node;
    el.messages.appendChild(node);
    if (state.autoScroll) scrollToBottom(false);
    return msg;
  }

  function buildMessageNode(msg) {
    const wrap = document.createElement('article');
    wrap.className = 'msg msg-' + msg.role;
    wrap.dataset.id = msg.id;

    if (msg.role !== 'user') {
      const head = document.createElement('header');
      head.className = 'msg-head';
      const author = msg.role === 'assistant' ? '🦆 RubberDuck' :
                     msg.role === 'system'    ? 'ⓘ system'      :
                     msg.role === 'error'     ? '⚠ error'       : msg.role;
      head.innerHTML = `<span class="msg-author">${escapeHtml(author)}</span>` +
                       `<span class="msg-ts">${ts(msg.createdAt)}</span>`;
      wrap.appendChild(head);
    }

    const body = document.createElement('div');
    body.className = 'msg-body';
    wrap.appendChild(body);

    const foot = document.createElement('footer');
    foot.className = 'msg-foot';
    wrap.appendChild(foot);

    renderBody(msg, body);
    renderFoot(msg, foot);
    return wrap;
  }

  function renderBody(msg, body) {
    if (msg.role === 'user') {
      // Users: plain text, preserve newlines.
      body.textContent = msg.content;
      return;
    }
    // Assistant: markdown → sanitize → HTML.
    const html = renderMarkdown(msg.content || '') +
                 (msg.streaming ? '<span class="streaming-cursor">▍</span>' : '');
    body.innerHTML = html;
    enhanceCodeBlocks(body);
  }

  function renderMarkdown(md) {
    try {
      const raw = (window.marked && typeof window.marked.parse === 'function')
                    ? window.marked.parse(md)
                    : ('<p>' + escapeHtml(md).replace(/\n/g, '<br>') + '</p>');
      const clean = window.DOMPurify
                      ? window.DOMPurify.sanitize(raw, { ADD_ATTR: ['target'] })
                      : raw;
      return clean;
    } catch (e) {
      Bridge.log('markdown render error: ' + (e && e.message));
      return '<p>' + escapeHtml(md) + '</p>';
    }
  }

  function enhanceCodeBlocks(root) {
    const pres = root.querySelectorAll('pre > code');
    pres.forEach((code) => {
      const pre = code.parentElement;
      if (pre.parentElement && pre.parentElement.classList.contains('code-block-wrap')) return;

      // Detect language from class="language-xxx"
      const m = Array.from(code.classList).find(c => c.startsWith('language-'));
      const lang = m ? m.slice(9) : '';

      // Syntax highlight.
      try {
        if (window.hljs) {
          if (lang && window.hljs.getLanguage && window.hljs.getLanguage(lang)) {
            const out = window.hljs.highlight(code.textContent, { language: lang, ignoreIllegals: true });
            code.innerHTML = out.value;
          } else {
            const out = window.hljs.highlightAuto(code.textContent);
            code.innerHTML = out.value;
            code.classList.add('hljs', 'language-' + (out.language || 'plaintext'));
          }
          code.classList.add('hljs');
        }
      } catch (_) { /* noop */ }

      // Diff post-process for ```diff blocks.
      if (lang === 'diff') {
        const lines = code.innerHTML.split('\n');
        code.innerHTML = lines.map(line => {
          const plain = line.replace(/<[^>]+>/g, '');
          if (plain.startsWith('+') && !plain.startsWith('+++')) return `<span class="diff-add">${line}</span>`;
          if (plain.startsWith('-') && !plain.startsWith('---')) return `<span class="diff-del">${line}</span>`;
          return line;
        }).join('\n');
      }

      // Wrap + chrome
      const wrap = document.createElement('div');
      wrap.className = 'code-block-wrap';
      pre.parentElement.insertBefore(wrap, pre);
      wrap.appendChild(pre);

      if (lang) {
        const pill = document.createElement('span');
        pill.className = 'code-lang-pill';
        pill.textContent = lang;
        wrap.appendChild(pill);
      }

      const actions = document.createElement('div');
      actions.className = 'code-actions';
      actions.innerHTML =
        `<button class="code-action-btn" data-act="copy">Copy</button>` +
        `<button class="code-action-btn" data-act="insert">Insert</button>` +
        `<button class="code-action-btn" data-act="replace">Replace</button>`;
      wrap.appendChild(actions);

      actions.addEventListener('click', async (e) => {
        const btn = e.target.closest('[data-act]');
        if (!btn) return;
        const text = code.textContent;
        const act = btn.dataset.act;
        if (act === 'copy') {
          await Bridge.copyToClipboard(text);
          const prev = btn.textContent;
          btn.textContent = 'Copied!';
          btn.classList.add('copied');
          setTimeout(() => { btn.textContent = prev; btn.classList.remove('copied'); }, 1200);
        } else if (act === 'insert') {
          await Bridge.insertAtCursor(text);
        } else if (act === 'replace') {
          await Bridge.replaceSelection(text);
        }
      });
    });
  }

  function renderFoot(msg, foot) {
    foot.innerHTML = '';
    if (msg.role === 'assistant') {
      if (msg.usage) {
        const tot = msg.usage.total_tokens;
        const pt = msg.usage.prompt_tokens;
        const ct = msg.usage.completion_tokens;
        const pieces = [];
        if (tot != null) pieces.push(`${tot} tok`);
        if (pt != null && ct != null) pieces.push(`(${pt} + ${ct})`);
        if (pieces.length) {
          const u = document.createElement('span');
          u.className = 'msg-usage';
          u.textContent = pieces.join(' ');
          foot.appendChild(u);
        }
      }
      const copyBtn = document.createElement('button');
      copyBtn.type = 'button';
      copyBtn.textContent = 'Copy';
      copyBtn.title = 'Copy full message';
      copyBtn.addEventListener('click', async () => {
        await Bridge.copyToClipboard(msg.content || '');
        copyBtn.textContent = 'Copied';
        setTimeout(() => copyBtn.textContent = 'Copy', 1000);
      });
      foot.appendChild(copyBtn);

      if (!msg.streaming) {
        const retryBtn = document.createElement('button');
        retryBtn.type = 'button';
        retryBtn.textContent = 'Retry';
        retryBtn.addEventListener('click', () => retryFromMessage(msg.id));
        foot.appendChild(retryBtn);
      }
    }
  }

  function updateMessage(msg) {
    if (!msg._node) return;
    const body = msg._node.querySelector('.msg-body');
    const foot = msg._node.querySelector('.msg-foot');
    if (body) renderBody(msg, body);
    if (foot) renderFoot(msg, foot);
    if (state.autoScroll) scrollToBottom(false);
  }

  // --------------------------------------------------------- Send flow
  async function send(rawText) {
    const text = (rawText || '').trim();
    // Slash-command detection — strip leading `/<cmd>` token from user text.
    let slashCommand = null;
    const slashMatch = text.match(/^\/(explain|tests|refactor|fix|doc)\b\s*(.*)$/);
    let userText = text;
    if (slashMatch) {
      slashCommand = slashMatch[1];
      userText = slashMatch[2].trim();
    }

    // `@skill:<name>` mention — when user types it manually (no popup picked),
    // resolve the body on-the-fly and strip the token from the echoed text.
    // First match wins; extra tokens are also stripped but ignored.
    const skillTokenRe = /(^|\s)@skill:([A-Za-z0-9_\-. ]+?)(?=\s|$)/;
    const skillMatch = userText.match(skillTokenRe);
    if (skillMatch && !state.skillBody) {
      const candidate = skillMatch[2].trim();
      try {
        const body = await Bridge.getSkillBody(candidate);
        if (body) {
          state.skillBody = body;
          state.skillName = candidate;
        }
      } catch (_) { /* ignore — skill simply unresolved */ }
    }
    // Strip *all* `@skill:*` tokens from the user-visible message so the
    // transcript stays clean regardless of whether we resolved a body.
    userText = userText.replace(/(^|\s)@skill:[A-Za-z0-9_\-. ]+?(?=\s|$)/g, '$1').trim();

    // If no free-form text but we have attachments, craft a default prompt.
    let displayText = userText;
    if (!displayText && state.attachments.length > 0) {
      displayText = slashCommand ? `/${slashCommand}` : '(context attached)';
    }

    addMessage('user', displayText, { attachments: [...state.attachments] });
    const assistant = addMessage('assistant', '', { streaming: true });

    // Build request body according to backend ChatRequest schema.
    const body = buildChatRequestBody(userText || displayText, slashCommand);

    // Reset transient state.
    const sentAttachments = [...state.attachments];
    state.attachments = [];
    state.skillBody = null;
    state.skillName = null;
    renderChips();
    updateSendEnabled();
    setStatus('streaming', 'streaming…');
    setSendAsStop(true);

    try {
      await streamChat(body, assistant);
    } catch (err) {
      if (err && err.name === 'AbortError') {
        assistant.content += assistant.content ? '\n\n> _(stopped)_' : '_(stopped by user)_';
        assistant.finishReason = 'aborted';
      } else {
        assistant.content += (assistant.content ? '\n\n' : '') +
          `> **Error:** ${escapeHtml(err && err.message ? err.message : String(err))}`;
        setStatus('error', 'error');
        Bridge.notify('Chat error: ' + (err && err.message || err), 'error');
      }
    } finally {
      assistant.streaming = false;
      state.activeStream = null;
      updateMessage(assistant);
      setSendAsStop(false);
      updateSendEnabled();
      if (document.getElementById('status-pill').classList.contains('status-streaming'))
        setStatus('connected', 'ready');
      Bridge.saveHistory(state.messages);
    }
  }

  function buildChatRequestBody(userText, slashCommand) {
    // Convert message history to backend shape (system/user/assistant only).
    const messages = [];
    for (const m of state.messages) {
      if (m.role === 'user' || m.role === 'assistant' || m.role === 'system') {
        // Skip the assistant placeholder we just added (empty content)
        if (m.role === 'assistant' && m.streaming && !m.content) continue;
        messages.push({ role: m.role, content: m.content || '' });
      }
    }
    // Replace last user message content with actual text (attachments go in context).
    if (messages.length && messages[messages.length - 1].role === 'user') {
      messages[messages.length - 1].content = userText || messages[messages.length - 1].content;
    }

    // Build ChatContext from current attachments.
    const context = { attached_files: [], diagnostics: [], open_files: state.openFiles || [] };
    let selection = null;
    for (const a of (state.messages[state.messages.length - 2]?.attachments || [])) {
      if (a.kind === 'selection') {
        selection = {
          file_path: a.path || null,
          language: a.language || null,
          code: a.code || '',
        };
      } else if (a.kind === 'file') {
        context.attached_files.push({
          file_path: a.path,
          content: a.content || '',
          language: a.language || null,
        });
      } else if (a.kind === 'diagnostics') {
        for (const d of (a.items || [])) {
          context.diagnostics.push({
            file_path: d.file_path || d.filePath || '',
            line: d.line ?? null,
            severity: d.severity || 'error',
            message: d.message || String(d),
          });
        }
      }
    }
    if (selection) context.selection = selection;

    return {
      messages,
      context,
      slash_command: slashCommand,
      skill_name: state.skillName || null,
      skill_body: state.skillBody || null,
      model: state.model || null,
      stream: true,
    };
  }

  async function streamChat(body, assistantMsg) {
    state.activeStream = new AbortController();
    const resp = await fetch(`${state.backendBase}/chat`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Accept': 'text/event-stream' },
      body: JSON.stringify(body),
      signal: state.activeStream.signal,
    });
    if (!resp.ok) {
      let msg = `HTTP ${resp.status}`;
      try {
        const j = await resp.json();
        if (j?.error?.description) msg = j.error.description;
        else if (j?.error_description) msg = j.error_description;
        else if (j?.detail) msg = typeof j.detail === 'string' ? j.detail : JSON.stringify(j.detail);
      } catch (_) {}
      throw new Error(msg);
    }
    if (!resp.body) throw new Error('Streaming not supported (no response body)');

    const reader = resp.body.getReader();
    const decoder = new TextDecoder('utf-8');
    let buf = '';
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      buf += decoder.decode(value, { stream: true });
      let idx;
      while ((idx = buf.indexOf('\n\n')) >= 0) {
        const frame = buf.slice(0, idx);
        buf = buf.slice(idx + 2);
        // Frames may have multiple lines; find the data: line(s).
        const lines = frame.split('\n');
        for (const line of lines) {
          if (!line.startsWith('data: ')) continue;
          const data = line.slice(6);
          if (data === '[DONE]') return;
          try {
            const ev = JSON.parse(data);
            handleSSE(ev, assistantMsg);
          } catch (e) {
            Bridge.log('Bad SSE frame: ' + data.slice(0, 120));
          }
        }
      }
    }
  }

  function handleSSE(ev, msg) {
    switch (ev.type) {
      case 'token':
        msg.content += ev.delta || '';
        updateMessage(msg);
        break;
      case 'usage':
        msg.usage = {
          prompt_tokens:     ev.prompt_tokens     ?? null,
          completion_tokens: ev.completion_tokens ?? null,
          total_tokens:      ev.total_tokens      ?? null,
        };
        updateMessage(msg);
        break;
      case 'done':
        msg.finishReason = ev.finish_reason || 'stop';
        break;
      case 'error':
        msg.content += (msg.content ? '\n\n' : '') +
          `> **Error:** ${ev.message || 'unknown error'}`;
        updateMessage(msg);
        break;
      case 'tool_call':
        msg.content += (msg.content ? '\n\n' : '') +
          `*(tool_call: ${ev.name}${ev.arguments ? ' ' + JSON.stringify(ev.arguments) : ''})*`;
        updateMessage(msg);
        break;
      default:
        Bridge.log('Unknown SSE event type: ' + ev.type);
    }
  }

  async function retryFromMessage(msgId) {
    const idx = state.messages.findIndex(m => m.id === msgId);
    if (idx < 1) return;
    // Find the user message that triggered this reply (previous user role).
    let prevUserIdx = -1;
    for (let i = idx - 1; i >= 0; i--) {
      if (state.messages[i].role === 'user') { prevUserIdx = i; break; }
    }
    if (prevUserIdx < 0) return;
    const userMsg = state.messages[prevUserIdx];
    // Drop the assistant reply and anything after it.
    const removed = state.messages.splice(idx);
    for (const m of removed) m._node?.remove();
    // Re-add attachments so buildChatRequestBody sees them on the prior user.
    state.attachments = userMsg.attachments ? [...userMsg.attachments] : [];
    await send(userMsg.content);
  }

  // ------------------------------------------------------------- Status
  function setStatus(kind, label) {
    el.statusPill.classList.remove('status-connected', 'status-streaming', 'status-error');
    el.statusPill.classList.add('status-' + kind);
    el.statusPill.querySelector('.status-label').textContent = label;
  }

  function setSendAsStop(on) {
    if (on) {
      el.sendBtn.classList.add('is-stop');
      el.sendBtn.querySelector('.send-label').textContent = 'Stop';
      el.sendBtn.querySelector('.send-kbd').textContent = '⎋';
      el.sendBtn.disabled = false;
    } else {
      el.sendBtn.classList.remove('is-stop');
      el.sendBtn.querySelector('.send-label').textContent = 'Send';
      el.sendBtn.querySelector('.send-kbd').textContent = '↵';
    }
  }

  // ------------------------------------------------------------- Actions
  function newChat() {
    if (state.activeStream) state.activeStream.abort();
    clearMessages();
    state.attachments = [];
    state.skillBody = null;
    state.skillName = null;
    renderChips();
    setStatus('connected', 'ready');
    el.input.value = '';
    autoGrow();
    updateSendEnabled();
    el.input.focus();
  }

  function clearMessages() {
    state.messages = [];
    el.messages.innerHTML = '';
  }

  function getSelectedText() {
    const sel = window.getSelection?.();
    return sel ? sel.toString() : '';
  }

  // ----------------------------------------------------- Bridge export
  // Single entry point mirroring TO_BE_REMOVED/jetbrainsccgui's
  // `window.execContextAction(name)` pattern so IDE-side actions can drive
  // the UI without poking the DOM.
  window.rubberduckUI = {
    execContextAction(name, payload) {
      try {
        switch (name) {
          case 'copy':
            return Bridge.copyToClipboard(getSelectedText() || lastAssistantContent());
          case 'paste':
            el.input.focus();
            if (payload != null) {
              const start = el.input.selectionStart, end = el.input.selectionEnd, v = el.input.value;
              el.input.value = v.slice(0, start) + String(payload) + v.slice(end);
              el.input.selectionStart = el.input.selectionEnd = start + String(payload).length;
              autoGrow(); updateSendEnabled();
            } else if (document.execCommand) {
              try { document.execCommand('paste'); } catch (_) {}
            }
            return;
          case 'new-chat':         return newChat();
          case 'clear':            return clearMessages();
          case 'focus-input':      return el.input.focus();
          case 'stop':             return state.activeStream?.abort();
          case 'set-theme':        document.body.dataset.theme = payload || 'dark'; return;
          case 'attach-selection': return attachSelection();
          case 'attach-file':
            return Bridge.pickFile().then(r => {
              if (!r) return;
              if (typeof r === 'string') return addAttachment({ kind: 'file', path: r });
              return addAttachment({
                kind: 'file',
                path: r.filePath || r.file_path || null,
                content: r.content || '',
                language: r.language || null,
              });
            });
          case 'set-attach-toggles':
            state.attachOpenFiles   = Boolean(payload?.attachOpenFiles);
            state.attachDiagnostics = Boolean(payload?.attachDiagnostics);
            renderAttachToggles();
            return;
          case 'send':             return send(payload != null ? String(payload) : el.input.value);
          case 'insert-text':
            el.input.value += (payload != null ? String(payload) : '');
            el.input.focus(); autoGrow(); updateSendEnabled();
            return;
          case 'set-open-files':
            state.openFiles = Array.isArray(payload) ? payload : [];
            return;
          case 'set-diagnostics':
            // Host can push diagnostics as a pre-attached chip.
            if (Array.isArray(payload) && payload.length)
              addAttachment({ kind: 'diagnostics', items: payload });
            return;
          default:
            Bridge.log('Unknown execContextAction: ' + name);
        }
      } catch (e) {
        Bridge.log('execContextAction error: ' + (e && e.message || e));
      }
    },
    addAttachment,
    setTheme: (t)  => { document.body.dataset.theme = t || 'dark'; },
    setModel: (m)  => { state.model = m; if (el.modelPicker) el.modelPicker.value = m; },
    setModels: (list) => {
      state.models = Array.isArray(list) ? list : state.models;
      populateModels();
    },
    setAttachToggles: (t) => {
      if (!t) return;
      state.attachOpenFiles   = Boolean(t.attachOpenFiles);
      state.attachDiagnostics = Boolean(t.attachDiagnostics);
      renderAttachToggles();
    },
    newChat,
    clearMessages,
    getMessages: () => state.messages.map(({ _node, ...rest }) => rest),
  };

  function lastAssistantContent() {
    for (let i = state.messages.length - 1; i >= 0; i--)
      if (state.messages[i].role === 'assistant') return state.messages[i].content || '';
    return '';
  }

  // ----------------------------------------------------- DOMContentLoaded
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
