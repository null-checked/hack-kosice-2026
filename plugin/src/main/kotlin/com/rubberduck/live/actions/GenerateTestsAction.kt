package com.rubberduck.live.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.rubberduck.live.detector.EventEmitter
import com.rubberduck.live.intervention.SocraticNotifier
import com.rubberduck.live.model.EventType
import com.rubberduck.live.model.GenerateTestsRequest
import com.rubberduck.live.model.GenerateTestsResponse
import com.rubberduck.live.net.BackendClient
import com.rubberduck.live.settings.RubberDuckSettings
import com.rubberduck.live.util.CodeSelection
import com.rubberduck.live.util.PathRedactor
import java.util.UUID

/**
 * Right-click action: extract the function under caret, POST to
 * `/generate-tests`, then create/overwrite a sibling `test_<module>.<ext>`
 * and open it in the editor.
 */
class GenerateTestsAction : AnAction() {

    override fun update(e: AnActionEvent) {
        val enabled = e.project != null &&
            (e.getData(CommonDataKeys.VIRTUAL_FILE) != null || e.getData(PlatformDataKeys.EDITOR) != null)
        e.presentation.isEnabledAndVisible = enabled
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val settings = RubberDuckSettings.getInstance()
        if (settings.state.privacyMode) {
            SocraticNotifier.showInfo(
                project,
                "RubberDuck Live — privacy mode",
                "Strict privacy is on — \"Generate Tests\" is disabled because it would send code to the backend.",
            )
            return
        }
        val editor = e.getData(PlatformDataKeys.EDITOR)
        val vf = e.getData(CommonDataKeys.VIRTUAL_FILE)
            ?: editor?.let { com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getFile(it.document) }
            ?: return
        val language = languageFor(vf)
        val wholeText = editor?.document?.text ?: String(vf.contentsToByteArray())
        val selection: CodeSelection.Selection = if (editor != null) {
            CodeSelection.fromEditor(editor, language)
        } else {
            CodeSelection.extractFunction(wholeText, 0, language)
        }
        val functionCode = if (selection.code.isBlank()) wholeText else selection.code
        val redactedFile = PathRedactor.redact(project, vf.path)

        val request = GenerateTestsRequest(
            language = language,
            code = functionCode,
            function_name = selection.functionName,
            file_path = redactedFile,
        )
        val interventionId = UUID.randomUUID().toString()
        EventEmitter.emit(
            EventType.INTERVENTION_SHOWN,
            file = redactedFile,
            payload = EventEmitter.json("intervention_id" to interventionId, "kind" to "tests"),
        )

        object : Task.Backgroundable(project, "Generating tests…", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                val response = try {
                    BackendClient.getInstance().generateTests(request)
                } catch (t: Throwable) {
                    ApplicationManager.getApplication().invokeLater {
                        SocraticNotifier.showInfo(
                            project,
                            "RubberDuck Live",
                            "Test generation failed: ${t.message ?: t::class.simpleName}",
                        )
                    }
                    return
                }
                ApplicationManager.getApplication().invokeLater {
                    writeAndOpen(project, vf, response, interventionId, redactedFile)
                }
            }
        }.queue()
    }

    private fun writeAndOpen(
        project: Project,
        sourceFile: VirtualFile,
        response: GenerateTestsResponse,
        interventionId: String,
        redactedFile: String?,
    ) {
        WriteCommandAction.runWriteCommandAction(project) {
            val parent = sourceFile.parent ?: return@runWriteCommandAction
            val name = response.test_file_name.ifBlank { "test_${sourceFile.nameWithoutExtension}.py" }
            val existing = parent.findChild(name)
            val target = existing ?: parent.createChildData(this, name)
            VfsUtil.saveText(target, response.test_code)
            FileEditorManager.getInstance(project).openFile(target, true)
            EventEmitter.emit(
                EventType.INTERVENTION_ACCEPTED,
                file = redactedFile,
                payload = EventEmitter.json(
                    "intervention_id" to interventionId,
                    "kind" to "tests",
                ),
            )
            SocraticNotifier.showInfo(project, "✅ Tests generated", response.reasoning.take(200))
        }
    }

    private fun languageFor(vf: VirtualFile): String = when (vf.extension?.lowercase()) {
        "py" -> "python"
        "kt", "kts" -> "kotlin"
        "java" -> "java"
        "ts", "tsx" -> "typescript"
        "js", "jsx" -> "javascript"
        else -> "python"
    }
}
