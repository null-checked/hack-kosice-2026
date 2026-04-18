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
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import com.rubberduck.live.services.SkillsManager
import com.rubberduck.live.settings.RubberDuckSettings
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
            val vf = FileChooser.chooseFile(descriptor, project, null)
            if (vf != null) {
                val base = project.basePath?.let { Paths.get(it) }
                val rel = if (base != null) {
                    runCatching { base.relativize(Paths.get(vf.path)).toString() }.getOrDefault(vf.path)
                } else vf.path
                result = JsonPrimitive(rel)
            }
        }
        return result
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
        val skills = runCatching {
            SkillsManager.getInstance(project).getAllSkills()
        }.getOrDefault(emptyList())
        return buildJsonArray {
            for (s in skills) {
                // The UI's slash-menu only needs the string name; keep the
                // array-of-strings shape the web UI already expects.
                add(JsonPrimitive(s.name))
            }
        }
    }

    private fun getSkillBody(name: String): JsonElement {
        if (name.isBlank()) return JsonNull
        val skill = runCatching {
            SkillsManager.getInstance(project).getSkillByName(name)
        }.getOrNull() ?: return JsonNull
        // Strip YAML frontmatter — return everything after the second `---`.
        val body = skill.content
        val m = Regex("(?s)^---\\s*\\n.*?\\n---\\s*\\n?(.*)$").find(body)
        return JsonPrimitive(m?.groupValues?.get(1) ?: body)
    }

    private fun listModels(): JsonElement {
        val s = RubberDuckSettings.getInstance().state
        val models = if (s.chatAvailableModels.isNotEmpty()) s.chatAvailableModels
        else mutableListOf("gpt-4o-mini", "gpt-4o", "claude-sonnet-4", "ollama/llama3.1")
        return buildJsonArray { models.forEach { add(JsonPrimitive(it)) } }
    }

    private fun saveHistory(payload: String): JsonElement {
        val base = project.basePath ?: return JsonPrimitive(false)
        return try {
            val dir = Paths.get(base, ".rubberduck")
            Files.createDirectories(dir)
            val file = dir.resolve("chat-history.jsonl")
            // Append one JSON line per save; good enough for MVP.
            val line = payload.replace("\n", " ") + "\n"
            Files.writeString(
                file,
                line,
                StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND,
            )
            JsonPrimitive(true)
        } catch (t: Throwable) {
            log.warn("saveHistory failed: ${t.message}")
            JsonPrimitive(false)
        }
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

        /** Small indirection so the theme decision lives in one place. */
        fun isDarkTheme(): Boolean {
            return runCatching {
                com.intellij.util.ui.UIUtil.isUnderDarcula()
            }.getOrElse {
                !com.intellij.ui.JBColor.isBright()
            }
        }
    }
}
