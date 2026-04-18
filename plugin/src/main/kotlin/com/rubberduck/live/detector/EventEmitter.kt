package com.rubberduck.live.detector

import com.rubberduck.live.model.DeveloperEvent
import com.rubberduck.live.model.EventType
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.util.UUID

/**
 * Shared factory for constructing & publishing [DeveloperEvent]s so every
 * listener emits a schema-compliant payload.
 */
object EventEmitter {

    fun emit(
        type: EventType,
        file: String? = null,
        language: String? = null,
        payload: Map<String, JsonElement> = emptyMap(),
    ): DeveloperEvent {
        val ctx = SessionContext.getInstance()
        val ev = DeveloperEvent(
            event_id = UUID.randomUUID().toString(),
            session_id = ctx.sessionId,
            ts = System.currentTimeMillis(),
            type = type.name,
            file = file,
            language = language,
            payload = JsonObject(payload),
        )
        ctx.record(ev)
        EventBus.getInstance().publish(ev)
        com.rubberduck.live.storage.EventLog.getInstance().append(ev)
        return ev
    }

    /** Build a [JsonObject] from vararg (name, value) pairs of JSON primitives. */
    fun json(vararg pairs: Pair<String, Any?>): JsonObject = buildJsonObject {
        for ((k, v) in pairs) {
            when (v) {
                null -> {}
                is Number -> put(k, JsonPrimitive(v))
                is Boolean -> put(k, JsonPrimitive(v))
                is String -> put(k, JsonPrimitive(v))
                else -> put(k, JsonPrimitive(v.toString()))
            }
        }
    }
}
