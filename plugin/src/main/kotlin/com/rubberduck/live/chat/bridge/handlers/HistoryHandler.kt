package com.rubberduck.live.chat.bridge.handlers

import com.rubberduck.live.chat.bridge.ChatBridgeHandler
import com.rubberduck.live.chat.bridge.ChatHandlerContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Covers every chat-history action the web UI calls: `saveHistory`,
 * `listSessions`, `loadSession`, `deleteSession`, `renameSession`.
 *
 * The class implements the primary `saveHistory` action via [name] and
 * exposes each sibling op as its own inner class (registered separately
 * by [com.rubberduck.live.chat.bridge.ChatBridgeDispatcher]). One
 * handler-per-action keeps the dispatcher's routing map unambiguous
 * while still letting all five share this file.
 */
class HistoryHandler : ChatBridgeHandler() {
    override val name: String = "saveHistory"

    override fun handle(payload: String, ctx: ChatHandlerContext): JsonElement {
        val obj = parsePayloadObject(payload) ?: return buildJsonObject {
            put("ok", false)
            put("error", "invalid-payload")
        }
        return ctx.sessionStore.save(obj)
    }
}

/** Lists session headers — `id, title, created_at, updated_at, message_count`. */
class SessionListHandler : ChatBridgeHandler() {
    override val name: String = "listSessions"

    override fun handle(payload: String, ctx: ChatHandlerContext): JsonElement {
        return ctx.sessionStore.list()
    }
}

/** Loads a full session JSON, or [JsonNull] when missing / invalid. */
class SessionLoadHandler : ChatBridgeHandler() {
    override val name: String = "loadSession"

    override fun handle(payload: String, ctx: ChatHandlerContext): JsonElement {
        val id = extractId(payload)
        if (id.isBlank()) return JsonNull
        return ctx.sessionStore.load(id) ?: JsonNull
    }

    private fun extractId(rawPayload: String): String {
        val obj = parsePayloadObject(rawPayload)
        if (obj != null) return (obj["id"] as? JsonPrimitive)?.content.orEmpty()
        return unquoteString(rawPayload)
    }
}

/** Deletes a session file on disk. Returns `true` when removed. */
class SessionDeleteHandler : ChatBridgeHandler() {
    override val name: String = "deleteSession"

    override fun handle(payload: String, ctx: ChatHandlerContext): JsonElement {
        val obj = parsePayloadObject(payload)
        val id = if (obj != null) (obj["id"] as? JsonPrimitive)?.content.orEmpty()
        else unquoteString(payload)
        if (id.isBlank()) return JsonPrimitive(false)
        return JsonPrimitive(ctx.sessionStore.delete(id))
    }
}

/** Updates a session's title. Payload: `{id, title}`. */
class SessionRenameHandler : ChatBridgeHandler() {
    override val name: String = "renameSession"

    override fun handle(payload: String, ctx: ChatHandlerContext): JsonElement {
        val obj: JsonObject = parsePayloadObject(payload) ?: return JsonPrimitive(false)
        val id = (obj["id"] as? JsonPrimitive)?.content.orEmpty()
        val title = (obj["title"] as? JsonPrimitive)?.content.orEmpty()
        if (id.isBlank() || title.isBlank()) return JsonPrimitive(false)
        return JsonPrimitive(ctx.sessionStore.rename(id, title))
    }
}

// =========================================================================
// Phase 3 · Feature A — History search, favorites, markdown export
// =========================================================================

/**
 * Case-insensitive substring search over the saved session list.
 *
 * - Payload: `{ "query": string }` (empty / whitespace → return all).
 * - Response: `{ "sessions": [{id, title, snippet, updatedAt, favorite}] }`
 *   sorted `favorite` first, then by `updatedAt` descending.
 *
 * Matches against the session title and every message body. When the
 * match is in a message body, `snippet` is a ~120-char window centred
 * on the first hit; when it's in the title (or no query), `snippet`
 * defaults to the title. Bodies are deserialised lazily: [load] only
 * runs for sessions that need a deeper scan.
 */
class HistorySearchHandler : ChatBridgeHandler() {
    override val name: String = "historySearch"

    override fun handle(payload: String, ctx: ChatHandlerContext): JsonElement {
        val obj = parsePayloadObject(payload)
        val query = (obj?.get("query") as? JsonPrimitive)?.content.orEmpty().trim()
        val headers: JsonArray = ctx.sessionStore.list()

        data class Row(val id: String, val title: String, val snippet: String,
                       val updatedAt: Long, val favorite: Boolean)
        val rows = mutableListOf<Row>()

        val needle = query.lowercase()
        for (h in headers) {
            val row = (h as? JsonObject) ?: continue
            val id = (row["id"] as? JsonPrimitive)?.content ?: continue
            val title = (row["title"] as? JsonPrimitive)?.content ?: "Untitled"
            val updatedAt = (row["updated_at"] as? JsonPrimitive)?.longOrNull ?: 0L
            val favorite = (row["favorite"] as? JsonPrimitive)?.booleanOrNull ?: false

            if (needle.isEmpty()) {
                rows += Row(id, title, title, updatedAt, favorite)
                continue
            }

            // Title hit — cheap check, no full session load.
            if (title.lowercase().contains(needle)) {
                rows += Row(id, title, title, updatedAt, favorite)
                continue
            }

            // Scan messages only when the title didn't already match.
            val session = ctx.sessionStore.load(id) ?: continue
            val messages = session["messages"] as? JsonArray ?: continue
            val snippet = findSnippet(messages, needle) ?: continue
            rows += Row(id, title, snippet, updatedAt, favorite)
        }

        rows.sortWith(compareByDescending<Row> { it.favorite }.thenByDescending { it.updatedAt })

        val sessions = buildJsonArray {
            for (r in rows) add(buildJsonObject {
                put("id", r.id)
                put("title", r.title)
                put("snippet", r.snippet)
                put("updatedAt", r.updatedAt)
                put("favorite", r.favorite)
            })
        }
        return buildJsonObject { put("sessions", sessions) }
    }

    /** 120-char window centred on the first message body containing [needle], or `null`. */
    private fun findSnippet(messages: JsonArray, needle: String): String? {
        for (m in messages) {
            val obj = m as? JsonObject ?: continue
            val body = (obj["content"] as? JsonPrimitive)?.content ?: continue
            val idx = body.lowercase().indexOf(needle)
            if (idx < 0) continue
            val start = (idx - 60).coerceAtLeast(0)
            val end = (idx + needle.length + 60).coerceAtMost(body.length)
            val prefix = if (start > 0) "…" else ""
            val suffix = if (end < body.length) "…" else ""
            return prefix + body.substring(start, end).replace('\n', ' ').trim() + suffix
        }
        return null
    }
}

/**
 * Toggle the star flag on a saved session.
 *
 * - Payload: `{ "id": string, "favorite": boolean }`
 * - Response: `{ "ok": true, "favorite": boolean }` on success,
 *   `{ "ok": false, "error": "<code>" }` otherwise.
 *
 * Idempotent — setting the same value twice returns `ok:true` without a
 * meaningful content change (only `updated_at` bumps).
 */
class HistoryToggleFavoriteHandler : ChatBridgeHandler() {
    override val name: String = "historyToggleFavorite"

    override fun handle(payload: String, ctx: ChatHandlerContext): JsonElement {
        val obj = parsePayloadObject(payload) ?: return errorObj("invalid-payload")
        val id = (obj["id"] as? JsonPrimitive)?.content.orEmpty()
        if (id.isBlank()) return errorObj("missing-id")
        val favorite = (obj["favorite"] as? JsonPrimitive)?.booleanOrNull
            ?: return errorObj("missing-favorite")
        val ok = ctx.sessionStore.setFavorite(id, favorite)
        return if (!ok) errorObj("not-found")
        else buildJsonObject {
            put("ok", true)
            put("favorite", favorite)
        }
    }

    private fun errorObj(code: String): JsonObject = buildJsonObject {
        put("ok", false)
        put("error", code)
    }
}

/**
 * Serialise a session to Markdown for one-click export.
 *
 * - Payload: `{ "id": string }`
 * - Response: `{ "markdown": string, "filename": string }` on success,
 *   `{ "ok": false, "error": "<code>" }` otherwise.
 *
 * `filename` is `rubberduck-<slug-title>-<yyyyMMdd-HHmm>.md` (falls back
 * to the session id when the title slugifies to nothing). Markdown body
 * preserves fenced code blocks verbatim — message bodies are copied as-is
 * with no further escaping so triple-backtick blocks render correctly
 * when the file is opened in any Markdown viewer.
 */
class HistoryExportMarkdownHandler : ChatBridgeHandler() {
    override val name: String = "historyExportMarkdown"

    override fun handle(payload: String, ctx: ChatHandlerContext): JsonElement {
        val obj = parsePayloadObject(payload)
        val id = (obj?.get("id") as? JsonPrimitive)?.content ?: unquoteString(payload)
        if (id.isBlank()) return errorObj("missing-id")
        val session = ctx.sessionStore.load(id) ?: return errorObj("not-found")

        val title = (session["title"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
            ?: "Untitled"
        val messages = session["messages"] as? JsonArray ?: JsonArray(emptyList())

        val markdown = renderMarkdown(title, messages)
        val filename = buildFilename(title, id, System.currentTimeMillis())
        return buildJsonObject {
            put("markdown", markdown)
            put("filename", filename)
        }
    }

    private fun renderMarkdown(title: String, messages: JsonArray): String {
        val sb = StringBuilder()
        sb.append("# ").append(title).append("\n\n")
        sb.append("_Exported ").append(Instant.now().toString()).append("_\n\n")
        for (m in messages) {
            val obj = m as? JsonObject ?: continue
            val role = (obj["role"] as? JsonPrimitive)?.content ?: continue
            if (role != "user" && role != "assistant") continue
            val body = (obj["content"] as? JsonPrimitive)?.content ?: ""
            val createdAt = (obj["createdAt"] as? JsonPrimitive)?.content ?: ""
            val roleLabel = if (role == "user") "User" else "Assistant"
            sb.append("## ").append(roleLabel)
            if (createdAt.isNotBlank()) sb.append(" — ").append(createdAt)
            sb.append("\n")
            // Preserve fenced code blocks verbatim — copy body as-is.
            sb.append(body).append("\n\n")
        }
        return sb.toString().trimEnd() + "\n"
    }

    private fun errorObj(code: String): JsonObject = buildJsonObject {
        put("ok", false)
        put("error", code)
    }

    companion object {
        private val FILENAME_TS: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmm").withZone(ZoneId.systemDefault())

        /** Slugify [title] + timestamp. Falls back to [fallbackId] when the slug is empty. */
        fun buildFilename(title: String, fallbackId: String, nowMillis: Long): String {
            val slug = slugify(title).ifBlank { slugify(fallbackId).ifBlank { "session" } }
            val stamp = FILENAME_TS.format(Instant.ofEpochMilli(nowMillis))
            return "rubberduck-$slug-$stamp.md"
        }

        /** Lowercase, spaces→dash, strip anything outside `[a-z0-9-]`, clamp to 48. */
        fun slugify(raw: String): String {
            val lower = raw.lowercase()
            val sb = StringBuilder()
            var lastDash = false
            for (ch in lower) {
                val ok = when {
                    ch in 'a'..'z' || ch in '0'..'9' -> { lastDash = false; true }
                    ch == '-' || ch == '_' -> { lastDash = false; true }
                    ch.isWhitespace() -> {
                        if (!lastDash && sb.isNotEmpty()) { sb.append('-'); lastDash = true }
                        false
                    }
                    else -> false
                }
                if (ok) sb.append(ch)
                if (sb.length >= 48) break
            }
            return sb.toString().trim('-', '_')
        }
    }
}
