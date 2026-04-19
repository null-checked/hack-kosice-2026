package com.rubberduck.live.chat.bridge.handlers

import com.intellij.openapi.diagnostic.Logger
import com.rubberduck.live.chat.bridge.ChatBridgeHandler
import com.rubberduck.live.chat.bridge.ChatHandlerContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * One-shot "webview finished loading" signal. Currently only records a
 * log line; Phase 2 will turn this into the webview-watchdog heartbeat.
 */
class ReadyHandler : ChatBridgeHandler() {
    override val name: String = "onReady"

    private val log = Logger.getInstance(ReadyHandler::class.java)

    override fun handle(payload: String, ctx: ChatHandlerContext): JsonElement {
        log.info("[chat-webui] ready")
        return JsonPrimitive(true)
    }
}
