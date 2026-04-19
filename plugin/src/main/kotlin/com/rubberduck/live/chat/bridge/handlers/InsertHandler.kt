package com.rubberduck.live.chat.bridge.handlers

import com.intellij.openapi.command.WriteCommandAction
import com.rubberduck.live.chat.bridge.ChatBridgeHandler
import com.rubberduck.live.chat.bridge.ChatHandlerContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * Inserts text at the active editor's caret position.
 *
 * Returns `true` on insert, `false` when no editor is focused. Wrapped
 * in [WriteCommandAction] so the insert is undo-grouped and fires all
 * the usual document-change listeners.
 */
class InsertHandler : ChatBridgeHandler() {
    override val name: String = "insertAtCursor"

    override fun handle(payload: String, ctx: ChatHandlerContext): JsonElement {
        val text = unquoteString(payload)
        val editor = ctx.activeEditor() ?: return JsonPrimitive(false)
        WriteCommandAction.runWriteCommandAction(ctx.project) {
            val caret = editor.caretModel.offset
            editor.document.insertString(caret, text)
            editor.caretModel.moveToOffset(caret + text.length)
        }
        return JsonPrimitive(true)
    }
}
