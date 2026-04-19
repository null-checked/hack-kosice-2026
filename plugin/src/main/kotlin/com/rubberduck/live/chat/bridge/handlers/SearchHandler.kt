package com.rubberduck.live.chat.bridge.handlers

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.codeStyle.NameUtil
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.rubberduck.live.chat.bridge.ChatBridgeHandler
import com.rubberduck.live.chat.bridge.ChatHandlerContext
import com.rubberduck.live.util.PathRedactor
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Paths

/**
 * Fuzzy project-file search used by the web UI's `@` autocomplete.
 * Returns ranked `{path, score}` objects capped at [MAX_FILE_MATCHES].
 *
 * Ported verbatim from the pre-refactor `ChatBridge.searchFiles`; see
 * that commit for the rationale behind the `FilenameIndex` walk and
 * [NameUtil]-based scoring.
 */
class SearchFilesHandler : ChatBridgeHandler() {
    override val name: String = "searchFiles"

    override fun handle(payload: String, ctx: ChatHandlerContext): JsonElement {
        if (ctx.settings.state.privacyMode) return JsonArray(emptyList())

        val obj = parsePayloadObject(payload) ?: return JsonArray(emptyList())
        val query = (obj["query"] as? JsonPrimitive)?.content?.trim().orEmpty()
        val limit = ((obj["limit"] as? JsonPrimitive)?.content?.toIntOrNull() ?: MAX_FILE_MATCHES)
            .coerceIn(1, 100)

        data class Hit(val path: String, val score: Int)

        return ReadAction.compute<JsonElement, RuntimeException> {
            val project = ctx.project
            val scope = GlobalSearchScope.projectScope(project)
            val fileIndex = ProjectFileIndex.getInstance(project)
            val matcher = if (query.isNotBlank())
                NameUtil.buildMatcher("*$query").build()
            else null
            val base = project.basePath?.let { Paths.get(it) }
            val hits = ArrayList<Hit>()

            for (name in FilenameIndex.getAllFilenames(project)) {
                val vfiles = FilenameIndex.getVirtualFilesByName(name, scope)
                for (vf in vfiles) {
                    if (!vf.isValid || vf.isDirectory) continue
                    if (!fileIndex.isInContent(vf)) continue
                    if (vf.fileType.isBinary) continue
                    if (vf.length > 1_000_000) continue
                    val rel = if (base != null) {
                        runCatching { base.relativize(Paths.get(vf.path)).toString().replace('\\', '/') }
                            .getOrDefault(vf.path)
                    } else vf.path
                    val redacted = PathRedactor.redact(project, vf.path) ?: rel
                    val score = if (matcher == null) 0
                    else {
                        val s = maxOf(matcher.matchingDegree(redacted), matcher.matchingDegree(name))
                        if (s <= 0 && !redacted.contains(query, ignoreCase = true)) continue
                        s
                    }
                    hits.add(Hit(redacted, score))
                }
            }
            hits.sortWith(
                compareByDescending<Hit> { it.score }
                    .thenBy { it.path.length }
                    .thenBy { it.path },
            )
            val topN = hits.take(limit)
            buildJsonArray {
                for (h in topN) {
                    add(buildJsonObject {
                        put("path", h.path)
                        put("score", h.score)
                    })
                }
            }
        }
    }

    companion object {
        private const val MAX_FILE_MATCHES = 30
    }
}

/**
 * BM25-backed semantic search via [com.rubberduck.live.services.CodeIndexer].
 * Returns `[{path, score, snippet}, ...]`. Honors the privacy-mode
 * kill-switch just like [SearchFilesHandler].
 */
class SearchSemanticHandler : ChatBridgeHandler() {
    override val name: String = "searchSemantic"

    override fun handle(payload: String, ctx: ChatHandlerContext): JsonElement {
        if (ctx.settings.state.privacyMode) return JsonArray(emptyList())

        val obj = parsePayloadObject(payload) ?: return JsonArray(emptyList())
        val query = (obj["query"] as? JsonPrimitive)?.content?.trim().orEmpty()
        if (query.isEmpty()) return JsonArray(emptyList())
        val k = ((obj["k"] as? JsonPrimitive)?.content?.toIntOrNull()
            ?: (obj["limit"] as? JsonPrimitive)?.content?.toIntOrNull()
            ?: 5)
            .coerceIn(1, 50)
        val hits = runCatching { ctx.indexer.search(query, k) }.getOrElse { emptyList() }
        return buildJsonArray {
            for (h in hits) {
                add(buildJsonObject {
                    put("path", h.path)
                    put("score", h.score)
                    put("snippet", h.snippet)
                })
            }
        }
    }
}
