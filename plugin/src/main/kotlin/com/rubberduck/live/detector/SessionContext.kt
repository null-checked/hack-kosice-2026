package com.rubberduck.live.detector

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.rubberduck.live.model.DeveloperEvent
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Holds per-IDE-run state shared across listeners and the detector.
 *
 * Most notably the plugin's `session_id` (fresh UUID per IDE launch), the
 * current active file, and a bounded ring buffer of the most recent events so
 * request-time context (e.g., `/socratic-question`) can attach the last 20.
 */
@Service(Service.Level.APP)
class SessionContext {

    val sessionId: String = UUID.randomUUID().toString()

    @Volatile var activeFile: String? = null

    @Volatile var lastTestFailureSignature: String? = null

    private val recent = ConcurrentLinkedDeque<DeveloperEvent>()
    private val maxRecent = 50

    /** Append [event] to the in-memory ring. Thread-safe. */
    fun record(event: DeveloperEvent) {
        recent.addLast(event)
        while (recent.size > maxRecent) recent.pollFirst()
    }

    /** Snapshot of the last [n] events for request context. */
    fun recent(n: Int): List<DeveloperEvent> {
        val list = recent.toList()
        return if (list.size <= n) list else list.subList(list.size - n, list.size)
    }

    companion object {
        fun getInstance(): SessionContext =
            ApplicationManager.getApplication().getService(SessionContext::class.java)
    }
}
