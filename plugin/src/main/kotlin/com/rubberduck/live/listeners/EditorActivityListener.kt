package com.rubberduck.live.listeners

import com.intellij.openapi.command.CommandEvent
import com.intellij.openapi.command.CommandListener
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.rubberduck.live.detector.EventEmitter
import com.rubberduck.live.model.EventType
import com.rubberduck.live.util.PathRedactor

/**
 * Captures document edits + undo/redo commands and forwards them to
 * [EventEmitter] after path redaction.
 *
 * Edits are bulk-captured per [DocumentEvent] (IntelliJ batches composite
 * actions into one event, so we don't flood the bus with per-keystroke rows).
 */
class EditorActivityListener(private val project: Project) {

    private val docListener = object : DocumentListener {
        override fun documentChanged(event: DocumentEvent) {
            val doc: Document = event.document
            val vf = FileDocumentManager.getInstance().getFile(doc) ?: return
            val file = PathRedactor.redact(project, vf.path) ?: return
            val added = event.newLength
            val removed = event.oldLength
            val line = event.document.getLineNumber(event.offset)
            val payload = EventEmitter.json(
                "chars_added" to added,
                "chars_removed" to removed,
                "line" to line,
            )
            EventEmitter.emit(
                EventType.EDIT,
                file = file,
                language = guessLanguage(vf.extension),
                payload = payload,
            )
        }
    }

    private val commandListener = object : CommandListener {
        override fun commandFinished(event: CommandEvent) {
            val name = event.commandName ?: return
            val lower = name.lowercase()
            val type = when {
                lower.contains("undo") -> EventType.UNDO
                lower.contains("redo") -> EventType.REDO
                else -> return
            }
            val file = com.rubberduck.live.detector.SessionContext.getInstance().activeFile
            val payload = EventEmitter.json("line" to 0)
            EventEmitter.emit(type, file = file, payload = payload)
        }
    }

    /** Install the listeners on the application-wide editor factory + command bus. */
    fun install(parent: com.intellij.openapi.Disposable) {
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(docListener, parent)
        // CommandProcessor exposes only the single-arg addCommandListener; subscribe via
        // the application message bus so disposal is handled by [parent].
        val bus = com.intellij.openapi.application.ApplicationManager.getApplication()
            .messageBus.connect(parent)
        bus.subscribe(com.intellij.openapi.command.CommandListener.TOPIC, commandListener)
    }

    private fun guessLanguage(ext: String?): String? = when (ext?.lowercase()) {
        "py" -> "python"
        "kt", "kts" -> "kotlin"
        "java" -> "java"
        "ts", "tsx" -> "typescript"
        "js", "jsx" -> "javascript"
        else -> ext
    }
}
