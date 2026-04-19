package com.rubberduck.live.chat.bridge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Contract for a single bridge action exposed to the chat web UI as
 * `window.rubberduck.<name>()`. Each implementation lives in
 * [com.rubberduck.live.chat.bridge.handlers] and is registered with
 * [ChatBridgeDispatcher] at `ChatBridge` construction time.
 *
 * Handlers are stateless with respect to JCEF — they receive the raw
 * JSON-encoded `payload` string that the web UI wrapped with
 * `JSON.stringify(arg)` and must return a [JsonElement] (never `null`;
 * use [kotlinx.serialization.json.JsonNull] for absent values).
 *
 * Threading: handlers declare their own threading needs. Anything that
 * touches PSI / VFS / editors must wrap work in `ReadAction.compute { … }`
 * or `WriteCommandAction.runWriteCommandAction(…)`. The dispatcher
 * itself makes no EDT assumptions.
 */
abstract class ChatBridgeHandler {

    /** Matches the `name` used by the web UI (`window.rubberduck.<name>`). */
    abstract val name: String

    /**
     * Execute the action. [payload] is the raw JSON string the JS side sent
     * (already `JSON.stringify`'d on that end). Implementations should
     * translate domain errors into a structured JSON response; **throwing**
     * is reserved for programmer errors and is caught by the dispatcher
     * which reports a generic structured error back to JS.
     */
    abstract fun handle(payload: String, ctx: ChatHandlerContext): JsonElement

    // ------------------------------------------------------- Shared helpers

    /**
     * The web UI wraps every outgoing argument in `JSON.stringify(arg)`
     * which, for a plain string, yields `"foo"`. Most handlers want the
     * raw string value — unwrap the single-value JSON envelope.
     */
    protected fun unquoteString(payload: String): String {
        if (payload.isEmpty() || payload == "null") return ""
        return try {
            when (val el = JSON.parseToJsonElement(payload)) {
                is JsonPrimitive -> el.content
                else -> payload
            }
        } catch (_: Throwable) {
            payload
        }
    }

    /**
     * Parse a bridge payload that may arrive either as a raw JSON object
     * (unusual) or JSON-stringified (the default — every JS-side call does
     * `JSON.stringify(arg)`). Returns `null` if the payload can't be
     * shaped into a [JsonObject].
     */
    protected fun parsePayloadObject(rawPayload: String): JsonObject? {
        if (rawPayload.isBlank() || rawPayload == "null") return null
        return try {
            when (val first = JSON.parseToJsonElement(rawPayload)) {
                is JsonObject -> first
                is JsonPrimitive -> {
                    // Web UI may have double-stringified: `"{...}"` → parse again.
                    if (first.isString) {
                        val inner = JSON.parseToJsonElement(first.content)
                        if (inner is JsonObject) inner else null
                    } else null
                }
                else -> null
            }
        } catch (_: Throwable) {
            null
        }
    }

    companion object {
        /** Shared [Json] codec — lenient to match the web UI's loose payloads. */
        val JSON: Json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
            explicitNulls = false
        }
    }
}
