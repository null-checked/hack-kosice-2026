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
    listModels:       async ()  => (await call('listModels'))       ?? ['gpt-5.4', 'gpt-5', 'gpt-4o-mini', 'gpt-4o', 'claude-3-5-sonnet-20241022', 'llama3.1:8b', 'qwen2.5-coder:7b'],
    setModel:         async (m) => (await call('setModel', m))      ?? false,
    log:              (m)       => { try { HOST?.log ? HOST.log(String(m)) : console.log('[rubberduck]', m); } catch (_) {} },
    notify:           (m, k='info') => {
      try { HOST?.notify ? HOST.notify(String(m), k) : console[k === 'error' ? 'error' : 'log']('[rubberduck:' + k + ']', m); } catch (_) {}
    },
    // ---- Phase 2.2: Per-session JSON history --------------------------------
    // The host writes each session to its own `.rubberduck/sessions/<id>.json`
    // (Kotlin + TS both atomic). We send `{id, title?, messages, model?}` and
    // receive `{ok, id, title, created_at, updated_at}` on success. A fresh
    // session (no id yet) gets a UUID back from the host; the caller holds
    // onto that id for subsequent saves in the same conversation.
    saveHistory:      async (session) => (await call('saveHistory', session)) ?? { ok: false },
    listSessions:     async ()       => (await call('listSessions'))          ?? [],
    loadSession:      async (id)     => (await call('loadSession', { id })),
    deleteSession:    async (id)     => (await call('deleteSession', { id })) ?? false,
    renameSession:    async (id, title) => (await call('renameSession', { id, title })) ?? false,
    onReady:          ()        => { try { HOST?.onReady && HOST.onReady(); } catch (_) {} },
    // ---- Phase 1.1: Apply/Reject inline code edits --------------------------
    // `applyEdit(payload)` asks the host to open an IDE-native diff preview
    // for the proposed edit. The host resolves with `{ applied: boolean }`.
    // When the host doesn't implement applyEdit we return a stub so the UI
    // still works against a plain browser (Apply becomes a no-op).
    applyEdit:        async (p) => (await call('applyEdit', p))     ?? { applied: false },
    rejectEdit:       async (p) => call('rejectEdit', p),
    // The host-bridge channel marshals a single JSON arg per call, so we
    // bundle the three logical fields into one object.
    emitIntervention: (kind, edit_id, type) => {
      try { HOST?.emitIntervention && HOST.emitIntervention({ kind, edit_id, type }); }
      catch (_) {}
    },
    // ---- Phase 1.2: @-file fuzzy search -------------------------------------
    // Returns `[{path, score}, ...]` ranked high-to-low across the whole
    // project, not just open editors. Host returns `[]` when strict privacy
    // is on. Falls back to an empty array when the host doesn't implement
    // the method (e.g. plain-browser dev) — callers degrade to open-files.
    searchFiles:      async (query, limit = 30) => (await call('searchFiles', { query, limit })) ?? [],
    // ---- Phase 2.1: BM25 semantic search ------------------------------------
    // Returns `[{path, score, snippet}, ...]` ranked by BM25 across the
    // project's code corpus. Triggered from the `@?<query>` prefix in the
    // composer's `@` autocomplete (filename fuzzy stays `@<query>`).
    searchSemantic:   async (query, k = 5) => (await call('searchSemantic', { query, k })) ?? [],
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
    // Chat execution mode (wired to the Ask/Edit/Agent dropdown). Maps to
    // `ChatRequest.mode` in the backend:
    //   "ask"   — plain streaming chat (no edits extracted).
    //   "edit"  — envelope mode: structured `{answer, edits}` JSON in ONE
    //             LLM call; server emits `code_edit` events inline.
    //   "agent" — DeepAgents multi-agent orchestrator; emits sub-agent
    //             `tool_call` chips + one `code_edit` per finished file.
    //             Requires `ORCHESTRATOR_ENABLED=true` + OpenAI-family
    //             provider on the backend (see health.orchestrator_available).
    mode:               'ask',
    // Phase 1.2: debounced fuzzy @-file search state. Keyed by the query
    // string currently being fetched so we can ignore responses that arrive
    // after the user has moved on.
    fileSearchTimer:    null,
    fileSearchToken:    0,
    fileSearchResults:  [],
    // Phase 2.2: per-session persistence. `sessionId` is populated after
    // the first successful `saveHistory` response; subsequent saves echo
    // the same id so they overwrite the same file. `sessions` is the rail
    // list kept in sync with disk via `listSessions()` after each mutation.
    sessionId:          null,
    sessionTitle:       null,
    sessions:           [],
    railCollapsed:      false,
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
    modePicker:      $('#mode-picker'),
    modelPicker:     $('#model-picker'),
    statusPill:      $('#status-pill'),
    slashMenu:       $('#slash-menu'),
    attachToggles:   $('#attach-toggles'),
    sessionRail:     $('#session-rail'),
    sessionRailList: $('#session-rail-list'),
    sessionRailEmpty:$('#session-rail-empty'),
    sessionRailToggle: $('#session-rail-toggle'),
    sessionNewBtn:   $('#session-new-btn'),
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

    // Phase 2.2 — populate the session rail (best-effort; no throw path).
    // Restore rail collapsed state from localStorage so it survives reload.
    try {
      state.railCollapsed = localStorage.getItem('rubberduck.railCollapsed') === '1';
      applyRailCollapsedState();
    } catch (_) {}
    refreshSessionRail();

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

    // Phase 2.2 — session rail wiring.
    if (el.sessionNewBtn) {
      el.sessionNewBtn.addEventListener('click', () => newChat());
    }
    if (el.sessionRailToggle) {
      el.sessionRailToggle.addEventListener('click', () => toggleSessionRail());
    }

    if (el.modePicker) {
      el.modePicker.value = state.mode;
      el.modePicker.addEventListener('change', (e) => {
        const next = e.target.value;
        if (next === 'ask' || next === 'edit' || next === 'agent') {
          state.mode = next;
          Bridge.log('mode=' + state.mode);
        }
      });
    }

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

  // No helper needed anymore — the native <select> reflects state.mode
  // and the change listener in wireEvents writes state.mode back.

  // --------------------------------------------------- Slash / Mention menu
  function updateSlashMenu() {
    const v = el.input.value;
    const caret = el.input.selectionStart ?? v.length;
    const prefix = v.slice(0, caret);
    // Find trigger:
    //   "/"    → slash command
    //   "@"    → filename fuzzy mention
    //   "@?"   → semantic (BM25) mention
    // Allow `?` as a leading character on the mention token.
    const m = prefix.match(/(^|\s)([@/])(\?)?([\w\-:/.]*)$/);
    if (!m) { hideSlashMenu(); return; }
    const trigger = m[2];
    const semantic = !!m[3];
    const query = m[4].toLowerCase();

    if (trigger === '/') {
      showSlashItems(SLASH_COMMANDS
        .filter(c => c.key.slice(1).startsWith(query))
        .map(c => ({ key: c.key, desc: c.desc, action: 'slash', payload: c.key })));
    } else {
      // @ mention — build synchronous results (fast: no IPC), then kick off
      // a debounced project-wide search that re-renders when it lands.
      // `semantic` chooses between the exact-name fuzzy path and the BM25
      // semantic path; the host bridge owns the ranking either way.
      renderMentionMenu(query, state.fileSearchResults, semantic);
      scheduleFileSearch(query, semantic);
    }
  }

  /**
   * Render the @-mention popover from the current synchronous sources plus
   * any project-wide search hits already on hand. Called (a) immediately on
   * every keystroke and (b) again when a debounced `Bridge.search{Files,
   * Semantic}` response lands — it's a pure function of `query` + `state`.
   *
   * Ordering rationale (filename mode `@`):
   *   1. `@problems`  — always first, most frequent demo path.
   *   2. `@skill:*`   — short list, filtered by substring.
   *   3. Project file hits (when query non-empty) — ranked by the bridge.
   *   4. Open editors — filtered by substring. Skipped when we already have
   *      project hits for the same path to avoid dupes.
   *   5. Empty fallback.
   *
   * Semantic mode (`@?`):
   *   - No @problems/skills entries — semantic hits only. Query may be
   *     empty (`@?`) in which case we show a helpful hint and let the
   *     user type more.
   *   - Each item is badged with its BM25 score and a short snippet so
   *     the user can tell why a file was matched even when the filename
   *     doesn't contain the query.
   */
  function renderMentionMenu(query, projectHits, semantic = false) {
    const items = [];

    if (semantic) {
      if (!query) {
        items.push({ key: '@?<query>', desc: 'Semantic search (BM25) across the project', empty: true });
      } else if (!Array.isArray(projectHits) || projectHits.length === 0) {
        items.push({ key: '(searching…)', desc: '', empty: true });
      } else {
        for (const hit of projectHits) {
          const p = hit && hit.path;
          if (!p) continue;
          const name = p.split(/[\\/]/).pop() || p;
          const score = hit.score != null ? Number(hit.score).toFixed(2) : '';
          const snippet = (hit.snippet || p).slice(0, 120);
          items.push({
            key: '@?' + name,
            desc: score ? `${snippet}  ·  score ${score}` : snippet,
            action: 'mention',
            payload: 'file:' + p,
            badge: 'semantic',
          });
        }
      }
      if (items.length === 0) items.push({ key: '(no matches)', desc: '', empty: true });
      showSlashItems(items);
      return;
    }

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
    const seenPaths = new Set();
    // Project-wide fuzzy hits land when query is non-empty and the bridge
    // returned results. `projectHits` is an array of `{path, score}`.
    if (query && Array.isArray(projectHits) && projectHits.length) {
      for (const hit of projectHits) {
        const p = hit && hit.path;
        if (!p || seenPaths.has(p)) continue;
        seenPaths.add(p);
        const name = p.split(/[\\/]/).pop() || p;
        items.push({ key: '@' + name, desc: p, action: 'mention', payload: 'file:' + p });
      }
    }
    // Open editors — always a quick win when no query; filtered on substring
    // when there is one. We suppress duplicates the bridge already returned.
    for (const f of state.openFiles) {
      if (seenPaths.has(f)) continue;
      const name = f.split(/[\\/]/).pop();
      const key = '@' + name;
      if (!query || name.toLowerCase().includes(query) || f.toLowerCase().includes(query)) {
        items.push({ key, desc: f, action: 'mention', payload: 'file:' + f });
        seenPaths.add(f);
      }
    }
    if (items.length === 0) items.push({ key: '(no matches)', desc: '', empty: true });
    showSlashItems(items);
  }

  /**
   * Debounce the bridge search so each keystroke doesn't spam the host.
   * Results land in `state.fileSearchResults` keyed by the fetch `token` —
   * stale responses (a later query started after this one) are ignored.
   * Routes to `searchSemantic` when `semantic=true`, else `searchFiles`.
   */
  function scheduleFileSearch(query, semantic = false) {
    if (state.fileSearchTimer) clearTimeout(state.fileSearchTimer);
    if (!query) {
      // No query = synchronous popup is already complete; clear any stale
      // project hits so empty `@` stays snappy and uncluttered.
      state.fileSearchResults = [];
      return;
    }
    const myToken = ++state.fileSearchToken;
    state.fileSearchTimer = setTimeout(async () => {
      try {
        const hits = semantic
          ? await Bridge.searchSemantic(query, 10)
          : await Bridge.searchFiles(query, 30);
        if (myToken !== state.fileSearchToken) return; // superseded
        state.fileSearchResults = Array.isArray(hits) ? hits : [];
        // Only re-render if the @-menu is still showing the same query and
        // mode (filename vs semantic).
        if (isMentionPopoverForQuery(query, semantic)) {
          renderMentionMenu(query, state.fileSearchResults, semantic);
        }
      } catch (e) {
        Bridge.log((semantic ? 'searchSemantic' : 'searchFiles') + ' failed: ' + (e && e.message || e));
      }
    }, 150);
  }

  /**
   * True if the composer still has a matching `@[?]<query>` trigger at the
   * caret — used to avoid clobbering the popup after the user has moved on
   * (switched to a `/` slash, hit Esc, started a new token).
   */
  function isMentionPopoverForQuery(query, semantic) {
    if (el.slashMenu.hidden) return false;
    const v = el.input.value;
    const caret = el.input.selectionStart ?? v.length;
    const m = v.slice(0, caret).match(/(^|\s)@(\?)?([\w\-:/.]*)$/);
    if (!m) return false;
    const wasSemantic = !!m[2];
    return wasSemantic === !!semantic && (m[3] || '').toLowerCase() === query;
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
      if (it.badge) n.dataset.badge = it.badge;
      // Badge span — rendered only when `it.badge` is set (e.g. BM25 `semantic`
      // hits). CSS targets `.popover-item[data-badge="..."]` so new badge
      // variants only need a style rule, no JS change.
      const badgeHtml = it.badge
        ? `<span class="pi-badge pi-badge-${escapeHtml(it.badge)}">${escapeHtml(it.badge)}</span>`
        : '';
      n.innerHTML = `<span class="pi-key">${escapeHtml(it.key)}</span>` +
                    `<span class="pi-desc">${escapeHtml(it.desc || '')}</span>` +
                    badgeHtml;
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

    // Phase 2.2 — Rewind affordance on user messages. Hover reveals a small
    // ✎ button; clicking it turns the bubble into an editable textarea with
    // Save/Cancel. On Save we truncate state.messages to this index and
    // re-send. The button is hidden while a stream is active to avoid
    // racing with an in-flight reply.
    if (msg.role === 'user') {
      const rewind = document.createElement('button');
      rewind.type = 'button';
      rewind.className = 'msg-rewind';
      rewind.setAttribute('aria-label', 'Edit & regenerate');
      rewind.title = 'Edit & regenerate (rewind from here)';
      rewind.innerHTML = '<span aria-hidden="true">✎</span>';
      rewind.addEventListener('click', (e) => {
        e.preventDefault();
        e.stopPropagation();
        rewindToMessage(msg.id);
      });
      wrap.appendChild(rewind);
    }
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
    // Agent-mode sub-agent chips render ABOVE the prose so the user sees
    // the execution trace first, then the final summary. Rendered as
    // native <details> so each one is click-to-expand for more detail.
    if (msg.role === 'assistant' && Array.isArray(msg.agentChips) && msg.agentChips.length) {
      const tray = buildAgentChipsTray(msg.agentChips);
      body.insertBefore(tray, body.firstChild);
    }
    if (msg.role === 'assistant' && Array.isArray(msg.edits) && msg.edits.length) {
      const tray = document.createElement('div');
      tray.className = 'edit-cards';
      for (const edit of msg.edits) tray.appendChild(buildEditCard(msg, edit));
      body.appendChild(tray);
    }
  }

  // ----------------------------------------------- Agent sub-agent chips
  // One <details> per chip. Expanding shows the brief (for dispatches)
  // or the full todo list (for plan chips). Because we squelch sub-agent
  // tokens in v1, the expanded body is just the brief/summary — still
  // better than a flat-text line because the user can collapse the
  // trace to summaries-only once they've skimmed it.
  function buildAgentChipsTray(chips) {
    const tray = document.createElement('div');
    tray.className = 'agent-chips';
    for (const c of chips) {
      const el = document.createElement('details');
      el.className = 'agent-chip agent-chip-' + c.kind
        + (c.status ? ' agent-chip-' + c.status : '');
      const summary = document.createElement('summary');
      if (c.kind === 'plan') {
        summary.innerHTML =
          `<span class="agent-chip-icon" aria-hidden="true">📋</span>` +
          `<span class="agent-chip-label"><strong>Plan</strong> ` +
          `(${c.todos.length} item${c.todos.length === 1 ? '' : 's'})</span>`;
        const list = document.createElement('ul');
        list.className = 'agent-chip-todos';
        for (const t of c.todos) {
          const li = document.createElement('li');
          li.className = 'agent-chip-todo status-' + (t.status || 'pending');
          li.textContent =
            (t.status === 'completed' ? '☑ ' :
             t.status === 'in_progress' ? '▶ ' : '☐ ') + t.task;
          list.appendChild(li);
        }
        el.appendChild(summary);
        el.appendChild(list);
      } else {
        // subagent chip — dispatched or returned.
        const icon = c.status === 'done' ? '✓' : '▶';
        const label = c.status === 'done'
          ? `<strong>${escapeHtml(c.subagent)}</strong> done`
          : `<strong>${escapeHtml(c.subagent)}</strong> running`;
        const snippet = c.status === 'done'
          ? escapeHtml((c.summary || '').slice(0, 80))
          : escapeHtml((c.brief || '').slice(0, 80));
        summary.innerHTML =
          `<span class="agent-chip-icon" aria-hidden="true">${icon}</span>` +
          `<span class="agent-chip-label">${label}</span>` +
          (snippet ? `<span class="agent-chip-snippet">— ${snippet}</span>` : '');
        const body = document.createElement('div');
        body.className = 'agent-chip-body';
        if (c.brief) {
          const b = document.createElement('div');
          b.className = 'agent-chip-brief';
          b.innerHTML = `<em>Brief:</em> ${escapeHtml(c.brief)}`;
          body.appendChild(b);
        }
        if (c.summary) {
          const s = document.createElement('div');
          s.className = 'agent-chip-summary';
          s.innerHTML = `<em>Summary:</em> ${escapeHtml(c.summary)}`;
          body.appendChild(s);
        }
        el.appendChild(summary);
        el.appendChild(body);
      }
      tray.appendChild(el);
    }
    return tray;
  }

  // ---------------------------------------------- Apply/Reject code-edit card
  // Mirrors `plugin/.../CodeEditCardComponent.kt` visually so a user switching
  // IDEs sees the same UX: file chip, 3-line preview, Preview/Apply + Reject
  // buttons, plus a collapsed inline unified-diff when the server provided it.
  function buildEditCard(msg, edit) {
    const card = document.createElement('div');
    card.className = 'edit-card status-' + (edit.status || 'pending');
    card.dataset.editId = edit.edit_id;

    const head = document.createElement('div');
    head.className = 'edit-card-head';
    const pathLabel = edit.file_path
      ? edit.file_path.split(/[\\/]/).slice(-2).join('/')
      : '(current file)';
    const range = (edit.start_line != null && edit.end_line != null)
      ? ` L${edit.start_line}–${edit.end_line}` : '';
    head.innerHTML =
      `<span class="edit-card-icon" aria-hidden="true">✎</span>` +
      `<span class="edit-card-path" title="${escapeHtml(edit.file_path || '')}">` +
        `${escapeHtml(pathLabel)}${escapeHtml(range)}</span>` +
      `<span class="edit-card-status-pill" aria-live="polite"></span>`;
    card.appendChild(head);

    if (edit.explanation) {
      const why = document.createElement('div');
      why.className = 'edit-card-why';
      why.textContent = edit.explanation;
      card.appendChild(why);
    }

    const preview = document.createElement('pre');
    preview.className = 'edit-card-preview';
    const lines = (edit.proposed_code || '').split('\n');
    const snippet = lines.slice(0, 3).join('\n');
    const more = lines.length > 3 ? `\n… +${lines.length - 3} lines` : '';
    preview.textContent = snippet + more;
    card.appendChild(preview);

    if (edit.diff_unified) {
      // Phase 2.3 — Auto-expand the unified diff inline. The toggle stays
      // as a collapse affordance so users can hide it when scanning many
      // cards. Green/red line backgrounds come from `.diff-add`/`.diff-del`
      // which already use the global `--diff-*-bg/fg` tokens (theme-aware).
      const diffPre = document.createElement('pre');
      diffPre.className = 'edit-card-diff';
      diffPre.hidden = false;
      diffPre.innerHTML = renderUnifiedDiff(edit.diff_unified);
      const toggle = document.createElement('button');
      toggle.type = 'button';
      toggle.className = 'edit-card-diff-toggle';
      toggle.textContent = 'Hide diff';
      toggle.addEventListener('click', () => {
        const willHide = !diffPre.hidden;
        diffPre.hidden = willHide;
        toggle.textContent = willHide ? 'Show diff' : 'Hide diff';
      });
      card.appendChild(toggle);
      card.appendChild(diffPre);
    }

    const actions = document.createElement('div');
    actions.className = 'edit-card-actions';
    const applyBtn = document.createElement('button');
    applyBtn.type = 'button';
    applyBtn.className = 'edit-card-btn edit-card-btn-apply';
    applyBtn.textContent = 'Preview & Apply';
    const rejectBtn = document.createElement('button');
    rejectBtn.type = 'button';
    rejectBtn.className = 'edit-card-btn edit-card-btn-reject';
    rejectBtn.textContent = 'Reject';
    actions.appendChild(applyBtn);
    actions.appendChild(rejectBtn);
    // Hide the Apply/Reject row when the card is already settled (agent mode
    // auto-applies; retries render with their final status). Leaves the
    // head's status pill as the only indicator, keeping the card slim.
    if (edit.status === 'applied' || edit.status === 'rejected') {
      actions.hidden = true;
    }
    card.appendChild(actions);

    const setStatus = (s) => {
      edit.status = s;
      card.classList.remove('status-pending', 'status-applied', 'status-rejected');
      card.classList.add('status-' + s);
      const pill = head.querySelector('.edit-card-status-pill');
      pill.textContent = s === 'applied' ? 'Applied'
                       : s === 'rejected' ? 'Rejected'
                       : '';
      applyBtn.disabled = s !== 'pending';
      rejectBtn.disabled = s !== 'pending';
      // Hide the button row once the card is settled — the head's pill
      // already communicates "Applied" / "Rejected".
      actions.hidden = (s !== 'pending');
    };
    setStatus(edit.status || 'pending');

    applyBtn.addEventListener('click', async () => {
      applyBtn.disabled = true;
      try {
        const res = await Bridge.applyEdit({
          edit_id:       edit.edit_id,
          file_path:     edit.file_path,
          original_code: edit.original_code,
          proposed_code: edit.proposed_code,
          explanation:   edit.explanation,
          start_line:    edit.start_line,
          end_line:      edit.end_line,
          diff_unified:  edit.diff_unified,
        });
        if (res && res.applied) {
          setStatus('applied');
          Bridge.emitIntervention('chat_edit', edit.edit_id, 'INTERVENTION_ACCEPTED');
          Bridge.notify('Edit applied', 'info');
        } else {
          // User dismissed the IDE-native diff preview — keep as pending so
          // they can try again from the same card.
          applyBtn.disabled = false;
        }
      } catch (err) {
        applyBtn.disabled = false;
        Bridge.notify('Apply failed: ' + (err && err.message || err), 'error');
      }
    });

    rejectBtn.addEventListener('click', () => {
      setStatus('rejected');
      Bridge.rejectEdit({ edit_id: edit.edit_id });
      Bridge.emitIntervention('chat_edit', edit.edit_id, 'INTERVENTION_DISMISSED');
    });

    return card;
  }

  // Render a unified-diff string as coloured spans — reuses the diff-add/del
  // CSS tokens. Strict "start-of-line +/-" check so `+++` / `---` header
  // lines render as plain context.
  function renderUnifiedDiff(diff) {
    return String(diff).split('\n').map((raw) => {
      const line = escapeHtml(raw);
      if (raw.startsWith('+++') || raw.startsWith('---') || raw.startsWith('@@')) {
        return `<span class="diff-hunk">${line}</span>`;
      }
      if (raw.startsWith('+')) return `<span class="diff-add">${line}</span>`;
      if (raw.startsWith('-')) return `<span class="diff-del">${line}</span>`;
      return line;
    }).join('\n');
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
    // Tag the assistant turn with the mode we dispatched under, so handleSSE
    // can decide whether code_edit events auto-apply (agent) or surface
    // Apply/Reject buttons (edit).
    const assistant = addMessage('assistant', '', { streaming: true, mode: state.mode });

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
      // Phase 2.2 — persist the turn under its per-session JSON file. The
      // host returns `{ok, id, title, ...}`; we hold onto `id` + `title` so
      // subsequent saves in the same conversation overwrite the same file.
      persistCurrentSession();
    }
  }

  /**
   * Persist the current conversation via the host bridge and refresh the
   * session rail. Rolls transparently from "no session yet" → "session has
   * id" on first call: the host returns a UUID, we keep it in `state` for
   * all future saves, and the rail shows the new item without a full
   * reload round-trip.
   */
  async function persistCurrentSession() {
    const serialized = serializeMessagesForStorage(state.messages);
    if (!serialized.length) return;
    try {
      const resp = await Bridge.saveHistory({
        id:       state.sessionId,
        title:    state.sessionTitle,
        model:    state.model,
        messages: serialized,
      });
      if (resp && resp.ok) {
        state.sessionId    = resp.id    || state.sessionId;
        state.sessionTitle = resp.title || state.sessionTitle;
        refreshSessionRail();
      }
    } catch (e) {
      Bridge.log('saveHistory failed: ' + (e && e.message || e));
    }
  }

  /**
   * Reduce the in-memory message array down to the storable subset. Drops:
   *  - DOM node handles (`_node`) — not JSON-safe.
   *  - Attachment FILE content — the bytes are wasteful to store and can
   *    be reattached from the project on replay.
   * Keeps everything the rewind / replay UX needs: role, content, id,
   * createdAt, edits (including their Apply/Reject status), finishReason.
   */
  function serializeMessagesForStorage(msgs) {
    return msgs.map((m) => {
      const out = {
        id:        m.id,
        role:      m.role,
        content:   m.content,
        createdAt: m.createdAt instanceof Date ? m.createdAt.toISOString() : m.createdAt,
      };
      if (m.streaming) out.streaming = true;
      if (m.finishReason) out.finishReason = m.finishReason;
      if (Array.isArray(m.edits) && m.edits.length) {
        out.edits = m.edits.map((e) => ({ ...e }));
      }
      if (Array.isArray(m.attachments) && m.attachments.length) {
        // Strip file `content` to keep the on-disk session small; reference
        // by `path/kind/name` is enough to let the user see what was attached.
        out.attachments = m.attachments.map((a) => {
          const slim = { kind: a.kind };
          if (a.path) slim.path = a.path;
          if (a.name) slim.name = a.name;
          if (a.filePath) slim.filePath = a.filePath;
          if (a.language) slim.language = a.language;
          return slim;
        });
      }
      return out;
    });
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

    // Phase 1.1: when Allow-Edits is ON, forward every attached file as a
    // top-level `context_files[]` too. The backend uses that list to power
    // structured edits that reference specific files; the legacy
    // `context.attached_files` stays populated for non-edit prompts.
    const context_files = [];
    for (const f of (context.attached_files || [])) {
      if (f && f.file_path) {
        context_files.push({ path: f.file_path, content: f.content || '' });
      }
    }

    // `mode` is the primary dispatch signal; `allow_edits` is kept as a
    // legacy mirror so the backend's `resolved_mode` fallback still matches
    // when an older server sees `allow_edits=true` without `mode`.
    return {
      messages,
      context,
      slash_command: slashCommand,
      skill_name: state.skillName || null,
      skill_body: state.skillBody || null,
      model: state.model || null,
      stream: true,
      mode: state.mode,
      allow_edits: state.mode === 'edit' || state.mode === 'agent',
      context_files,
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
      case 'tool_call': {
        // Agent mode overloads `tool_call` for three cases; each renders
        // as an interactive <details> chip in the `agentChips` tray above
        // the markdown body. See `renderAgentChips`.
        //
        //   - name="write_todos"  → one planning chip with a todo list
        //   - name="task"         → sub-agent dispatch (opens in "running")
        //   - name="task_return"  → pairs with the last running dispatch;
        //                           flips it to "done" and stores the summary
        //
        // Anything else falls back to a generic markdown line so legacy
        // tool-calls from the other modes keep rendering.
        msg.agentChips = Array.isArray(msg.agentChips) ? msg.agentChips : [];
        const args = ev.arguments || {};
        if (ev.name === 'write_todos' && Array.isArray(args.todos)) {
          msg.agentChips.push({
            id:    uuid(),
            kind:  'plan',
            todos: args.todos.map(t => ({
              task: t.task || '',
              status: t.status || 'pending',
            })),
          });
        } else if (ev.name === 'task' && args.subagent) {
          msg.agentChips.push({
            id:       uuid(),
            kind:     'subagent',
            subagent: args.subagent,
            brief:    args.brief || '',
            summary:  null,
            status:   'running',
          });
        } else if (ev.name === 'task_return') {
          // Pair with the most recent still-running subagent chip.
          for (let i = msg.agentChips.length - 1; i >= 0; i--) {
            const c = msg.agentChips[i];
            if (c.kind === 'subagent' && c.status === 'running') {
              c.summary = args.summary || 'done';
              c.status = 'done';
              break;
            }
          }
        } else {
          // Unknown tool call — fall back to inline markdown so the user
          // still sees *something*.
          const inline = `*(tool_call: ${ev.name}${ev.arguments ? ' ' + JSON.stringify(ev.arguments) : ''})*`;
          msg.content += (msg.content ? '\n\n' : '') + inline;
        }
        updateMessage(msg);
        break;
      }
      case 'code_edit': {
        // One inline Apply/Reject card per structured edit the backend
        // emitted (edit mode) OR an auto-applied card (agent mode).
        // Store on the assistant message so it re-renders through
        // `updateMessage` and survives a later `retryFromMessage`.
        msg.edits = Array.isArray(msg.edits) ? msg.edits : [];
        const edit = {
          edit_id:       ev.edit_id || uuid(),
          file_path:     ev.file_path || '',
          original_code: ev.original_code || '',
          proposed_code: ev.proposed_code || '',
          explanation:   ev.explanation || '',
          start_line:    ev.start_line ?? null,
          end_line:      ev.end_line ?? null,
          diff_unified:  ev.diff_unified || '',
          status:        'pending',
        };

        // Dedup by file_path: agent runs can produce 2–3 edits for the
        // same file (reviewer re-confirms code-writer's work, or
        // code-writer emits the same file twice from multiple rounds).
        // Replace the prior card in place with the latest content rather
        // than stacking — the final proposed_code is what the user wants
        // to apply, not the earlier drafts.
        const prevIdx = edit.file_path
          ? msg.edits.findIndex(e => e.file_path === edit.file_path && e.status === 'pending')
          : -1;
        if (prevIdx >= 0) {
          edit.edit_id = msg.edits[prevIdx].edit_id;  // preserve stable id for the bridge
          msg.edits[prevIdx] = edit;
        } else {
          msg.edits.push(edit);
        }

        Bridge.emitIntervention('chat_edit', edit.edit_id, 'INTERVENTION_SHOWN');

        // Agent mode auto-applies — the user opted into multi-agent codegen,
        // they don't want to babysit Apply/Reject on every file the agents
        // produced. Edit mode keeps the manual Apply/Reject flow.
        if (msg.mode === 'agent') {
          edit.status = 'applied';
          // Fire-and-forget; the IDE handles conflicts / failures itself.
          // We update the message first so the card renders in the
          // applied state immediately, then kick off the bridge call.
          updateMessage(msg);
          Bridge.applyEdit({
            edit_id:       edit.edit_id,
            file_path:     edit.file_path,
            original_code: edit.original_code,
            proposed_code: edit.proposed_code,
            explanation:   edit.explanation,
            start_line:    edit.start_line,
            end_line:      edit.end_line,
            diff_unified:  edit.diff_unified,
          }).then((res) => {
            if (res && res.applied) {
              Bridge.emitIntervention('chat_edit', edit.edit_id, 'INTERVENTION_ACCEPTED');
            } else {
              // Host refused the auto-apply — downgrade the card to pending
              // so the user can retry manually.
              edit.status = 'pending';
              updateMessage(msg);
            }
          }).catch((err) => {
            Bridge.log('auto-apply failed: ' + (err && err.message || err));
            edit.status = 'pending';
            updateMessage(msg);
          });
          break;
        }

        updateMessage(msg);
        break;
      }
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
  async function newChat() {
    if (state.activeStream) state.activeStream.abort();
    // Phase 2.2 — save the current session before wiping it, so the
    // conversation the user was mid-way through isn't lost. Fire-and-forget
    // is fine; a brief lag is acceptable and the rail refresh handles its
    // own async lifecycle.
    if (state.messages.length) {
      await persistCurrentSession().catch(() => {});
    }
    clearMessages();
    state.sessionId    = null;
    state.sessionTitle = null;
    state.attachments  = [];
    state.skillBody    = null;
    state.skillName    = null;
    renderChips();
    setStatus('connected', 'ready');
    el.input.value = '';
    autoGrow();
    updateSendEnabled();
    renderSessionRail();  // un-highlight the previously-active session
    el.input.focus();
  }

  function clearMessages() {
    state.messages = [];
    el.messages.innerHTML = '';
  }

  // ============================================================== Session rail
  // Phase 2.2 — Left-column list of saved sessions. Data flows through
  // `Bridge.listSessions/loadSession/deleteSession/renameSession` which hit
  // per-session JSON files under `.rubberduck/sessions/`. Each call mutates
  // disk first, then `refreshSessionRail` re-reads the list so there's a
  // single source of truth (the filesystem, not an in-memory shadow).

  /** Fetch sessions from the host and re-render the rail. No-op when the
   *  host bridge doesn't support sessions (e.g. browser standalone). */
  async function refreshSessionRail() {
    if (!el.sessionRail) return;
    try {
      const list = await Bridge.listSessions();
      state.sessions = Array.isArray(list) ? list : [];
    } catch (e) {
      Bridge.log('listSessions failed: ' + (e && e.message || e));
      state.sessions = [];
    }
    renderSessionRail();
  }

  /** Render `state.sessions` into the rail. Highlights the active row by
   *  `state.sessionId`; shows an empty placeholder when no sessions exist. */
  function renderSessionRail() {
    if (!el.sessionRailList) return;
    const list = state.sessions || [];
    el.sessionRailList.innerHTML = '';
    if (!list.length) {
      if (el.sessionRailEmpty) el.sessionRailEmpty.hidden = false;
      return;
    }
    if (el.sessionRailEmpty) el.sessionRailEmpty.hidden = true;

    for (const s of list) {
      const id = s && s.id;
      if (!id) continue;
      const title = (s.title || '').trim() || 'Untitled session';
      const item = document.createElement('div');
      item.className = 'session-item' + (id === state.sessionId ? ' is-active' : '');
      item.setAttribute('role', 'listitem');
      item.dataset.id = id;
      item.innerHTML =
        `<span class="session-item-title" title="${escapeHtml(title)}">${escapeHtml(title)}</span>` +
        `<button class="session-item-menu" type="button" aria-label="Session actions" title="Rename / delete">…</button>`;

      // Click anywhere except the overflow menu → load that session.
      item.addEventListener('click', (e) => {
        if (e.target.closest('.session-item-menu')) return;
        selectSession(id);
      });
      const menuBtn = item.querySelector('.session-item-menu');
      menuBtn.addEventListener('click', (e) => {
        e.preventDefault();
        e.stopPropagation();
        openSessionMenu(item, id, title);
      });
      el.sessionRailList.appendChild(item);
    }
  }

  /** Load a session by id — persist the current turn first, then replace
   *  `state.messages` with the stored transcript and rebuild the DOM. */
  async function selectSession(id) {
    if (!id || id === state.sessionId) return;
    if (state.activeStream) {
      // Don't interrupt an in-flight stream; the user can stop it first.
      Bridge.notify('Stop the current response before switching sessions', 'info');
      return;
    }
    // Persist the current session so we don't lose a mid-turn conversation.
    if (state.messages.length) {
      await persistCurrentSession().catch(() => {});
    }
    let session;
    try {
      session = await Bridge.loadSession(id);
    } catch (e) {
      Bridge.notify('Could not load session: ' + (e && e.message || e), 'error');
      return;
    }
    if (!session) {
      Bridge.notify('Session not found', 'warn');
      await refreshSessionRail();
      return;
    }
    state.sessionId    = session.id || id;
    state.sessionTitle = session.title || null;
    state.attachments  = [];
    state.skillBody    = null;
    state.skillName    = null;
    renderChips();
    rebuildTranscriptFromMessages(Array.isArray(session.messages) ? session.messages : []);
    renderSessionRail();            // re-highlight the now-active row
    el.input.focus();
  }

  /** Wipe the transcript and rebuild it from a stored messages array. Each
   *  saved message keeps its id + createdAt + edits so rewind, retry, and
   *  Apply/Reject status all survive a reload. */
  function rebuildTranscriptFromMessages(messages) {
    clearMessages();
    for (const saved of messages) {
      // Skip any `streaming:true` assistant stub that happened to be saved
      // — those were mid-stream placeholders and carry no useful content.
      if (saved.role === 'assistant' && saved.streaming && !saved.content) continue;
      const msg = {
        id:        saved.id || uuid(),
        role:      saved.role,
        content:   saved.content || '',
        createdAt: saved.createdAt ? new Date(saved.createdAt) : new Date(),
        streaming: false,
      };
      if (saved.finishReason) msg.finishReason = saved.finishReason;
      if (Array.isArray(saved.edits)) msg.edits = saved.edits.map((e) => ({ ...e }));
      if (Array.isArray(saved.attachments)) msg.attachments = saved.attachments.map((a) => ({ ...a }));
      state.messages.push(msg);
      const node = buildMessageNode(msg);
      msg._node = node;
      el.messages.appendChild(node);
    }
    if (state.autoScroll) scrollToBottom(false);
  }

  /** Flip the rail's collapsed state + persist to localStorage. */
  function toggleSessionRail() {
    state.railCollapsed = !state.railCollapsed;
    try { localStorage.setItem('rubberduck.railCollapsed', state.railCollapsed ? '1' : '0'); } catch (_) {}
    applyRailCollapsedState();
  }

  /** Apply the current `state.railCollapsed` to the DOM. Pure — safe to
   *  call multiple times (init hydration + toggle clicks share this). */
  function applyRailCollapsedState() {
    document.body.classList.toggle('rail-collapsed', !!state.railCollapsed);
    if (el.sessionRailToggle) {
      el.sessionRailToggle.setAttribute('aria-expanded', state.railCollapsed ? 'false' : 'true');
      el.sessionRailToggle.setAttribute('aria-label',
        state.railCollapsed ? 'Expand session rail' : 'Collapse session rail');
      el.sessionRailToggle.title = state.railCollapsed ? 'Expand sessions' : 'Collapse sessions';
    }
  }

  /** Minimal overflow menu for a rail row — two options, rename + delete.
   *  Closes on outside-click or Escape. We render the menu inline as a
   *  sibling so it inherits z-index from the rail and can't be clipped by
   *  transcript scrolling. */
  function openSessionMenu(itemEl, id, title) {
    // Clean any previously-open menu first.
    document.querySelectorAll('.session-item-menupop').forEach(n => n.remove());
    document.querySelectorAll('.session-item.is-menu-open').forEach(n => n.classList.remove('is-menu-open'));

    itemEl.classList.add('is-menu-open');
    const pop = document.createElement('div');
    pop.className = 'session-item-menupop';
    pop.innerHTML =
      `<button type="button" data-act="rename">Rename…</button>` +
      `<button type="button" class="is-danger" data-act="delete">Delete</button>`;
    itemEl.appendChild(pop);

    const close = () => { pop.remove(); itemEl.classList.remove('is-menu-open'); };
    const onDocClick = (e) => { if (!pop.contains(e.target)) { close(); document.removeEventListener('mousedown', onDocClick, true); } };
    const onKey      = (e) => { if (e.key === 'Escape') { close(); document.removeEventListener('keydown', onKey, true); } };
    document.addEventListener('mousedown', onDocClick, true);
    document.addEventListener('keydown', onKey, true);

    pop.addEventListener('click', async (e) => {
      const btn = e.target.closest('[data-act]');
      if (!btn) return;
      e.preventDefault();
      e.stopPropagation();
      close();
      if (btn.dataset.act === 'rename') {
        await promptRenameSession(id, title);
      } else if (btn.dataset.act === 'delete') {
        await confirmDeleteSession(id, title);
      }
    });
  }

  async function promptRenameSession(id, oldTitle) {
    // eslint-disable-next-line no-alert
    const next = window.prompt('Rename session', oldTitle || '');
    if (next == null) return;
    const trimmed = next.trim();
    if (!trimmed || trimmed === oldTitle) return;
    try {
      const ok = await Bridge.renameSession(id, trimmed);
      if (!ok) {
        Bridge.notify('Rename failed', 'error');
        return;
      }
      if (id === state.sessionId) state.sessionTitle = trimmed;
      await refreshSessionRail();
    } catch (e) {
      Bridge.notify('Rename failed: ' + (e && e.message || e), 'error');
    }
  }

  async function confirmDeleteSession(id, title) {
    // eslint-disable-next-line no-alert
    const ok = window.confirm(`Delete session "${title}"?\nThis cannot be undone.`);
    if (!ok) return;
    try {
      const deleted = await Bridge.deleteSession(id);
      if (!deleted) {
        Bridge.notify('Delete failed', 'error');
        return;
      }
      if (id === state.sessionId) {
        // Active session went away — clear the transcript cleanly.
        clearMessages();
        state.sessionId    = null;
        state.sessionTitle = null;
      }
      await refreshSessionRail();
    } catch (e) {
      Bridge.notify('Delete failed: ' + (e && e.message || e), 'error');
    }
  }

  // ================================================================= Rewind
  /** Replace a prior user message's body with an inline editor. On Save we
   *  truncate `state.messages` (and DOM) at this index and call `send()` so
   *  the stream starts fresh from the edited point. No server change — it's
   *  all client-side truncation + resend of a modified user turn. */
  function rewindToMessage(msgId) {
    if (state.activeStream) {
      Bridge.notify('Stop the current response before rewinding', 'info');
      return;
    }
    const idx = state.messages.findIndex(m => m.id === msgId);
    if (idx < 0) return;
    const msg = state.messages[idx];
    if (msg.role !== 'user' || !msg._node) return;
    const body = msg._node.querySelector('.msg-body');
    if (!body || body.dataset.editing === '1') return;

    const originalText = msg.content || '';
    body.dataset.editing = '1';
    msg._node.classList.add('is-editing');
    body.innerHTML = '';

    const editor = document.createElement('div');
    editor.className = 'msg-rewind-editor';
    const ta = document.createElement('textarea');
    ta.rows = Math.max(2, Math.min(12, originalText.split('\n').length));
    ta.value = originalText;
    editor.appendChild(ta);

    const actions = document.createElement('div');
    actions.className = 'msg-rewind-actions';
    actions.innerHTML =
      `<button type="button" class="btn btn-primary" data-act="save">Save &amp; regenerate</button>` +
      `<button type="button" class="btn btn-ghost" data-act="cancel">Cancel</button>`;
    editor.appendChild(actions);
    body.appendChild(editor);
    ta.focus();
    ta.setSelectionRange(ta.value.length, ta.value.length);

    const cancel = () => {
      body.dataset.editing = '';
      msg._node.classList.remove('is-editing');
      body.textContent = originalText;
    };
    const save = async () => {
      const edited = ta.value.trim();
      if (!edited) { cancel(); return; }
      if (edited === originalText) { cancel(); return; }
      // Truncate messages + DOM at this index (drop the user msg itself too —
      // `send()` will re-add it).
      const removed = state.messages.splice(idx);
      for (const m of removed) m._node?.remove();
      // Carry forward attachments the user had on the original turn.
      state.attachments = Array.isArray(msg.attachments) ? msg.attachments.map(a => ({ ...a })) : [];
      await send(edited);
    };

    actions.addEventListener('click', (e) => {
      const btn = e.target.closest('[data-act]');
      if (!btn) return;
      e.preventDefault();
      if (btn.dataset.act === 'save') save();
      else cancel();
    });
    ta.addEventListener('keydown', (e) => {
      if (e.key === 'Escape') { e.preventDefault(); cancel(); }
      else if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') { e.preventDefault(); save(); }
    });
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
          case 'set-allow-edits':
            // Legacy bridge message — older IDE builds send Boolean
            // payloads before the Ask/Edit/Agent dropdown existed.
            // Translate into the new `mode` enum and reflect in the
            // dropdown.
            state.mode = Boolean(payload) ? 'edit' : 'ask';
            if (el.modePicker) el.modePicker.value = state.mode;
            return;
          case 'set-mode':
            if (payload === 'ask' || payload === 'edit' || payload === 'agent') {
              state.mode = payload;
              if (el.modePicker) el.modePicker.value = state.mode;
            }
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
    setAllowEdits: (v) => {
      // Legacy shim kept so older IDE host code (pre-dropdown) can still
      // flip the mode. Maps true → "edit", false → "ask". Prefer setMode
      // for new callers.
      state.mode = Boolean(v) ? 'edit' : 'ask';
      if (el.modePicker) el.modePicker.value = state.mode;
    },
    setMode: (m) => {
      if (m === 'ask' || m === 'edit' || m === 'agent') {
        state.mode = m;
        if (el.modePicker) el.modePicker.value = state.mode;
      }
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
