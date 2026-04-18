package com.rubberduck.live.listeners

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.rubberduck.live.detector.EventEmitter
import com.rubberduck.live.model.EventType
import com.rubberduck.live.util.PathRedactor
import java.util.concurrent.ConcurrentHashMap

/**
 * Emits `INSPECTION` events for ERROR/WARNING highlights produced by
 * IntelliJ's background analysis daemon.
 *
 * Hooks the per-project `DaemonCodeAnalyzer.DAEMON_EVENT_TOPIC` bus. When a
 * daemon pass finishes for one or more file editors, we walk their highlights
 * via [DaemonCodeAnalyzerImpl.processHighlights] and emit an event per NEW
 * issue (deduped against a per-file `Set<digest>` so repeat passes don't
 * flood).
 *
 * We skip `WEAK_WARNING`/`INFO` to keep the event stream signal-dense —
 * those severities would drown the dashboard.
 */
class InspectionListener(private val project: Project) {

    private val log = Logger.getInstance(InspectionListener::class.java)

    /** file-path -> digests seen on the last daemon pass. */
    private val seen = ConcurrentHashMap<String, MutableSet<String>>()

    private val daemonListener = object : DaemonCodeAnalyzer.DaemonListener {
        override fun daemonFinished(fileEditors: MutableCollection<out FileEditor>) {
            // Runs off the EDT; wrap highlight reads in a read-action.
            ApplicationManager.getApplication().runReadAction {
                for (fileEditor in fileEditors) {
                    val textEditor = fileEditor as? TextEditor ?: continue
                    processFile(textEditor.editor.document)
                }
            }
        }
    }

    private fun processFile(doc: Document) {
        val vf: VirtualFile = FileDocumentManager.getInstance().getFile(doc) ?: return
        val redacted = PathRedactor.redact(project, vf.path) ?: return
        val currentPass = mutableSetOf<String>()

        for (severity in SEVERITIES) {
            DaemonCodeAnalyzerImpl.processHighlights(
                doc, project, severity, 0, doc.textLength,
            ) { info: HighlightInfo ->
                // Only count highlights at the exact severity we asked for —
                // processHighlights returns everything ≥ min severity.
                if (info.severity == severity) {
                    val digest = digestFor(info, doc)
                    currentPass.add(digest)
                    val prev = seen[redacted]
                    if (prev == null || digest !in prev) {
                        emit(redacted, info, doc, severity, vf.extension)
                    }
                }
                true
            }
        }
        seen[redacted] = currentPass
    }

    private fun digestFor(info: HighlightInfo, doc: Document): String {
        val line = lineOf(info, doc)
        val desc = info.description.orEmpty()
        val severityName = info.severity.name
        return "$line:$severityName:$desc"
    }

    private fun lineOf(info: HighlightInfo, doc: Document): Int {
        val off = info.actualStartOffset.coerceIn(0, doc.textLength)
        return runCatching { doc.getLineNumber(off) }.getOrDefault(0)
    }

    private fun emit(
        redacted: String,
        info: HighlightInfo,
        doc: Document,
        severity: HighlightSeverity,
        extension: String?,
    ) {
        val description = info.description.orEmpty().trim()
        if (description.isBlank()) return  // headless highlights — skip
        val inspectionId = runCatching {
            // Not all highlights come from a named inspection (some are from
            // parser/annotator). When it's null we just omit the field.
            info.inspectionToolId
        }.getOrNull()
        val payload = EventEmitter.json(
            "severity" to severity.name,
            "description" to description.take(200),
            "line" to lineOf(info, doc),
            "inspection_id" to inspectionId,
        )
        EventEmitter.emit(
            EventType.INSPECTION,
            file = redacted,
            language = guessLanguage(extension),
            payload = payload,
        )
        log.debug("INSPECTION emitted: $redacted line=${lineOf(info, doc)} $description")
    }

    private fun guessLanguage(ext: String?): String? = when (ext?.lowercase()) {
        "py" -> "python"
        "kt", "kts" -> "kotlin"
        "java" -> "java"
        "ts", "tsx" -> "typescript"
        "js", "jsx" -> "javascript"
        else -> ext
    }

    fun install(parent: Disposable) {
        val connection = project.messageBus.connect(parent)
        connection.subscribe(DaemonCodeAnalyzer.DAEMON_EVENT_TOPIC, daemonListener)
        log.info("InspectionListener installed for project=${project.name}")
    }

    companion object {
        // Severities we emit events for. ERROR first so repeated passes see
        // the most-severe issue fresh before WARNING digests dedupe.
        private val SEVERITIES = listOf(
            HighlightSeverity.ERROR,
            HighlightSeverity.WARNING,
        )
    }
}
