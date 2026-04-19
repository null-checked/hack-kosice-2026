package com.rubberduck.live.chat.bridge.handlers

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.rubberduck.live.chat.bridge.ChatBridgeHandler
import com.rubberduck.live.chat.bridge.ChatHandlerContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Returns the current editor selection (if any) as `{filePath, language, code}`
 * or [JsonNull] when no editor is focused or the selection is empty.
 *
 * Wraps in [ReadAction] so PSI/document reads don't violate threading
 * contracts.
 */
class SelectionHandler : ChatBridgeHandler() {
    override val name: String = "getSelection"

    override fun handle(payload: String, ctx: ChatHandlerContext): JsonElement {
        val editor = ctx.activeEditor() ?: return JsonNull
        return ReadAction.compute<JsonElement, RuntimeException> {
            val sel = editor.selectionModel
            val text = sel.selectedText
            if (text.isNullOrEmpty()) return@compute JsonNull
            val vf = FileDocumentManager.getInstance().getFile(editor.document)
            val path = vf?.path
            val language = vf?.extension?.lowercase() ?: "text"
            buildJsonObject {
                put("filePath", path)
                put("language", language)
                put("code", text)
            }
        }
    }
}
