package com.rubberduck.live.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * A single developer event, wire-compatible with the backend schema in
 * [ARCHITECTURE.md §3](../../../../../../docs/ARCHITECTURE.md).
 *
 * - [ts] is epoch millis.
 * - [file] must already be project-relative (redacted by [com.rubberduck.live.util.PathRedactor]).
 * - [payload] is type-specific and left as a free-form JSON object.
 */
@Serializable
data class DeveloperEvent(
    val event_id: String,
    val session_id: String,
    val ts: Long,
    val type: String,
    val file: String? = null,
    val language: String? = null,
    val payload: JsonObject = JsonObject(emptyMap()),
) {
    /** Convenience accessor treating [type] as the enum; returns null if unknown. */
    val typeEnum: EventType?
        get() = runCatching { EventType.valueOf(type) }.getOrNull()

    @Suppress("unused")
    fun payloadField(name: String): JsonElement? = payload[name]
}
