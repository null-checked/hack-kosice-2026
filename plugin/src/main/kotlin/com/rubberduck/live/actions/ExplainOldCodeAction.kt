package com.rubberduck.live.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.rubberduck.live.intervention.SocraticNotifier
import com.rubberduck.live.model.ExplainCodeRequest
import com.rubberduck.live.net.BackendClient
import com.rubberduck.live.settings.RubberDuckSettings
import com.rubberduck.live.util.CodeSelection
import com.rubberduck.live.util.PathRedactor

/**
 * Right-click action: ask the backend to explain the function under caret
 * (or the current selection). Useful for code you wrote three months ago
 * and no longer remember. Mirrors the roadmap item in
 * `docs/ARCHITECTURE.md §14`.
 *
 * Uses the standard RubberDuck patterns:
 * - `CodeSelection.fromEditor` to get the enclosing function
 * - `PathRedactor.redact(project, path)` so absolute paths stay local
 * - Strict-privacy gate
 * - Background `Task.Backgroundable` for the HTTP call
 */
class ExplainOldCodeAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible =
            e.project != null && e.getData(PlatformDataKeys.EDITOR) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(PlatformDataKeys.EDITOR) ?: return
        val vf = e.getData(CommonDataKeys.VIRTUAL_FILE)
            ?: com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getFile(editor.document)
            ?: return

        if (RubberDuckSettings.getInstance().state.privacyMode) {
            SocraticNotifier.showInfo(
                project,
                "RubberDuck Live — privacy mode",
                "Strict privacy is on — \"Explain old code\" is disabled because it would send code to the backend.",
            )
            return
        }

        val language = languageFor(vf.extension)
        val selection: CodeSelection.Selection = if (editor.selectionModel.hasSelection()) {
            CodeSelection.Selection(
                code = editor.selectionModel.selectedText.orEmpty(),
                functionName = null,
                startLine = editor.document.getLineNumber(editor.selectionModel.selectionStart),
                endLine = editor.document.getLineNumber(editor.selectionModel.selectionEnd),
                language = language,
            )
        } else {
            CodeSelection.fromEditor(editor, language)
        }

        if (selection.code.isBlank()) {
            SocraticNotifier.showInfo(
                project,
                "RubberDuck: Explain Old Code",
                "No function detected under the caret. Select some code and try again.",
            )
            return
        }

        val redactedPath = PathRedactor.redact(project, vf.path)
        val request = ExplainCodeRequest(
            code = selection.code,
            file_path = redactedPath,
            language = language,
            context = "",
        )

        object : Task.Backgroundable(project, "Explaining code with RubberDuck…", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                val response = try {
                    BackendClient.getInstance().explainCode(request)
                } catch (t: Throwable) {
                    ApplicationManager.getApplication().invokeLater {
                        SocraticNotifier.showInfo(
                            project,
                            "RubberDuck: Explain Old Code",
                            "Request failed: ${t.message ?: t::class.simpleName}",
                        )
                    }
                    return
                }
                ApplicationManager.getApplication().invokeLater {
                    val suggestions = if (response.suggestions.isEmpty()) "(none)"
                    else response.suggestions.joinToString("<br/>") { "• $it" }
                    val message = buildString {
                        append("<html>")
                        append("<b>Explanation:</b><br/>")
                        append(escapeHtml(response.explanation))
                        append("<br/><br/><b>Complexity:</b> ")
                        append(escapeHtml(response.complexity))
                        append("<br/><br/><b>Suggestions:</b><br/>")
                        append(suggestions)
                        append("</html>")
                    }
                    Messages.showInfoMessage(project, message, "🦆 RubberDuck: Explain Old Code")
                }
            }
        }.queue()
    }

    private fun languageFor(ext: String?): String = when (ext?.lowercase()) {
        "py" -> "python"
        "kt", "kts" -> "kotlin"
        "java" -> "java"
        "ts", "tsx" -> "typescript"
        "js", "jsx" -> "javascript"
        else -> "python"
    }

    private fun escapeHtml(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")
}
