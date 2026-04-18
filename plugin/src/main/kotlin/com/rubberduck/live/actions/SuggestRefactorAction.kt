package com.rubberduck.live.actions

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.rubberduck.live.detector.EventEmitter
import com.rubberduck.live.intervention.SocraticNotifier
import com.rubberduck.live.model.EventType
import com.rubberduck.live.model.SelectionRange
import com.rubberduck.live.model.SuggestRefactorRequest
import com.rubberduck.live.model.SuggestRefactorResponse
import com.rubberduck.live.net.BackendClient
import com.rubberduck.live.settings.RubberDuckSettings
import com.rubberduck.live.util.PathRedactor
import java.awt.Dimension
import java.util.UUID
import javax.swing.JComponent

/**
 * Right-click action: send the current file (or selection) to
 * `/suggest-refactor` and open IntelliJ's native diff viewer on the response.
 */
class SuggestRefactorAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null &&
            e.getData(PlatformDataKeys.EDITOR) != null
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
                "Strict privacy is on — \"Suggest Refactor\" is disabled because it would send code to the backend.",
            )
            return
        }
        val doc = editor.document
        val full = doc.text
        val selModel = editor.selectionModel
        val range = if (selModel.hasSelection()) {
            val startLine = doc.getLineNumber(selModel.selectionStart)
            val endLine = doc.getLineNumber(selModel.selectionEnd)
            SelectionRange(startLine, endLine)
        } else null
        val redacted = PathRedactor.redact(project, vf.path)
        val request = SuggestRefactorRequest(
            language = languageFor(vf.extension),
            code = full,
            file_path = redacted,
            selection_range = range,
        )
        val interventionId = UUID.randomUUID().toString()
        EventEmitter.emit(
            EventType.INTERVENTION_SHOWN,
            file = redacted,
            payload = EventEmitter.json("intervention_id" to interventionId, "kind" to "refactor"),
        )

        object : Task.Backgroundable(project, "Analyzing and refactoring…", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                val response = try {
                    BackendClient.getInstance().suggestRefactor(request)
                } catch (t: Throwable) {
                    ApplicationManager.getApplication().invokeLater {
                        SocraticNotifier.showInfo(
                            project,
                            "RubberDuck Live",
                            "Refactor request failed: ${t.message ?: t::class.simpleName}",
                        )
                    }
                    return
                }
                ApplicationManager.getApplication().invokeLater {
                    showDiffWithApply(project, editor.document, vf.name, full, response, interventionId, redacted)
                }
            }
        }.queue()
    }

    /**
     * Show the refactor diff in a modal dialog with **Apply** / **Cancel**
     * buttons. Only the Apply path mutates the document; Cancel (or closing
     * the window) emits an `INTERVENTION_DISMISSED` so dashboard metrics
     * reflect what actually happened. Replaces the old read-only
     * `DiffManager.showDiff(...)` flow which fired `ACCEPTED` prematurely
     * whether the user accepted or not.
     */
    private fun showDiffWithApply(
        project: Project,
        document: Document,
        fileName: String,
        originalCode: String,
        response: SuggestRefactorResponse,
        interventionId: String,
        redactedFile: String?,
    ) {
        val smellSummary = response.smells_detected.joinToString("\n") {
            "• ${it.type} (line ${it.line}): ${it.description}"
        }
        val titleWithSmells = if (smellSummary.isBlank()) {
            "RubberDuck refactor — $fileName"
        } else {
            "RubberDuck refactor — $fileName\n$smellSummary"
        }
        val factory = DiffContentFactory.getInstance()
        val before = factory.create(project, originalCode)
        val after = factory.create(project, response.refactored_code)
        val diffRequest = SimpleDiffRequest(titleWithSmells, before, after, "Original", "Suggested")

        val dialog = object : DialogWrapper(project, true) {
            private val diffPanel = DiffManager.getInstance()
                .createRequestPanel(project, disposable, null)
                .also { it.setRequest(diffRequest) }

            init {
                title = "Apply RubberDuck refactor?"
                setOKButtonText("Apply")
                setCancelButtonText("Cancel")
                init()
            }

            override fun createCenterPanel(): JComponent =
                diffPanel.component.apply { preferredSize = Dimension(960, 560) }
        }

        val applied = dialog.showAndGet()

        if (applied) {
            WriteCommandAction.runWriteCommandAction(
                project,
                "RubberDuck: Apply refactor",
                null,
                { document.setText(response.refactored_code) },
            )
            SocraticNotifier.showInfo(
                project,
                "✅ Refactor applied",
                smellSummary.ifBlank { "The suggested refactor has been written to the file. Ctrl+Z to revert." },
            )
            EventEmitter.emit(
                EventType.INTERVENTION_ACCEPTED,
                file = redactedFile,
                payload = EventEmitter.json(
                    "intervention_id" to interventionId,
                    "kind" to "refactor",
                ),
            )
        } else {
            EventEmitter.emit(
                EventType.INTERVENTION_DISMISSED,
                file = redactedFile,
                payload = EventEmitter.json(
                    "intervention_id" to interventionId,
                    "kind" to "refactor",
                ),
            )
        }
    }

    private fun languageFor(ext: String?): String = when (ext?.lowercase()) {
        "py" -> "python"
        "kt", "kts" -> "kotlin"
        "java" -> "java"
        "ts", "tsx" -> "typescript"
        "js", "jsx" -> "javascript"
        else -> "python"
    }
}
