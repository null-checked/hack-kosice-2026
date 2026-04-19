package com.rubberduck.live.chat.bridge.handlers

import com.rubberduck.live.chat.bridge.ChatBridgeHandler
import com.rubberduck.live.chat.bridge.ChatHandlerContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray

/**
 * Returns the list of chat model IDs surfaced by the model picker.
 *
 * Uses [com.rubberduck.live.settings.RubberDuckSettings.State.chatAvailableModels]
 * when populated; otherwise falls back to a built-in list so the UI is
 * never empty on a fresh install.
 */
class ModelsHandler : ChatBridgeHandler() {
    override val name: String = "listModels"

    override fun handle(payload: String, ctx: ChatHandlerContext): JsonElement {
        val s = ctx.settings.state
        val models = if (s.chatAvailableModels.isNotEmpty()) s.chatAvailableModels
        else DEFAULT_MODELS
        return buildJsonArray { models.forEach { add(JsonPrimitive(it)) } }
    }

    companion object {
        private val DEFAULT_MODELS = listOf(
            "gpt-4o-mini", "gpt-4o",
            "claude-3-5-sonnet-20241022",
            "llama3.1:8b", "qwen2.5-coder:7b",
        )
    }
}
