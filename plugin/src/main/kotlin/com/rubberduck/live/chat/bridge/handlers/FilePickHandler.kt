package com.rubberduck.live.chat.bridge.handlers

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.vfs.VfsUtil
import com.rubberduck.live.chat.bridge.ChatBridgeHandler
import com.rubberduck.live.chat.bridge.ChatHandlerContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Paths

/**
 * Opens the native file-picker dialog and, on selection, returns
 * `{filePath, content, language}` back to the web UI so the user can
 * attach an arbitrary project file to the chat.
 *
 * Runs on the EDT via `invokeAndWait` because [FileChooser.chooseFile]
 * must be called on the Swing thread.
 */
class FilePickHandler : ChatBridgeHandler() {
    override val name: String = "pickFile"

    override fun handle(payload: String, ctx: ChatHandlerContext): JsonElement {
        val descriptor = FileChooserDescriptor(true, false, false, false, false, false)
        var result: JsonElement = JsonNull
        ApplicationManager.getApplication().invokeAndWait {
            val vf = FileChooser.chooseFile(descriptor, ctx.project, null) ?: return@invokeAndWait
            val base = ctx.project.basePath?.let { Paths.get(it) }
            val rel = if (base != null) {
                runCatching { base.relativize(Paths.get(vf.path)).toString() }.getOrDefault(vf.path)
            } else vf.path
            val content = runCatching { VfsUtil.loadText(vf) }.getOrDefault("")
            val language = runCatching {
                FileTypeManager.getInstance().getFileTypeByFile(vf).name.lowercase()
            }.getOrNull()?.takeIf { it.isNotBlank() }
                ?: vf.extension?.lowercase()
                ?: "text"
            result = buildJsonObject {
                put("filePath", rel)
                put("content", content)
                put("language", language)
            }
        }
        return result
    }
}
