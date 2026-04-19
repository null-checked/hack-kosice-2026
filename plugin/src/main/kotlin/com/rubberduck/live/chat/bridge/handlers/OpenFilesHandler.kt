package com.rubberduck.live.chat.bridge.handlers

import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.rubberduck.live.chat.bridge.ChatBridgeHandler
import com.rubberduck.live.chat.bridge.ChatHandlerContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Paths

/**
 * Returns the list of project-relative paths currently open in editor tabs.
 *
 * Covers the `getOpenFiles` bridge action. Grouped with [DiagnosticsHandler]
 * in the original monolith but separated here so each handler owns one
 * action (cleaner tests, clearer ownership).
 */
class OpenFilesHandler : ChatBridgeHandler() {
    override val name: String = "getOpenFiles"

    override fun handle(payload: String, ctx: ChatHandlerContext): JsonElement {
        return ReadAction.compute<JsonElement, RuntimeException> {
            val base = ctx.project.basePath?.let { Paths.get(it) }
            val files = FileEditorManager.getInstance(ctx.project).openFiles
            buildJsonArray {
                for (f in files) {
                    val p = f.path
                    val rel = if (base != null) {
                        runCatching { base.relativize(Paths.get(p)).toString() }.getOrDefault(p)
                    } else p
                    add(JsonPrimitive(rel))
                }
            }
        }
    }
}
