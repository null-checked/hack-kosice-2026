package com.rubberduck.live.chat.bridge.handlers

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.ide.CopyPasteManager
import com.rubberduck.live.chat.bridge.ChatBridgeHandler
import com.rubberduck.live.chat.bridge.ChatHandlerContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.awt.datatransfer.StringSelection

/**
 * Copies text supplied by the web UI into the IDE clipboard.
 *
 * **M3 hardening:**
 *  - Max [MAX_PAYLOAD_BYTES] per payload (10 MB) — catches a runaway UI
 *    loop from exhausting EDT memory.
 *  - Minimum [MIN_INTERVAL_MS] between accepted copies (200 ms, matching
 *    cc-gui's token bucket). Dropped calls log a warn and return `false`.
 */
class ClipboardHandler : ChatBridgeHandler() {
    override val name: String = "copyToClipboard"

    private val log = Logger.getInstance(ClipboardHandler::class.java)

    @Volatile
    private var lastAcceptedAt: Long = 0L

    override fun handle(payload: String, ctx: ChatHandlerContext): JsonElement {
        val text = unquoteString(payload)

        // Size cap first — rejecting huge payloads cheaply dodges the whole
        // clipboard round-trip.
        if (text.length.toLong() * 2 > MAX_PAYLOAD_BYTES) {
            log.warn("copyToClipboard: rejected payload over ${MAX_PAYLOAD_BYTES} bytes (len=${text.length})")
            return JsonPrimitive(false)
        }

        val now = ctx.clock()
        val delta = now - lastAcceptedAt
        if (delta in 0 until MIN_INTERVAL_MS) {
            log.warn("copyToClipboard: rate-limited (${delta}ms < ${MIN_INTERVAL_MS}ms)")
            return JsonPrimitive(false)
        }
        lastAcceptedAt = now

        CopyPasteManager.getInstance().setContents(StringSelection(text))
        return JsonPrimitive(true)
    }

    companion object {
        /** 10 MB hard cap on a single copy. Matches cc-gui's `ClipboardHandler`. */
        const val MAX_PAYLOAD_BYTES: Long = 10L * 1024 * 1024

        /** Minimum milliseconds between accepted copies (≈ 5 Hz). */
        const val MIN_INTERVAL_MS: Long = 200L
    }
}
