package com.rubberduck.live.chat.bridge.handlers

import com.intellij.openapi.diagnostic.Logger
import com.rubberduck.live.chat.bridge.ChatBridgeHandler
import com.rubberduck.live.chat.bridge.ChatHandlerContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * Mirrors `console.log` from the web UI into the IntelliJ log so JS-side
 * issues show up under idea.log without needing devtools.
 */
class LogHandler : ChatBridgeHandler() {
    override val name: String = "log"

    private val log = Logger.getInstance(LogHandler::class.java)

    override fun handle(payload: String, ctx: ChatHandlerContext): JsonElement {
        val msg = unquoteString(payload)
        log.info("[chat-webui] $msg")
        return JsonPrimitive(true)
    }
}
