package com.rubberduck.live.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.vfs.VirtualFile
import com.rubberduck.live.settings.RubberDuckSettings
import com.rubberduck.live.net.BackendClient
import com.rubberduck.live.util.PathRedactor
import com.rubberduck.live.util.CodeSelection
import org.jetbrains.annotations.NotNull
import javax.swing.SwingUtilities

class ExplainOldCodeAction : AnAction() {
    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isEnabledAndVisible = editor != null && file != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project: Project = e.project ?: return
        val editor: Editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val file: VirtualFile = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val document = editor.document
        val selection = editor.selectionModel
        val code = if (selection.hasSelection()) {
            selection.selectedText ?: ""
        } else {
            CodeSelection.getFunctionAtCaret(editor, file)
        }
        if (code.isNullOrBlank()) {
            Messages.showInfoMessage(project, "No function or code selected.", "RubberDuck: Explain Old Code")
            return
        }
        val redactedPath = PathRedactor.redact(file.path)
        val backendBase = RubberDuckSettings.getInstance().backendApiBase
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Explaining Code with RubberDuck", true) {
            override fun run(indicator: com.intellij.openapi.progress.ProgressIndicator) {
                try {
                    indicator.text = "Contacting RubberDuck..."
                    val response = BackendClient.post(
                        url = "$backendBase/api/v1/explain-code",
                        json = mapOf(
                            "file_path" to redactedPath,
                            "code" to code,
                            "context" to ""
                        )
                    )
                    val explanation = response?.get("explanation")?.asString ?: "No explanation returned."
                    val complexity = response?.get("complexity")?.asString ?: "Unknown"
                    val suggestions = response?.get("suggestions")?.asJsonArray?.joinToString("\n") { "- " + it.asString } ?: "None"
                    val message = "<html><b>Explanation:</b><br>${escapeHtml(explanation)}<br><br><b>Complexity:</b> $complexity<br><br><b>Suggestions:</b><br>${escapeHtml(suggestions)}".replace("\n", "<br>")
                    SwingUtilities.invokeLater {
                        Messages.showDialog(
                            project,
                            message,
                            "RubberDuck: Explain Old Code",
                            arrayOf("OK"),
                            0,
                            null
                        )
                    }
                } catch (ex: Exception) {
                    SwingUtilities.invokeLater {
                        Messages.showErrorDialog(project, "Failed to explain code: ${ex.message}", "RubberDuck: Explain Old Code")
                    }
                }
            }
        })
    }

    private fun escapeHtml(text: String): String {
        return text.replace("&", "&")
            .replace("<", "<")
            .replace(">", ">")
            .replace("\"", """)
            .replace("'", "'")
    }
}
