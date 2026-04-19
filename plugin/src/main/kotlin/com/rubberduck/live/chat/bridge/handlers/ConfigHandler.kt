package com.rubberduck.live.chat.bridge.handlers

import com.rubberduck.live.chat.bridge.ChatBridgeHandler
import com.rubberduck.live.chat.bridge.ChatHandlerContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Returns the config payload consumed by `chat-webui/app.js` on boot —
 * backend URL, current theme, chat model + history knobs, and the
 * available-models list for the combo box.
 *
 * Extracted verbatim from `ChatBridge.getConfig()` at line 176 of the
 * pre-refactor monolith.
 */
class ConfigHandler : ChatBridgeHandler() {
    override val name: String = "getConfig"

    override fun handle(payload: String, ctx: ChatHandlerContext): JsonElement {
        val s = ctx.settings
        val st = s.state
        return buildJsonObject {
            put("backendBase", s.backendApiBase)
            put("theme", currentTheme())
            put("defaultModel", st.chatModel)
            put("chatModel", st.chatModel)
            put("chatMaxHistoryTokens", st.chatMaxHistoryTokens)
            put("chatAttachOpenFiles", st.chatAttachOpenFiles)
            put("chatAttachDiagnostics", st.chatAttachDiagnostics)
            put("chatAvailableModels", buildJsonArray {
                st.chatAvailableModels.forEach { add(JsonPrimitive(it)) }
            })
        }
    }

    private fun currentTheme(): String =
        if (runCatching { !com.intellij.ui.JBColor.isBright() }.getOrDefault(true)) "dark" else "light"
}
