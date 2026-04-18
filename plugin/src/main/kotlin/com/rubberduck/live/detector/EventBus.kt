package com.rubberduck.live.detector

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.rubberduck.live.model.DeveloperEvent
import com.rubberduck.live.model.EventType
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * In-process pub/sub for [DeveloperEvent]s.
 *
 * All producers (listeners, detector, actions) post here; consumers
 * (WS client, SQLite log, tool window, detector) subscribe. Backed by a
 * replay-free [MutableSharedFlow] with a generous extraBufferCapacity so
 * bursty edit events don't block the EDT.
 */
@Service(Service.Level.APP)
class EventBus {

    private val _events = MutableSharedFlow<DeveloperEvent>(
        replay = 0,
        extraBufferCapacity = 256,
    )

    /** Read-only stream of all events. */
    val events: SharedFlow<DeveloperEvent> = _events.asSharedFlow()

    /** Non-blocking publish. Returns false if the buffer was full (rare). */
    fun publish(event: DeveloperEvent): Boolean = _events.tryEmit(event)

    /** Convenience helper: `bus.publishType(EDIT, file="...", payload={...})`. */
    @Suppress("unused")
    fun hasListeners(): Boolean = _events.subscriptionCount.value > 0

    companion object {
        fun getInstance(): EventBus =
            ApplicationManager.getApplication().getService(EventBus::class.java)

        /** Produce [EventType] as a string; kept here to keep imports in one place. */
        fun typeName(t: EventType): String = t.name
    }
}
