package com.rubberduck.live.actions

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.rubberduck.live.detector.EventEmitter
import com.rubberduck.live.intervention.SocraticNotifier
import com.rubberduck.live.model.EventType
import com.rubberduck.live.model.SelectionRange
import com.rubberduck.live.model.SuggestRefactorRequest
import com.rubberduck.live.model.SuggestRefactorResponse
import com.rubberduck.live.net.BackendClient
import com.rubberduck.live.settings.RubberDuckSettings
import com.rubberduck.live.util.PathRedactor
import java.util.UUID

/**
 * Right-click action: send the current file (or selection) to
 * `/suggest-refactor` and open IntelliJ's native diff viewer on the response.
 */
class SuggestRefactorAction : AnAction() {

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
                    showDiff(project, vf.name, full, response, interventionId, redacted)
                }
            }
        }.queue()
    }

    private fun showDiff(
        project: com.intellij.openapi.project.Project,
        fileName: String,
        originalCode: String,
        response: SuggestRefactorResponse,
        interventionId: String,
        redactedFile: String?,
    ) {
        val smellSummary = response.smells_detected.joinToString("\n") { "• ${it.type} (line ${it.line}): ${it.description}" }
        val title = if (smellSummary.isBlank()) {
            "RubberDuck refactor — $fileName"
        } else {
            "RubberDuck refactor — $fileName\n$smellSummary"
        }
        val factory = DiffContentFactory.getInstance()
        val before = factory.create(project, originalCode)
        val after = factory.create(project, response.refactored_code)
        val diffRequest = SimpleDiffRequest(title, before, after, "Original", "Suggested")
        DiffManager.getInstance().showDiff(project, diffRequest)
        EventEmitter.emit(
            EventType.INTERVENTION_ACCEPTED,
            file = redactedFile,
            payload = EventEmitter.json(
                "intervention_id" to interventionId,
                "kind" to "refactor",
            ),
        )
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
