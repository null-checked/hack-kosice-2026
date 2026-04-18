package com.rubberduck.live.listeners

import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.rubberduck.live.detector.EventEmitter
import com.rubberduck.live.model.EventType
import com.rubberduck.live.util.PathRedactor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Fires a single `CARET_DWELL` event when the caret sits on the same line for
 * ≥ 90 s. Payload includes `has_error_marker`, computed by querying the
 * daemon for ERROR-severity highlights intersecting the caret line.
 *
 * We don't emit intermediate dwells; one event per dwell period keeps the bus
 * quiet and satisfies the StuckDetector's `long_idle_on_error` rule.
 */
class CaretDwellTracker(private val project: Project) : CaretListener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var timer: Job? = null

    @Volatile private var lastLine: Int = -1
    @Volatile private var lastEditor: Editor? = null

    override fun caretPositionChanged(event: CaretEvent) {
        val line = event.newPosition.line
        if (line == lastLine && event.editor === lastEditor) return
        lastLine = line
        lastEditor = event.editor
        timer?.cancel()
        timer = scope.launch {
            delay(90_000)
            fireDwell(event.editor, line, 90_000)
        }
    }

    private fun fireDwell(editor: Editor, line: Int, durMs: Long) {
        // Read on EDT because daemon / VFS access requires read-action.
        ApplicationManager.getApplication().invokeLater {
            ApplicationManager.getApplication().runReadAction {
                val doc = editor.document
                val vf = FileDocumentManager.getInstance().getFile(doc) ?: return@runReadAction
                val file = PathRedactor.redact(project, vf.path) ?: return@runReadAction
                val hasError = hasErrorOnLine(editor, line)
                val payload = EventEmitter.json(
                    "line" to line,
                    "duration_ms" to durMs,
                    "has_error_marker" to hasError,
                )
                EventEmitter.emit(EventType.CARET_DWELL, file = file, payload = payload)
            }
        }
    }

    private fun hasErrorOnLine(editor: Editor, line: Int): Boolean {
        return try {
            val doc = editor.document
            if (line < 0 || line >= doc.lineCount) return false
            val start = doc.getLineStartOffset(line)
            val end = doc.getLineEndOffset(line)
            var found = false
            DaemonCodeAnalyzerImpl.processHighlights(
                doc, project, HighlightSeverity.ERROR, start, end
            ) { _ -> found = true; false }
            found
        } catch (_: Throwable) {
            false
        }
    }

    fun install(parent: com.intellij.openapi.Disposable) {
        EditorFactory.getInstance().eventMulticaster.addCaretListener(this, parent)
        com.intellij.openapi.util.Disposer.register(parent) {
            timer?.cancel()
            scope.cancel()
        }
    }
}
