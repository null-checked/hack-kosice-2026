package com.rubberduck.live.chat.bridge.handlers

import com.intellij.openapi.command.WriteCommandAction
import com.rubberduck.live.chat.bridge.ChatBridgeHandler
import com.rubberduck.live.chat.bridge.ChatHandlerContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * Replaces the active selection with [text]; if no selection, falls back
 * to an insert at the caret so the "Copy to editor" button still does
 * something useful.
 */
class ReplaceHandler : ChatBridgeHandler() {
    override val name: String = "replaceSelection"

    override fun handle(payload: String, ctx: ChatHandlerContext): JsonElement {
        val text = unquoteString(payload)
        val editor = ctx.activeEditor() ?: return JsonPrimitive(false)
        WriteCommandAction.runWriteCommandAction(ctx.project) {
            val sel = editor.selectionModel
            if (sel.hasSelection()) {
                editor.document.replaceString(sel.selectionStart, sel.selectionEnd, text)
            } else {
                editor.document.insertString(editor.caretModel.offset, text)
            }
        }
        return JsonPrimitive(true)
    }
}
