package com.rubberduck.live.chat.bridge.handlers

import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.Logger
import com.rubberduck.live.chat.bridge.ChatBridgeHandler
import com.rubberduck.live.chat.bridge.ChatHandlerContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Fires an IDE balloon notification on behalf of the web UI.
 *
 * **M3 hardening:**
 *  - Token bucket: max [MAX_PER_SECOND] notifications per second.
 *    Excess calls are dropped with a warn log.
 *  - Message cap: [MAX_MESSAGE_CHARS] — longer strings are truncated
 *    with an ellipsis so a runaway UI can't flood `Notifications.Bus`.
 */
class NotifyHandler : ChatBridgeHandler() {
    override val name: String = "notify"

    private val log = Logger.getInstance(NotifyHandler::class.java)

    /** Circular buffer of the last [MAX_PER_SECOND] accepted timestamps (ms). */
    private val recent = ArrayDeque<Long>(MAX_PER_SECOND)

    override fun handle(payload: String, ctx: ChatHandlerContext): JsonElement {
        val (rawMsg, kind) = parseMessageAndKind(payload)

        // Token-bucket rate limit: drop if [MAX_PER_SECOND] accepts
        // already fall inside the last 1 s window.
        val now = ctx.clock()
        synchronized(recent) {
            while (recent.isNotEmpty() && (now - recent.first() > WINDOW_MS)) {
                recent.removeFirst()
            }
            if (recent.size >= MAX_PER_SECOND) {
                log.warn("notify: rate-limited (${recent.size} in last ${WINDOW_MS}ms)")
                return JsonPrimitive(false)
            }
            recent.addLast(now)
        }

        // Truncate runaway messages.
        val message = if (rawMsg.length > MAX_MESSAGE_CHARS) {
            rawMsg.take(MAX_MESSAGE_CHARS) + "…"
        } else {
            rawMsg
        }

        val type = when (kind.lowercase()) {
            "error" -> NotificationType.ERROR
            "warn", "warning" -> NotificationType.WARNING
            else -> NotificationType.INFORMATION
        }
        ctx.notifier(message, type)
        return JsonPrimitive(true)
    }

    private fun parseMessageAndKind(rawPayload: String): Pair<String, String> {
        var message = rawPayload
        var kind = "info"
        try {
            when (val el = JSON.parseToJsonElement(rawPayload)) {
                is JsonPrimitive -> message = el.content
                is JsonObject -> {
                    message = (el["message"] as? JsonPrimitive)?.content ?: message
                    kind = (el["kind"] as? JsonPrimitive)?.content ?: kind
                }
                else -> {}
            }
        } catch (_: Throwable) {
            // leave message as the raw payload
        }
        return message to kind
    }

    companion object {
        /** Max notifications accepted inside the [WINDOW_MS] window. */
        const val MAX_PER_SECOND: Int = 10

        /** Sliding-window size for the rate limit, in milliseconds. */
        const val WINDOW_MS: Long = 1_000L

        /** Hard cap on the balloon message length. */
        const val MAX_MESSAGE_CHARS: Int = 4_096
    }
}
