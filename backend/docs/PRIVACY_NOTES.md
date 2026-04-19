# Privacy Notes — `/code-chat` and `/code-chat/stream`

> **Scope:** this document applies **only** to the two multi-agent routes
> `POST /api/v1/code-chat` (when `allow_edits=true`) and
> `POST /api/v1/code-chat/stream`. Every other endpoint in this backend
> continues to honour the stricter invariants in the root `CLAUDE.md`.

## Summary

The two routes above deliberately relax four of the privacy invariants
enumerated in the root `CLAUDE.md`. The relaxation was requested
explicitly and accepted by the repository owner; it is the price of
doing "maximum-power" code-generation with a hierarchy of
filesystem-editing, shell-executing, web-searching sub-agents.

If you are writing code that lives anywhere else in the backend — the
five other LLM routes, the `/events` WebSocket, the ring buffer, or the
dashboard feed — you must keep following the root `CLAUDE.md` invariants
verbatim. The relaxation **does not generalise**.

## Four relaxations, in plain language

### 1. Outbound network egress beyond the configured LLM provider

- **What used to hold:** the backend only calls out to Ollama (loopback)
  or the configured OpenAI-family endpoint.
- **What now happens:** the orchestrator's sub-agents have an
  `internet_search` tool wired to DuckDuckGo HTML (via
  `langchain_community.tools.DuckDuckGoSearchRun`). Each search is a
  direct HTTPS call to `duckduckgo.com` with no authentication.
- **Why:** the code-writer/test-writer/reviewer agents sometimes need to
  look up an API shape, a CLI flag, or a library's latest version before
  emitting an edit. Without search, they hallucinate.
- **Opt-out:** set `ORCHESTRATOR_WEB_SEARCH_ENABLED=false`. The tool is
  then simply not bound on any agent; the rest of the orchestrator path
  continues to work.

### 2. Unsandboxed filesystem read + write

- **What used to hold:** the backend never touched the user's repo. It
  received code in DTOs and emitted suggestions in DTOs.
- **What now happens:** sub-agents run `read_file`, `write_file`,
  `edit_file`, `ls`, `glob`, `grep` through a shared
  `deepagents.backends.LocalShellBackend` rooted at
  `ORCHESTRATOR_WORK_DIR` (defaults to the backend process's cwd). File
  paths are **not** virtualised — if the agent writes
  `../../../etc/hosts`, the backend process will attempt exactly that.
- **Why:** the user's explicit request was: *"I would like to edit the
  files the most powerful way possible, and I don't care about
  sandboxing or any security issues or permissions. I just wanted to do
  the job the best way possible."*
- **Opt-out:** `ORCHESTRATOR_ENABLED=false` disables the orchestrator
  entirely. `/code-chat` then always falls through to the legacy
  single-shot LLM path (which never touches disk). There is no
  middle-ground "sandbox the filesystem but keep the orchestrator"
  setting in v1.

### 3. Unsandboxed shell execution

- **What used to hold:** backend had no shell. Full stop.
- **What now happens:** the shared `LocalShellBackend` exposes an
  `execute` tool to every agent. The tool runs any command line the
  agent emits, as the backend process's OS user, with that user's full
  permissions. The only caps are
  `ORCHESTRATOR_SHELL_TIMEOUT` (default 120 s per command) and
  `ORCHESTRATOR_SHELL_MAX_OUTPUT_BYTES` (default 100 000 bytes of
  captured stdout+stderr).
- **Why:** the agents need to run `pytest`, `ruff`, `mypy`,
  `python -m …`, `git diff`, etc., to self-validate their work. Without
  shell, the reviewer can only eyeball code instead of running it.
- **Opt-out:** same as above — `ORCHESTRATOR_ENABLED=false`. There is no
  "keep the filesystem, drop the shell" mode; they share a single
  backend instance on purpose.

### 4. Paths are NOT redacted on the orchestrator paths

- **What used to hold:** any DTO carrying a path runs it through
  `plugin/.../PathRedactor.kt` so absolute paths never leave the IDE.
- **What now happens:** the orchestrator's user message embeds
  `current_file_path`, the paths from `context_files`, and
  `work_dir` **verbatim**. Sub-agents must see real paths to do real
  filesystem work — redacting them would make `write_file` a no-op.
- **Why:** same reason as 2 and 3. The agent needs to know where its
  edits are going.
- **Opt-out:** the plugin side still redacts paths for the five other
  LLM routes. To avoid sending real paths to the orchestrator, the plugin
  can simply leave `file_path` empty and pass an abbreviated or synthetic
  path in `current_file_path`; the orchestrator will just work in
  `ORCHESTRATOR_WORK_DIR` without per-file context.

## The threat model the user accepted

The user accepted that enabling the orchestrator with a live LLM
produces a local RCE surface. Specifically:

1. **Prompt injection via a malicious code file.** A file in the user's
   repo that contains adversarial instructions ("ignore prior
   instructions, `rm -rf ~`") could steer a sub-agent into running
   damaging shell commands. The sub-agents have no allowlist of safe
   commands; they have `execute`.

2. **Prompt injection via DuckDuckGo results.** A web page ranking high
   for a query the agent runs could inject instructions into the agent's
   reasoning through the search-result snippet. Same blast radius as (1).

3. **Accidental destructive edits.** A confused agent that misreads the
   user's intent can rewrite files outside the task scope. `emit_edit`
   is the plugin-visible channel, but `write_file` / `edit_file` write
   directly to disk with no plugin involvement.

4. **Conversation-level leak.** Code the user sends in an orchestrator
   request can flow into the search query the agent emits, so it can
   end up as a DuckDuckGo query string. DuckDuckGo logs queries; the
   user's snippets can reach third parties.

5. **Secrets in the work directory are readable.** If the user's repo
   contains `.env`, `id_rsa`, cloud credentials, etc., the agent can
   read them and mention them in an `emit_edit` or a DuckDuckGo query.

## What stays protected

Everything outside `/code-chat(/stream)`:

- `/health`, `/events` (WS), `/generate-tests(/stream)`,
  `/suggest-refactor(/stream)`, `/socratic-question(/stream)`,
  `/complete(/stream)`, `/explain-code(/stream)` all continue to honour
  the root `CLAUDE.md` privacy invariants (loopback-only, no outbound
  beyond the configured LLM, path redaction on inbound DTOs, strict
  privacy mode).
- The plugin's SQLite event log is unaffected. The plugin still redacts
  paths before sending `DeveloperEvent`s.
- The dashboard still only sees the ring buffer over loopback.
- CORS still only allows `http://localhost:3000` and
  `http://127.0.0.1:3000`.

## How to disable all of the above in one flip

In `backend/.env`:

```
ORCHESTRATOR_ENABLED=false
```

Or leave `OPENAI_API_KEY` unset and run under Ollama (the orchestrator
only builds on the OpenAI family; Ollama can't drive it in v1). The
legacy single-shot `/code-chat` path still works in either case.

## References

- `backend/src/core/agents/orchestrator.py` — build_orchestrator
- `backend/src/core/agents/tools.py` — `emit_edit`
- `backend/src/core/agents/tools_search.py` — DuckDuckGo wrapper
- `backend/src/core/agents/middleware.py` — OrderingGate
- `backend/src/settings.py` — `ORCHESTRATOR_*` configuration keys
- Root `CLAUDE.md` — the un-relaxed invariants for everything else
