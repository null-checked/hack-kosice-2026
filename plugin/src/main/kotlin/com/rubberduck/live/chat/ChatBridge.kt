package com.rubberduck.live.chat

import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.codeStyle.NameUtil
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import com.rubberduck.live.detector.EventEmitter
import com.rubberduck.live.model.EventType
import com.rubberduck.live.services.CodeIndexer
import com.rubberduck.live.services.SkillsManager
import com.rubberduck.live.settings.RubberDuckSettings
import com.rubberduck.live.util.DiffApplier
import com.rubberduck.live.util.PathRedactor
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.awt.datatransfer.StringSelection
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Owns the `window.rubberduck.*` bridge exposed to the JCEF-hosted
 * chat web UI. Each method is implemented as a [JBCefJSQuery] that
 * accepts a single JSON-encoded string payload and returns a JSON
 * string (or `null` / an error code).
 *
 * The web UI's `chat-webui/app.js` calls these as Promises:
 *
 * ```js
 *   const r = await window.rubberduck.getSelection();
 * ```
 *
 * The bootstrap script (see [buildBootstrapScript]) is injected at
 * `onLoadStart` on every navigation so that `window.rubberduck` exists
 * before the page's `app.js` runs.
 */
class ChatBridge(
    private val project: Project,
    private val browser: JBCefBrowser,
    /** Callback used for host→UI one-shot pushes. */
    @Suppress("unused") private val executeJs: (String) -> Unit,
) : Disposable {

    private val log = Logger.getInstance(ChatBridge::class.java)
    private val queries = mutableListOf<JBCefJSQuery>()
    private val methodSnippets = StringBuilder()

    // ---------------------------------------------------------------- Setup

    init {
        register("getConfig") { getConfig() }
        register("getSelection") { getSelection() }
        register("getOpenFiles") { getOpenFiles() }
        register("getDiagnostics") { getDiagnostics() }
        register("readFile") { payload -> readFile(unquoteString(payload)) }
        register("pickFile") { pickFile() }
        register("insertAtCursor") { payload -> insertAtCursor(unquoteString(payload)) }
        register("replaceSelection") { payload -> replaceSelection(unquoteString(payload)) }
        register("copyToClipboard") { payload -> copyToClipboard(unquoteString(payload)) }
        register("listSkills") { listSkills() }
        register("getSkillBody") { payload -> getSkillBody(unquoteString(payload)) }
        register("listModels") { listModels() }
        register("saveHistory") { payload -> saveHistory(payload) }
        register("log") { payload -> logMessage(unquoteString(payload)) }
        register("notify") { payload -> notifyUser(payload) }
        register("onReady") { onReady() }
        register("setModel") { payload -> setModel(unquoteString(payload)) }
        register("applyEdit") { payload -> applyEdit(payload) }
        register("rejectEdit") { payload -> rejectEdit(payload) }
        register("emitIntervention") { payload -> emitIntervention(payload) }
        register("searchFiles") { payload -> searchFiles(payload) }
        register("searchSemantic") { payload -> searchSemantic(payload) }
        register("listSessions") { listSessions() }
        register("loadSession") { payload -> loadSession(payload) }
        register("deleteSession") { payload -> deleteSession(payload) }
        register("renameSession") { payload -> renameSession(payload) }
    }

    /** JS source injected on every page load to expose `window.rubberduck.*`. */
    fun buildBootstrapScript(): String {
        return """
            (function() {
              window.rubberduck = window.rubberduck || {};
              ${methodSnippets}
            })();
        """.trimIndent()
    }

    // ---------------------------------------------------------------- Helpers

    private fun register(name: String, handler: (String) -> JsonElement) {
        val query = JBCefJSQuery.create(browser as JBCefBrowserBase)
        query.addHandler { raw ->
            try {
                val payload = raw ?: ""
                val result = handler(payload)
                val encoded = JSON.encodeToString(JsonElement.serializer(), result)
                JBCefJSQuery.Response(encoded)
            } catch (t: Throwable) {
                log.warn("Bridge.$name threw: ${t.message}", t)
                JBCefJSQuery.Response(null, -1, t.message ?: t::class.java.simpleName)
            }
        }
        queries += query
        // Browser-side shim: wrap the JBCefJSQuery injector in a Promise so
        // the web UI can `await` it like any normal async function.
        val inject = query.inject(
            "JSON.stringify(arg === undefined ? null : arg)",
            "function(r){ try { resolve(r ? JSON.parse(r) : null); } catch(e) { resolve(r); } }",
            "function(ec,msg){ reject(new Error(msg || ('bridge error ' + ec))); }",
        )
        methodSnippets.append(
            """
            window.rubberduck.${name} = function(arg) {
              return new Promise(function(resolve, reject) {
                $inject
              });
            };
            """.trimIndent()
        ).append('\n')
    }

    /**
     * The web UI wraps every outgoing payload in `JSON.stringify(arg)` which,
     * for a plain string argument, yields `"foo"`. Our handlers mostly want
     * the raw string, so unwrap the single-value JSON envelope.
     */
    private fun unquoteString(payload: String): String {
        if (payload.isEmpty() || payload == "null") return ""
        return try {
            when (val el = JSON.parseToJsonElement(payload)) {
                is JsonPrimitive -> if (el.isString) el.content else el.content
                else -> payload
            }
        } catch (_: Throwable) {
            payload
        }
    }

    // ----------------------------------------------------------------- APIs

    private fun getConfig(): JsonElement {
        val s = RubberDuckSettings.getInstance()
        val st = s.state
        return buildJsonObject {
            put("backendBase", s.backendApiBase)
            put("theme", currentTheme())
            put("defaultModel", st.chatModel)
            put("chatModel", st.chatModel)
            put("chatMaxHistoryTokens", st.chatMaxHistoryTokens)
            put("chatAttachOpenFiles", st.chatAttachOpenFiles)
            put("chatAttachDiagnostics", st.chatAttachDiagnostics)
            put("chatAvailableModels", buildJsonArray {
                st.chatAvailableModels.forEach { add(JsonPrimitive(it)) }
            })
        }
    }

    private fun getSelection(): JsonElement {
        val editor = activeEditor() ?: return JsonNull
        return ReadAction.compute<JsonElement, RuntimeException> {
            val sel = editor.selectionModel
            val text = sel.selectedText
            if (text.isNullOrEmpty()) return@compute JsonNull
            val vf = FileDocumentManager.getInstance().getFile(editor.document)
            val path = vf?.path
            val language = vf?.extension?.lowercase() ?: "text"
            buildJsonObject {
                put("filePath", path)
                put("language", language)
                put("code", text)
            }
        }
    }

    private fun getOpenFiles(): JsonElement {
        return ReadAction.compute<JsonElement, RuntimeException> {
            val base = project.basePath?.let { Paths.get(it) }
            val files = FileEditorManager.getInstance(project).openFiles
            buildJsonArray {
                for (f in files) {
                    val p = f.path
                    val rel = if (base != null) {
                        runCatching { base.relativize(Paths.get(p)).toString() }.getOrDefault(p)
                    } else p
                    add(JsonPrimitive(rel))
                }
            }
        }
    }

    private fun getDiagnostics(): JsonElement {
        val editor = activeEditor() ?: return JsonArray(emptyList())
        return ReadAction.compute<JsonElement, RuntimeException> {
            val doc = editor.document
            val vf = FileDocumentManager.getInstance().getFile(doc)
            val filePath = vf?.path ?: ""
            val highlights = runCatching {
                DaemonCodeAnalyzerImpl.getHighlights(doc, null, project)
            }.getOrDefault(emptyList())
            buildJsonArray {
                for (h in highlights.take(50)) {
                    val lineNum = if (h.startOffset in 0..doc.textLength) {
                        doc.getLineNumber(h.startOffset) + 1
                    } else 0
                    val severity = (h.severity?.name ?: "INFO").lowercase()
                    val message = h.description ?: h.toolTip ?: ""
                    if (message.isBlank()) continue
                    add(buildJsonObject {
                        put("filePath", filePath)
                        put("line", lineNum)
                        put("severity", severity)
                        put("message", message)
                    })
                }
            }
        }
    }

    private fun readFile(pathStr: String): JsonElement {
        if (pathStr.isBlank()) return JsonNull
        val base = project.basePath?.let { Paths.get(it).toAbsolutePath().normalize() }
            ?: return JsonNull
        val candidate = runCatching {
            val p = Paths.get(pathStr)
            if (p.isAbsolute) p.toAbsolutePath().normalize()
            else base.resolve(p).toAbsolutePath().normalize()
        }.getOrElse { return JsonNull }
        // Path-traversal guard: must live under project base.
        if (!candidate.startsWith(base)) {
            log.warn("Rejected bridge readFile outside project: $candidate")
            return JsonNull
        }
        if (!Files.exists(candidate) || !Files.isRegularFile(candidate)) return JsonNull
        return try {
            JsonPrimitive(Files.readString(candidate, StandardCharsets.UTF_8))
        } catch (_: IOException) {
            JsonNull
        }
    }

    private fun pickFile(): JsonElement {
        val descriptor = FileChooserDescriptor(true, false, false, false, false, false)
        var result: JsonElement = JsonNull
        ApplicationManager.getApplication().invokeAndWait {
            val vf = FileChooser.chooseFile(descriptor, project, null) ?: return@invokeAndWait
            val base = project.basePath?.let { Paths.get(it) }
            val rel = if (base != null) {
                runCatching { base.relativize(Paths.get(vf.path)).toString() }.getOrDefault(vf.path)
            } else vf.path
            val content = runCatching { VfsUtil.loadText(vf) }.getOrDefault("")
            val language = runCatching {
                FileTypeManager.getInstance().getFileTypeByFile(vf).name.lowercase()
            }.getOrNull()?.takeIf { it.isNotBlank() }
                ?: vf.extension?.lowercase()
                ?: "text"
            result = buildJsonObject {
                put("filePath", rel)
                put("content", content)
                put("language", language)
            }
        }
        return result
    }

    private fun setModel(model: String): JsonElement {
        if (model.isBlank()) return JsonPrimitive(false)
        val s = RubberDuckSettings.getInstance().state
        s.chatModel = model
        // If the user picks a model that isn't already in the list,
        // append it so the combobox round-trips next time Settings opens.
        if (s.chatAvailableModels.none { it.equals(model, ignoreCase = true) }) {
            s.chatAvailableModels.add(model)
        }
        return JsonPrimitive(true)
    }

    private fun insertAtCursor(text: String): JsonElement {
        val editor = activeEditor() ?: return JsonPrimitive(false)
        WriteCommandAction.runWriteCommandAction(project) {
            val caret = editor.caretModel.offset
            editor.document.insertString(caret, text)
            editor.caretModel.moveToOffset(caret + text.length)
        }
        return JsonPrimitive(true)
    }

    private fun replaceSelection(text: String): JsonElement {
        val editor = activeEditor() ?: return JsonPrimitive(false)
        WriteCommandAction.runWriteCommandAction(project) {
            val sel = editor.selectionModel
            if (sel.hasSelection()) {
                editor.document.replaceString(sel.selectionStart, sel.selectionEnd, text)
            } else {
                editor.document.insertString(editor.caretModel.offset, text)
            }
        }
        return JsonPrimitive(true)
    }

    private fun copyToClipboard(text: String): JsonElement {
        CopyPasteManager.getInstance().setContents(StringSelection(text))
        return JsonPrimitive(true)
    }

    private fun listSkills(): JsonElement {
        // Only enabled skills (per RubberDuckSettings.enabledSkills) are
        // exposed to the web UI so the @ popup matches the Skills config.
        val skills = runCatching {
            SkillsManager.getInstance(project).getEnabledSkills()
        }.getOrDefault(emptyList())
        return buildJsonArray {
            for (s in skills) {
                add(buildJsonObject {
                    put("name", s.name)
                    put("description", s.description)
                    put("trigger", s.trigger)
                })
            }
        }
    }

    private fun getSkillBody(name: String): JsonElement {
        if (name.isBlank()) return JsonNull
        val mgr = runCatching { SkillsManager.getInstance(project) }.getOrNull()
            ?: return JsonNull
        // Match by frontmatter `name` first, then by `id` (filename stem) as
        // a fallback so `@skill:code-explainer` also resolves.
        val skill = mgr.getSkillByName(name)
            ?: mgr.getAllSkills().find { it.id.equals(name, ignoreCase = true) }
            ?: return JsonNull
        // Strip YAML frontmatter — return everything after the second `---`.
        val body = skill.content
        val m = Regex("(?s)^---\\s*\\n.*?\\n---\\s*\\n?(.*)$").find(body)
        return JsonPrimitive(m?.groupValues?.get(1) ?: body)
    }

    private fun listModels(): JsonElement {
        val s = RubberDuckSettings.getInstance().state
        val models = if (s.chatAvailableModels.isNotEmpty()) s.chatAvailableModels
        else mutableListOf(
            "gpt-4o-mini", "gpt-4o",
            "claude-3-5-sonnet-20241022",
            "llama3.1:8b", "qwen2.5-coder:7b",
        )
        return buildJsonArray { models.forEach { add(JsonPrimitive(it)) } }
    }

    /**
     * Persist the current conversation. The web UI sends a payload of shape:
     *
     * ```json
     * {
     *   "id":       "<uuid-or-null>",
     *   "title":    "<optional>",
     *   "model":    "<optional>",
     *   "messages": [ ... ]
     * }
     * ```
     *
     * If `id` is null / blank, [SessionStore] generates a fresh UUID and
     * the response echoes it back — the web UI holds onto it for
     * subsequent saves in the same session.
     *
     * Returns `{ok, id, title, created_at, updated_at}` on success, or
     * `{ok:false, error}` on failure.
     */
    private fun saveHistory(rawPayload: String): JsonElement {
        val obj = parsePayloadObject(rawPayload) ?: return buildJsonObject {
            put("ok", false)
            put("error", "invalid-payload")
        }
        return SessionStore.getInstance(project).save(obj)
    }

    /** Return session headers — id, title, created_at, updated_at, message_count. */
    private fun listSessions(): JsonElement = SessionStore.getInstance(project).list()

    /** Return the full session JSON for `id`, or `null` when missing / invalid. */
    private fun loadSession(rawPayload: String): JsonElement {
        val id = extractId(rawPayload)
        if (id.isBlank()) return JsonNull
        return SessionStore.getInstance(project).load(id) ?: JsonNull
    }

    /** Delete a session file on disk. Returns `true` when removed. */
    private fun deleteSession(rawPayload: String): JsonElement {
        val id = extractId(rawPayload)
        if (id.isBlank()) return JsonPrimitive(false)
        return JsonPrimitive(SessionStore.getInstance(project).delete(id))
    }

    /** Update a session's title. Payload: `{id, title}`. */
    private fun renameSession(rawPayload: String): JsonElement {
        val obj = parsePayloadObject(rawPayload) ?: return JsonPrimitive(false)
        val id = (obj["id"] as? JsonPrimitive)?.content.orEmpty()
        val title = (obj["title"] as? JsonPrimitive)?.content.orEmpty()
        if (id.isBlank() || title.isBlank()) return JsonPrimitive(false)
        return JsonPrimitive(SessionStore.getInstance(project).rename(id, title))
    }

    /**
     * Pull a session id out of a payload that may have either shape:
     *   - `{"id": "<uuid>"}` — the preferred form
     *   - `"<uuid>"`         — a bare JSON string
     * Returns an empty string when neither shape matches.
     */
    private fun extractId(rawPayload: String): String {
        val obj = parsePayloadObject(rawPayload)
        if (obj != null) {
            return (obj["id"] as? JsonPrimitive)?.content.orEmpty()
        }
        return unquoteString(rawPayload)
    }

    private fun logMessage(msg: String): JsonElement {
        log.info("[chat-webui] $msg")
        return JsonPrimitive(true)
    }

    private fun notifyUser(rawPayload: String): JsonElement {
        // Web UI calls `notify(msg, kind)` which the JS-side wraps as
        // `JSON.stringify(firstArg)` — so `kind` won't reach us through
        // arg serialization.  We accept either a bare string or a small
        // JSON object for forward-compatibility.
        var message = rawPayload
        var kind = "info"
        try {
            val el = JSON.parseToJsonElement(rawPayload)
            when (el) {
                is JsonPrimitive -> message = el.content
                is JsonObject -> {
                    message = (el["message"] as? JsonPrimitive)?.content ?: message
                    kind = (el["kind"] as? JsonPrimitive)?.content ?: kind
                }
                else -> {}
            }
        } catch (_: Throwable) {
            // leave message as the raw payload
        }
        val type = when (kind.lowercase()) {
            "error" -> NotificationType.ERROR
            "warn", "warning" -> NotificationType.WARNING
            else -> NotificationType.INFORMATION
        }
        Notifications.Bus.notify(
            Notification("RubberDuck Live", "RubberDuck Chat", message, type),
            project,
        )
        return JsonPrimitive(true)
    }

    private fun onReady(): JsonElement {
        log.info("[chat-webui] ready")
        return JsonPrimitive(true)
    }

    /** Lazily-constructed resolver that round-trips redacted paths to VirtualFiles. */
    private val fileResolver: SessionFileResolver by lazy { SessionFileResolver(project) }

    /**
     * Preview a proposed code edit in IntelliJ's native diff dialog and apply
     * it on Accept. Returns `{applied: bool, edit_id: "..."}`.
     *
     * Expected payload shape (the JS-side stringifies the single arg):
     * ```json
     * {
     *   "edit_id": "uuid",
     *   "file_path": "src/foo.py",
     *   "original_code": "…",
     *   "proposed_code": "…",
     *   "explanation": "Rename app to application"
     * }
     * ```
     *
     * Events:
     *  - Caller already emitted `INTERVENTION_SHOWN` when the card rendered.
     *  - We emit `INTERVENTION_ACCEPTED` on Apply, or `INTERVENTION_DISMISSED`
     *    on Cancel — matching the contract every other action uses so the
     *    dashboard's acceptance-rate metric stays coherent.
     */
    private fun applyEdit(rawPayload: String): JsonElement {
        val obj = parsePayloadObject(rawPayload) ?: return buildJsonObject {
            put("applied", false)
            put("error", "invalid-payload")
        }
        val editId = (obj["edit_id"] as? JsonPrimitive)?.content ?: ""
        val filePath = (obj["file_path"] as? JsonPrimitive)?.content ?: ""
        val original = (obj["original_code"] as? JsonPrimitive)?.content ?: ""
        val proposed = (obj["proposed_code"] as? JsonPrimitive)?.content ?: ""
        val explanation = (obj["explanation"] as? JsonPrimitive)?.content ?: ""

        if (filePath.isBlank() || proposed.isBlank()) {
            return buildJsonObject {
                put("applied", false)
                put("error", "missing-fields")
            }
        }

        val vf = fileResolver.resolve(filePath) ?: return buildJsonObject {
            put("applied", false)
            put("error", "file-not-found")
        }
        val redacted = PathRedactor.redact(project, vf.path)

        // Capture the result from the EDT-scoped dialog.
        var applied = false
        ApplicationManager.getApplication().invokeAndWait {
            val doc = FileDocumentManager.getInstance().getDocument(vf) ?: return@invokeAndWait
            // If the document has drifted from the LLM's `original_code`, we
            // still go through the dialog — the diff viewer shows the user
            // exactly what's about to change so they can catch drift.
            val currentText = doc.text
            val leftText = if (original.isNotBlank()) original else currentText
            val title = if (explanation.isNotBlank()) {
                "RubberDuck chat edit — ${vf.name}\n$explanation"
            } else {
                "RubberDuck chat edit — ${vf.name}"
            }
            val result = DiffApplier.previewAndApply(
                project = project,
                document = doc,
                title = title,
                original = leftText,
                proposed = proposed,
                windowTitle = "Apply chat edit?",
                commandName = "RubberDuck: Apply chat edit",
            )
            applied = result.applied
        }

        val evtType = if (applied) EventType.INTERVENTION_ACCEPTED else EventType.INTERVENTION_DISMISSED
        EventEmitter.emit(
            evtType,
            file = redacted,
            payload = EventEmitter.json(
                "intervention_id" to editId,
                "kind" to "chat_edit",
            ),
        )
        return buildJsonObject {
            put("applied", applied)
            put("edit_id", editId)
        }
    }

    /**
     * User clicked Reject on an edit card. Emits `INTERVENTION_DISMISSED` so
     * the acceptance-rate metric in the dashboard stays in `[0,1]`.
     */
    private fun rejectEdit(rawPayload: String): JsonElement {
        val obj = parsePayloadObject(rawPayload) ?: return JsonPrimitive(false)
        val editId = (obj["edit_id"] as? JsonPrimitive)?.content ?: ""
        val filePath = (obj["file_path"] as? JsonPrimitive)?.content
        EventEmitter.emit(
            EventType.INTERVENTION_DISMISSED,
            file = filePath,
            payload = EventEmitter.json(
                "intervention_id" to editId,
                "kind" to "chat_edit",
            ),
        )
        return JsonPrimitive(true)
    }

    /**
     * Generic intervention-emitter used by the JS side for per-card lifecycle
     * pings (e.g. `INTERVENTION_SHOWN` on card render). Payload:
     *
     * ```json
     * { "kind": "chat_edit",
     *   "type": "INTERVENTION_SHOWN",
     *   "edit_id": "...",
     *   "file_path": "..."
     * }
     * ```
     */
    private fun emitIntervention(rawPayload: String): JsonElement {
        val obj = parsePayloadObject(rawPayload) ?: return JsonPrimitive(false)
        val typeName = (obj["type"] as? JsonPrimitive)?.content ?: "INTERVENTION_SHOWN"
        val kind = (obj["kind"] as? JsonPrimitive)?.content ?: "chat_edit"
        val editId = (obj["edit_id"] as? JsonPrimitive)?.content ?: ""
        val filePath = (obj["file_path"] as? JsonPrimitive)?.content
        val evt = runCatching { EventType.valueOf(typeName) }.getOrNull()
            ?: EventType.INTERVENTION_SHOWN
        EventEmitter.emit(
            evt,
            file = filePath,
            payload = EventEmitter.json(
                "intervention_id" to editId,
                "kind" to kind,
            ),
        )
        return JsonPrimitive(true)
    }

    /**
     * Fuzzy-search files across the whole project. Returns a ranked list of
     * `{path, score}` objects; the web-ui's `@` autocomplete renders them.
     *
     * Ported from `origin/edits-in-kotlin`'s `AtMentionController.collectCandidates`:
     *   1. Walk [FilenameIndex.getAllFilenames] → [FilenameIndex.getVirtualFilesByName]
     *      — the idiomatic way to avoid iterating the full VFS.
     *   2. Filter out directories, non-content files, binary files, and files
     *      over 1 MB — mentioning those would waste tokens or crash the
     *      LLM context.
     *   3. Score with [NameUtil.buildMatcher] (IntelliJ's own fuzzy matcher)
     *      on both the full redacted path and the bare file name; pick the
     *      higher score. Paths that don't match either substring or the
     *      fuzzy matcher are dropped entirely.
     *   4. Sort by score desc, then path length asc, then alphabetical, and
     *      cap at `limit` (default 30) so a huge monorepo doesn't overwhelm
     *      the JCEF IPC channel.
     *
     * Privacy: respects [com.rubberduck.live.settings.RubberDuckSettings.State.privacyMode] —
     * returns an empty array when strict privacy is on so the user never
     * accidentally ships file names off-device via the UI.
     */
    private fun searchFiles(rawPayload: String): JsonElement {
        if (RubberDuckSettings.getInstance().state.privacyMode) {
            return JsonArray(emptyList())
        }
        val obj = parsePayloadObject(rawPayload) ?: return JsonArray(emptyList())
        val query = (obj["query"] as? JsonPrimitive)?.content?.trim().orEmpty()
        val limit = ((obj["limit"] as? JsonPrimitive)?.content?.toIntOrNull() ?: MAX_FILE_MATCHES)
            .coerceIn(1, 100)

        data class Hit(val path: String, val score: Int)

        return ReadAction.compute<JsonElement, RuntimeException> {
            val scope = GlobalSearchScope.projectScope(project)
            val fileIndex = ProjectFileIndex.getInstance(project)
            val matcher = if (query.isNotBlank())
                NameUtil.buildMatcher("*$query").build()
            else null
            val base = project.basePath?.let { Paths.get(it) }
            val hits = ArrayList<Hit>()

            for (name in FilenameIndex.getAllFilenames(project)) {
                val vfiles = FilenameIndex.getVirtualFilesByName(name, scope)
                for (vf in vfiles) {
                    if (!vf.isValid || vf.isDirectory) continue
                    if (!fileIndex.isInContent(vf)) continue
                    if (vf.fileType.isBinary) continue
                    if (vf.length > 1_000_000) continue
                    val rel = if (base != null) {
                        runCatching { base.relativize(Paths.get(vf.path)).toString().replace('\\', '/') }
                            .getOrDefault(vf.path)
                    } else vf.path
                    val redacted = PathRedactor.redact(project, vf.path) ?: rel
                    val score = if (matcher == null) 0
                    else {
                        val s = maxOf(matcher.matchingDegree(redacted), matcher.matchingDegree(name))
                        if (s <= 0 && !redacted.contains(query, ignoreCase = true)) continue
                        s
                    }
                    hits.add(Hit(redacted, score))
                }
            }
            hits.sortWith(
                compareByDescending<Hit> { it.score }
                    .thenBy { it.path.length }
                    .thenBy { it.path },
            )
            val topN = hits.take(limit)
            buildJsonArray {
                for (h in topN) {
                    add(buildJsonObject {
                        put("path", h.path)
                        put("score", h.score)
                    })
                }
            }
        }
    }

    /**
     * Semantic search across the project's BM25 index (Phase 2.1). The
     * index is built and refreshed by [CodeIndexer]; this bridge method
     * is a thin adapter that forwards `{query, k}` to `/index/search`
     * and returns `[{path, score, snippet}, ...]`.
     *
     * Privacy: same gate as [searchFiles] — strict privacy returns `[]`.
     *
     * Web-ui semantics: `@?<query>` triggers this branch instead of
     * [searchFiles]. The popup renders a "semantic" badge so the user
     * can tell the ranking is topic-based rather than filename-based.
     */
    private fun searchSemantic(rawPayload: String): JsonElement {
        if (RubberDuckSettings.getInstance().state.privacyMode) {
            return JsonArray(emptyList())
        }
        val obj = parsePayloadObject(rawPayload) ?: return JsonArray(emptyList())
        val query = (obj["query"] as? JsonPrimitive)?.content?.trim().orEmpty()
        if (query.isEmpty()) return JsonArray(emptyList())
        val k = ((obj["k"] as? JsonPrimitive)?.content?.toIntOrNull()
            ?: (obj["limit"] as? JsonPrimitive)?.content?.toIntOrNull()
            ?: 5)
            .coerceIn(1, 50)
        val hits = runCatching { CodeIndexer.getInstance(project).search(query, k) }
            .getOrElse { emptyList() }
        return buildJsonArray {
            for (h in hits) {
                add(buildJsonObject {
                    put("path", h.path)
                    put("score", h.score)
                    put("snippet", h.snippet)
                })
            }
        }
    }

    /**
     * Parse a bridge payload that may arrive either as a raw JSON object
     * (unusual) or JSON-stringified (the default — every JS-side call does
     * `JSON.stringify(arg)`). Returns `null` if the payload can't be shaped
     * into a JsonObject.
     */
    private fun parsePayloadObject(rawPayload: String): JsonObject? {
        if (rawPayload.isBlank() || rawPayload == "null") return null
        return try {
            when (val first = JSON.parseToJsonElement(rawPayload)) {
                is JsonObject -> first
                is JsonPrimitive -> {
                    // Web UI may have double-stringified: `"{...}"` → parse again.
                    if (first.isString) {
                        val inner = JSON.parseToJsonElement(first.content)
                        if (inner is JsonObject) inner else null
                    } else null
                }
                else -> null
            }
        } catch (_: Throwable) {
            null
        }
    }

    // --------------------------------------------------------- Utilities

    private fun activeEditor(): Editor? =
        FileEditorManager.getInstance(project).selectedTextEditor

    private fun currentTheme(): String = if (isDarkTheme()) "dark" else "light"

    override fun dispose() {
        queries.forEach { runCatching { Disposer.dispose(it) } }
        queries.clear()
    }

    companion object {
        private val JSON = Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
            explicitNulls = false
        }

        /**
         * Default cap on file-search results returned to the web UI. Enough
         * to fill a ranked popup without pushing tens of thousands of paths
         * through the JCEF IPC channel on huge monorepos.
         */
        private const val MAX_FILE_MATCHES = 30

        /** Small indirection so the theme decision lives in one place. */
        fun isDarkTheme(): Boolean {
            return runCatching { !com.intellij.ui.JBColor.isBright() }.getOrDefault(true)
        }
    }
}
