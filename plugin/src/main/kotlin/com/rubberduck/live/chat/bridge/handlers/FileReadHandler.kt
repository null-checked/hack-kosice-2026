package com.rubberduck.live.chat.bridge.handlers

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.LocalFileSystem
import com.rubberduck.live.chat.bridge.ChatBridgeHandler
import com.rubberduck.live.chat.bridge.ChatHandlerContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Serves file content to the chat web UI for the "attached files" panel.
 *
 * **M4 hardening:** validates that the resolved absolute path lives inside
 * `Project.basePath` *and* — when the file is reachable through VFS —
 * that [ProjectFileIndex.isInContent] returns true. This blocks both
 * classic path-traversal (`../../etc/passwd`) and the subtler case where
 * a symlinked file inside the project points *out* of the project root.
 *
 * Returns:
 *  - file contents as a [JsonPrimitive] on success,
 *  - [JsonNull] for missing / unreadable / out-of-project inputs.
 *
 * (Structured errors are not used here because the existing web UI
 * treats `null` as "not available" — preserving wire-format parity is a
 * Phase 1 non-negotiable.)
 */
class FileReadHandler : ChatBridgeHandler() {
    override val name: String = "readFile"

    private val log = Logger.getInstance(FileReadHandler::class.java)

    override fun handle(payload: String, ctx: ChatHandlerContext): JsonElement {
        val pathStr = unquoteString(payload)
        if (pathStr.isBlank()) return JsonNull

        val base = ctx.project.basePath?.let { Paths.get(it).toAbsolutePath().normalize() }
            ?: return JsonNull

        val candidate = runCatching {
            val p = Paths.get(pathStr)
            if (p.isAbsolute) p.toAbsolutePath().normalize()
            else base.resolve(p).toAbsolutePath().normalize()
        }.getOrElse {
            log.warn("readFile: path parse failure for '$pathStr': ${it.message}")
            return JsonNull
        }

        // --- M4 gate #1: lexical containment on the normalised path. ---------
        if (!candidate.startsWith(base)) {
            log.warn("readFile: rejected path outside project base: $candidate")
            return JsonNull
        }

        // --- M4 gate #2: VFS / ProjectFileIndex containment. -----------------
        // Catches symlinks whose target escapes the content roots even though
        // the lexical path happens to start with the project base (e.g. a
        // symlink at `<base>/link → /etc/passwd`). Mirrors SessionFileResolver.
        val vf = LocalFileSystem.getInstance().findFileByPath(
            candidate.toString().replace('\\', '/'),
        )
        if (vf != null) {
            val inContent = ReadAction.compute<Boolean, RuntimeException> {
                ProjectFileIndex.getInstance(ctx.project).isInContent(vf)
            }
            if (!inContent) {
                log.warn("readFile: rejected non-content file (possibly symlinked): $candidate")
                return JsonNull
            }
        }

        if (!Files.exists(candidate) || !Files.isRegularFile(candidate)) return JsonNull
        return try {
            JsonPrimitive(Files.readString(candidate, StandardCharsets.UTF_8))
        } catch (_: IOException) {
            JsonNull
        }
    }
}
