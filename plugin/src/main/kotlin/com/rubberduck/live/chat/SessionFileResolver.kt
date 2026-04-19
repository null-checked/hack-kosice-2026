package com.rubberduck.live.chat

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import java.util.concurrent.ConcurrentHashMap

/**
 * Round-trips redacted (project-relative) paths back to real [VirtualFile]s.
 *
 * Code edits that arrive from the backend carry file paths in the redacted form
 * produced by [com.rubberduck.live.util.PathRedactor.redact] (per ARCHITECTURE
 * §11 — paths never leave the IDE unredacted). The chat UI needs to turn that
 * redacted string back into a [VirtualFile] when the user clicks "Preview &
 * Apply", without ever trusting absolute paths coming from the wire.
 *
 * Strategy:
 *  1. Remember any explicit `@`-mention the user made in this session — that's
 *     the authoritative mapping (fastest, and lets mentions survive path
 *     renames in principle).
 *  2. Fall back to `project.basePath + redactedPath` and ask [LocalFileSystem]
 *     for the matching file. This is how most edits resolve, since the backend
 *     just echoes back the redacted path of the file the user is editing.
 *
 * Thread-safe — the mention cache is a [ConcurrentHashMap] so the Swing EDT
 * and background coroutines can call it without locking.
 */
class SessionFileResolver(private val project: Project) {

    private val mentions = ConcurrentHashMap<String, VirtualFile>()

    /** Record that [redactedPath] was explicitly mentioned and resolved to [vf]. */
    fun rememberMention(redactedPath: String, vf: VirtualFile) {
        if (redactedPath.isNotBlank()) {
            mentions[redactedPath] = vf
        }
    }

    /**
     * Resolve [redactedPath] back to a [VirtualFile], or `null` if the file
     * doesn't exist **inside the project's content roots**. Every resolved
     * file — from the mention cache or the fallback — is gated on
     * [ProjectFileIndex.isInContent] so a crafted redacted path cannot reach
     * files outside the project.
     */
    fun resolve(redactedPath: String): VirtualFile? {
        if (redactedPath.isBlank()) return null

        val index = ProjectFileIndex.getInstance(project)

        mentions[redactedPath]?.let { vf ->
            if (vf.isValid && index.isInContent(vf)) return vf
        }

        val base = project.basePath?.replace('\\', '/')?.trimEnd('/') ?: return null
        val normalized = redactedPath.replace('\\', '/').trimStart('/')
        val absolute = "$base/$normalized"
        val vf = LocalFileSystem.getInstance().findFileByPath(absolute) ?: return null
        return if (index.isInContent(vf)) vf else null
    }

    /** Drop all remembered mentions — useful when the chat is cleared. */
    fun clear() {
        mentions.clear()
    }
}
