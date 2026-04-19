package com.rubberduck.live.chat.bridge.handlers

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.rubberduck.live.chat.bridge.ChatBridgeHandler
import com.rubberduck.live.chat.bridge.ChatHandlerContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Bridge action `file_save_dialog` — pops the native save-file dialog and
 * writes the supplied content to the chosen path.
 *
 * - Payload: `{ defaultName: string, content: string, extension: string }`
 * - Response:
 *   - `{ saved: true,  path: "<absolute path>" }` on write success.
 *   - `{ saved: false }` when the user cancelled, the payload was invalid,
 *     or the write failed (a best-effort `error` code may be included).
 *
 * ### Threading
 * [FileChooserFactory] must be driven on the Swing EDT; [invokeAndWait]
 * guarantees that. The actual byte-write happens off the EDT — after the
 * dialog has returned the selected path, we drop back to the caller
 * thread (already a JCEF background worker) for [Files.writeString] so a
 * large file doesn't freeze the UI.
 *
 * Production code path is UI-bound, but [sanitizeFilename] and
 * [writeBytes] are exposed as pure helpers so the non-dialog logic is
 * unit-testable without a running IDE.
 */
class FileSaveHandler : ChatBridgeHandler() {
    override val name: String = "file_save_dialog"

    override fun handle(payload: String, ctx: ChatHandlerContext): JsonElement {
        val obj = parsePayloadObject(payload)
            ?: return buildJsonObject { put("saved", false); put("error", "invalid-payload") }

        val defaultName = (obj["defaultName"] as? JsonPrimitive)?.content.orEmpty()
        val content = (obj["content"] as? JsonPrimitive)?.content.orEmpty()
        val extension = (obj["extension"] as? JsonPrimitive)?.content.orEmpty()
            .ifBlank { "txt" }

        val safeName = sanitizeFilename(defaultName.ifBlank { "rubberduck-export.$extension" })

        var chosenPath: Path? = null
        ApplicationManager.getApplication().invokeAndWait {
            // `FileSaverDescriptor(title, description, *allowedExtensions)`
            val descriptor = FileSaverDescriptor(
                "Export Session",
                "Save the chat session to a file",
                extension,
            )
            val factory = FileChooserFactory.getInstance()
            val dialog = factory.createSaveFileDialog(descriptor, ctx.project)
            // A null baseDir lets the dialog open at its platform-default
            // location (last-used / home). We pass the sanitised default
            // filename so the user sees "rubberduck-<title>-<ts>.md" pre-filled.
            val wrapper = dialog.save(null as com.intellij.openapi.vfs.VirtualFile?, safeName)
            chosenPath = wrapper?.file?.toPath()
        }

        val path = chosenPath
            ?: return buildJsonObject { put("saved", false) }

        return try {
            writeBytes(path, content)
            buildJsonObject {
                put("saved", true)
                put("path", path.toAbsolutePath().toString())
            }
        } catch (t: Throwable) {
            buildJsonObject {
                put("saved", false)
                put("error", t.message ?: t::class.java.simpleName)
            }
        }
    }

    companion object {
        /**
         * Strip filesystem-hostile characters from a user-supplied filename.
         * Keeps `[A-Za-z0-9._-]`, collapses everything else to `-`, clamps
         * to 128 chars. Never returns an empty string (falls back to
         * `rubberduck-export`). Pure — safe to call off any thread.
         */
        fun sanitizeFilename(raw: String): String {
            if (raw.isBlank()) return "rubberduck-export"
            val sb = StringBuilder(raw.length)
            var lastDash = false
            for (ch in raw) {
                val keep = ch.isLetterOrDigit() || ch == '.' || ch == '_' || ch == '-'
                if (keep) { sb.append(ch); lastDash = false }
                else if (!lastDash && sb.isNotEmpty()) { sb.append('-'); lastDash = true }
                if (sb.length >= 128) break
            }
            val trimmed = sb.toString().trim('-', '.', '_')
            return trimmed.ifBlank { "rubberduck-export" }
        }

        /**
         * Write [content] to [target] using UTF-8. Extracted so tests can
         * exercise the write path without a dialog. Creates parent
         * directories lazily; truncates any existing file.
         */
        fun writeBytes(target: Path, content: String): Path {
            val parent = target.parent
            if (parent != null && !Files.isDirectory(parent)) {
                Files.createDirectories(parent)
            }
            Files.writeString(target, content)
            return target
        }

        /** Resolve a filename relative to a base dir — helper mirror of real-dialog output. */
        @Suppress("unused")
        fun resolveUnder(base: String, filename: String): Path =
            Paths.get(base).resolve(sanitizeFilename(filename))
    }
}
