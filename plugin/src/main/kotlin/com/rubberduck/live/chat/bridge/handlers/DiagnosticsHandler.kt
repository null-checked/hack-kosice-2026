package com.rubberduck.live.chat.bridge.handlers

import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.rubberduck.live.chat.bridge.ChatBridgeHandler
import com.rubberduck.live.chat.bridge.ChatHandlerContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Returns up to [MAX_DIAGNOSTICS] inspections / errors from the active
 * editor's [DaemonCodeAnalyzerImpl]. Drops diagnostics with empty
 * messages so the UI's "Attach diagnostics" chip never ships blanks.
 */
class DiagnosticsHandler : ChatBridgeHandler() {
    override val name: String = "getDiagnostics"

    override fun handle(payload: String, ctx: ChatHandlerContext): JsonElement {
        val editor = ctx.activeEditor() ?: return JsonArray(emptyList())
        return ReadAction.compute<JsonElement, RuntimeException> {
            val doc = editor.document
            val vf = FileDocumentManager.getInstance().getFile(doc)
            val filePath = vf?.path ?: ""
            val highlights = runCatching {
                DaemonCodeAnalyzerImpl.getHighlights(doc, null, ctx.project)
            }.getOrDefault(emptyList())
            buildJsonArray {
                for (h in highlights.take(MAX_DIAGNOSTICS)) {
                    val lineNum = if (h.startOffset in 0..doc.textLength) {
                        doc.getLineNumber(h.startOffset) + 1
                    } else 0
                    val severity = (h.severity?.name ?: "INFO").lowercase()
                    val message = h.description ?: h.toolTip ?: ""
                    if (message.isBlank()) continue
                    add(buildJsonObject {
                        put("filePath", filePath)
                        put("line", lineNum)
                        put("severity", severity)
                        put("message", message)
                    })
                }
            }
        }
    }

    companion object {
        /** Cap at 50 to match the monolith's behaviour and keep payloads small. */
        private const val MAX_DIAGNOSTICS = 50
    }
}
