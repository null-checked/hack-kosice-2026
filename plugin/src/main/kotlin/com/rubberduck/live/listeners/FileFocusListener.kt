package com.rubberduck.live.listeners

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.rubberduck.live.detector.EventEmitter
import com.rubberduck.live.detector.SessionContext
import com.rubberduck.live.model.EventType
import com.rubberduck.live.util.PathRedactor

/** Emits `FILE_FOCUS` events when the user switches tabs. */
class FileFocusListener(private val project: Project) : FileEditorManagerListener {

    private var lastFile: String? = null
    private var lastFocusTs: Long = System.currentTimeMillis()

    override fun selectionChanged(event: FileEditorManagerEvent) {
        val newVf = event.newFile ?: return
        val newPath = PathRedactor.redact(project, newVf.path) ?: return
        val now = System.currentTimeMillis()
        val prev = lastFile
        val duration = if (prev != null) now - lastFocusTs else null

        val payload = EventEmitter.json(
            "previous_file" to prev,
            "duration_in_previous_ms" to duration,
        )
        EventEmitter.emit(EventType.FILE_FOCUS, file = newPath, payload = payload)
        SessionContext.getInstance().activeFile = newPath
        lastFile = newPath
        lastFocusTs = now
    }

    /** Register the listener via the project message bus. */
    fun install(parent: com.intellij.openapi.Disposable) {
        val conn = project.messageBus.connect(parent)
        conn.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, this)
        // Prime active file from the current selection, if any.
        val fem = FileEditorManager.getInstance(project)
        fem.selectedFiles.firstOrNull()?.let { vf ->
            val p = PathRedactor.redact(project, vf.path)
            SessionContext.getInstance().activeFile = p
            lastFile = p
        }
    }
}
