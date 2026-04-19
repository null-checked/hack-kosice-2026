package com.rubberduck.live.chat.bridge.handlers

import com.rubberduck.live.chat.bridge.ChatBridgeHandler
import com.rubberduck.live.chat.bridge.ChatHandlerContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * Persists a new chat model selection into [com.rubberduck.live.settings.RubberDuckSettings].
 *
 * If the user picks a model that isn't already in the list, it's
 * appended so the combobox round-trips next time Settings opens.
 */
class ModelPersistHandler : ChatBridgeHandler() {
    override val name: String = "setModel"

    override fun handle(payload: String, ctx: ChatHandlerContext): JsonElement {
        val model = unquoteString(payload)
        if (model.isBlank()) return JsonPrimitive(false)
        val s = ctx.settings.state
        s.chatModel = model
        if (s.chatAvailableModels.none { it.equals(model, ignoreCase = true) }) {
            s.chatAvailableModels.add(model)
        }
        return JsonPrimitive(true)
    }
}
