package com.rubberduck.live.chat

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.ui.jcef.JBCefBrowser
import com.rubberduck.live.chat.bridge.ChatBridgeDispatcher
import com.rubberduck.live.chat.bridge.ChatHandlerContext
import com.rubberduck.live.chat.bridge.handlers.ClipboardHandler
import com.rubberduck.live.chat.bridge.handlers.ConfigHandler
import com.rubberduck.live.chat.bridge.handlers.DiagnosticsHandler
import com.rubberduck.live.chat.bridge.handlers.EditHandler
import com.rubberduck.live.chat.bridge.handlers.EmitInterventionHandler
import com.rubberduck.live.chat.bridge.handlers.FilePickHandler
import com.rubberduck.live.chat.bridge.handlers.FileReadHandler
import com.rubberduck.live.chat.bridge.handlers.FileSaveHandler
import com.rubberduck.live.chat.bridge.handlers.HistoryExportMarkdownHandler
import com.rubberduck.live.chat.bridge.handlers.HistoryHandler
import com.rubberduck.live.chat.bridge.handlers.HistorySearchHandler
import com.rubberduck.live.chat.bridge.handlers.HistoryToggleFavoriteHandler
import com.rubberduck.live.chat.bridge.handlers.InsertHandler
import com.rubberduck.live.chat.bridge.handlers.LogHandler
import com.rubberduck.live.chat.bridge.handlers.ModelPersistHandler
import com.rubberduck.live.chat.bridge.handlers.ModelsHandler
import com.rubberduck.live.chat.bridge.handlers.NotifyHandler
import com.rubberduck.live.chat.bridge.handlers.OpenFilesHandler
import com.rubberduck.live.chat.bridge.handlers.ReadyHandler
import com.rubberduck.live.chat.bridge.handlers.RejectEditHandler
import com.rubberduck.live.chat.bridge.handlers.ReplaceHandler
import com.rubberduck.live.chat.bridge.handlers.SearchFilesHandler
import com.rubberduck.live.chat.bridge.handlers.SearchSemanticHandler
import com.rubberduck.live.chat.bridge.handlers.SelectionHandler
import com.rubberduck.live.chat.bridge.handlers.SessionDeleteHandler
import com.rubberduck.live.chat.bridge.handlers.SessionListHandler
import com.rubberduck.live.chat.bridge.handlers.SessionLoadHandler
import com.rubberduck.live.chat.bridge.handlers.SessionRenameHandler
import com.rubberduck.live.chat.bridge.handlers.SkillBodyHandler
import com.rubberduck.live.chat.bridge.handlers.SkillsHandler

/**
 * Thin facade over [ChatBridgeDispatcher] — owns the JCEF
 * [com.intellij.ui.jcef.JBCefJSQuery] lifecycle, builds the shared
 * [ChatHandlerContext], and forwards the bootstrap script to the
 * browser so `window.rubberduck.*` is available on every page load.
 *
 * Every per-action method that used to live here was extracted into its
 * own [com.rubberduck.live.chat.bridge.ChatBridgeHandler] implementation
 * under `chat/bridge/handlers/`. See
 * [`docs/CHAT_PLUGIN_IMPROVEMENT_PLAN.md`](../../../../../../../docs/CHAT_PLUGIN_IMPROVEMENT_PLAN.md)
 * for the refactor rationale.
 *
 * **Wire-format parity:** the action names and JSON payload shapes are
 * preserved verbatim — existing `chat-webui/app.js` and VS Code sibling
 * implementations continue to work unmodified.
 */
class ChatBridge(
    project: Project,
    private val browser: JBCefBrowser,
    @Suppress("unused") private val executeJs: (String) -> Unit,
) : Disposable {

    private val context = ChatHandlerContext(project = project)

    private val dispatcher = ChatBridgeDispatcher(context).registerAll(
        ConfigHandler(),
        SelectionHandler(),
        OpenFilesHandler(),
        DiagnosticsHandler(),
        FileReadHandler(),
        FilePickHandler(),
        ModelPersistHandler(),
        InsertHandler(),
        ReplaceHandler(),
        ClipboardHandler(),
        SkillsHandler(),
        SkillBodyHandler(),
        ModelsHandler(),
        HistoryHandler(),
        SessionListHandler(),
        SessionLoadHandler(),
        SessionDeleteHandler(),
        SessionRenameHandler(),
        HistorySearchHandler(),
        HistoryToggleFavoriteHandler(),
        HistoryExportMarkdownHandler(),
        FileSaveHandler(),
        LogHandler(),
        NotifyHandler(),
        ReadyHandler(),
        EditHandler(),
        RejectEditHandler(),
        EmitInterventionHandler(),
        SearchFilesHandler(),
        SearchSemanticHandler(),
    )

    /** JS source injected on every page load to expose `window.rubberduck.*`. */
    fun buildBootstrapScript(): String = dispatcher.install(browser)

    override fun dispose() = dispatcher.dispose()

    companion object {
        /** Small indirection so the theme decision lives in one place. */
        fun isDarkTheme(): Boolean {
            return runCatching { !com.intellij.ui.JBColor.isBright() }.getOrDefault(true)
        }
    }
}
