package com.rubberduck.live.util

import com.intellij.openapi.project.Project
import java.io.File
import java.nio.file.Paths

/**
 * Strips absolute filesystem paths down to project-relative paths before they
 * leave the IDE.
 *
 * The architecture doc §11 is explicit: the plugin **must** redact and the
 * backend does not re-redact. All outbound paths (events + DTO `file_path`)
 * must go through [redact].
 */
object PathRedactor {

    /**
     * Returns [absolutePath] normalized relative to [project] base path. Paths
     * outside the project are returned as `"<external>/<file_name>"` so the
     * user's home directory is never exposed.
     *
     * Handles both POSIX (`/Users/alice/...`) and Windows (`C:\Users\alice\...`)
     * conventions.
     */
    fun redact(project: Project?, absolutePath: String?): String? {
        if (absolutePath.isNullOrBlank()) return null
        val normalized = absolutePath.replace('\\', '/')
        val base = project?.basePath?.replace('\\', '/')

        if (base != null) {
            val baseTrimmed = base.trimEnd('/')
            if (normalized.startsWith("$baseTrimmed/")) {
                return normalized.removePrefix("$baseTrimmed/")
            }
            if (normalized == baseTrimmed) {
                return "."
            }
        }
        // Fallback: safe, no-home-dir-leak form.
        val name = runCatching { Paths.get(normalized).fileName?.toString() }
            .getOrNull()
            ?: File(normalized).name
        return "<external>/$name"
    }

    /** Convenience for callers that only have a plain absolute path string. */
    fun redactFile(project: Project?, file: File): String? = redact(project, file.absolutePath)
}
