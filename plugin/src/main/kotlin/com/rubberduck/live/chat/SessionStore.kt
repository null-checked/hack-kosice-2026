package com.rubberduck.live.chat

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Per-project JSON-file chat session store. Each conversation lives in a
 * single file at `$PROJECT_BASE/.rubberduck/sessions/<id>.json`.
 *
 * File shape:
 * ```json
 * {
 *   "id":         "<uuid>",
 *   "title":      "Rename app to application",
 *   "created_at": 1700000000000,
 *   "updated_at": 1700000001234,
 *   "model":      "gpt-4o-mini",
 *   "messages":   [ ... ]
 * }
 * ```
 *
 * ### Why one file per session?
 * The `advanced-skills` branch's legacy `saveHistory` did a whole-file
 * read-modify-write against a single `chat-history.jsonl` — any two
 * concurrent saves from different IDE windows would stomp each other.
 * One-file-per-session sidesteps this entirely: two saves only collide
 * if they target the *same* session, which in practice means the same
 * webview tab (single writer).
 *
 * ### Atomic writes
 * Every mutation serialises to a sibling `.tmp` file then uses
 * `ATOMIC_MOVE` to swap it into place. A half-written session is never
 * visible to [list] because the `.tmp` suffix is filtered out, and no
 * reader ever catches the file mid-write.
 *
 * ### Path safety
 * All caller-supplied ids are run through [isSafeId] before being joined
 * against the sessions directory — `..`, slashes, and Windows drive
 * letters are rejected. This means a webview compromise can't use the
 * bridge to read or overwrite arbitrary files.
 */
@Service(Service.Level.PROJECT)
class SessionStore(private val project: Project) {

    private val log = Logger.getInstance(SessionStore::class.java)

    // ------------------------------------------------------------------ Public

    /**
     * Persist a session. Accepts a partial object (anything omitted is
     * filled in with defaults or preserved from disk):
     *
     *  - `id`      — session uuid. Generated when blank.
     *  - `title`   — first-line summary. Derived from the first user message
     *                if blank and not preserved from a prior save.
     *  - `model`   — model id the turn ran against (stored verbatim).
     *  - `messages`— the full transcript array. Required for a meaningful save.
     *
     * Returns `{ok, id, title, created_at, updated_at}` on success or
     * `{ok:false, error}` on failure.
     */
    fun save(session: JsonObject): JsonObject {
        val dir = sessionsDir() ?: return errorObj("no-project-base")
        runCatching { Files.createDirectories(dir) }.getOrElse {
            return errorObj("mkdir-failed: ${it.message}")
        }

        val rawId = (session["id"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
        val id = when {
            rawId == null -> UUID.randomUUID().toString()
            !isSafeId(rawId) -> return errorObj("unsafe-id")
            else -> rawId
        }

        val target = dir.resolve("$id.json")
        val now = System.currentTimeMillis()

        // Preserve `created_at` across saves — first write sets it; subsequent
        // writes to the same id keep the original (so "started 2h ago" stays
        // accurate even after many updates).
        val existing = readJson(target)
        val createdAt: Long = (session["created_at"] as? JsonPrimitive)?.longOrNull
            ?: (existing?.get("created_at") as? JsonPrimitive)?.longOrNull
            ?: now

        val title = (session["title"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
            ?: (existing?.get("title") as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
            ?: deriveTitle(session["messages"] as? JsonArray)

        val messages = (session["messages"] as? JsonArray) ?: JsonArray(emptyList())
        val model = (session["model"] as? JsonPrimitive)?.content

        // Phase 3 — carry-over of the `favorite` flag. New payloads may set
        // it explicitly; otherwise we preserve whatever was on disk, which
        // means toggling favorite and then saving a new turn does NOT
        // silently flip it back to false. Missing-field on an old session
        // file is the documented "migrate to false" path.
        val favorite: Boolean = (session["favorite"] as? JsonPrimitive)?.booleanOrNull
            ?: (existing?.get("favorite") as? JsonPrimitive)?.booleanOrNull
            ?: false

        val full = buildJsonObject {
            put("id", id)
            put("title", title)
            put("created_at", createdAt)
            put("updated_at", now)
            put("favorite", favorite)
            if (model != null) put("model", model)
            put("messages", messages)
        }

        return try {
            writeAtomic(target, JSON.encodeToString(JsonObject.serializer(), full))
            buildJsonObject {
                put("ok", true)
                put("id", id)
                put("title", title)
                put("created_at", createdAt)
                put("updated_at", now)
            }
        } catch (t: Throwable) {
            log.warn("saveSession failed: ${t.message}")
            errorObj("write-failed: ${t.message}")
        }
    }

    /**
     * Return session headers sorted by `updated_at` descending. Only reads
     * a small slice of each file (id/title/timestamps) — the full messages
     * array is not deserialized until [load] is called.
     */
    fun list(): JsonArray {
        val dir = sessionsDir() ?: return JsonArray(emptyList())
        if (!Files.isDirectory(dir)) return JsonArray(emptyList())
        val summaries = mutableListOf<Pair<Long, JsonObject>>()
        runCatching {
            Files.newDirectoryStream(dir, "*.json").use { stream ->
                for (path in stream) {
                    val o = readJson(path) ?: continue
                    val id = (o["id"] as? JsonPrimitive)?.content
                        ?: path.fileName.toString().removeSuffix(".json")
                    if (!isSafeId(id)) continue
                    val title = (o["title"] as? JsonPrimitive)?.content ?: "Untitled"
                    val createdAt = (o["created_at"] as? JsonPrimitive)?.longOrNull ?: 0L
                    val updatedAt = (o["updated_at"] as? JsonPrimitive)?.longOrNull ?: createdAt
                    val msgs = (o["messages"] as? JsonArray)
                    val messageCount = msgs?.size ?: 0
                    // Phase 3 — Feature A: expose `favorite` on every list row
                    // so the rail can paint stars without a per-session load.
                    // Missing field (old sessions) reads as `false`.
                    val favorite = (o["favorite"] as? JsonPrimitive)?.booleanOrNull ?: false
                    val header = buildJsonObject {
                        put("id", id)
                        put("title", title)
                        put("created_at", createdAt)
                        put("updated_at", updatedAt)
                        put("message_count", messageCount)
                        put("favorite", favorite)
                    }
                    summaries += updatedAt to header
                }
            }
        }.onFailure { log.warn("listSessions failed: ${it.message}") }
        summaries.sortByDescending { it.first }
        return buildJsonArray { summaries.forEach { add(it.second) } }
    }

    /** Load the full session object, or `null` when missing / invalid id. */
    fun load(id: String): JsonObject? {
        if (!isSafeId(id)) return null
        val dir = sessionsDir() ?: return null
        return readJson(dir.resolve("$id.json"))
    }

    /** Delete the session file. Returns `false` on invalid id / missing file / IO error. */
    fun delete(id: String): Boolean {
        if (!isSafeId(id)) return false
        val dir = sessionsDir() ?: return false
        return runCatching { Files.deleteIfExists(dir.resolve("$id.json")) }.getOrElse { false }
    }

    /** Rename a session — preserves `id/created_at/messages/model`, bumps `updated_at`. */
    fun rename(id: String, title: String): Boolean {
        val trimmed = title.trim().take(200)
        if (trimmed.isEmpty()) return false
        val existing = load(id) ?: return false
        val rewritten = buildJsonObject {
            for ((k, v) in existing) {
                if (k != "title" && k != "updated_at") put(k, v)
            }
            put("title", trimmed)
            put("updated_at", System.currentTimeMillis())
        }
        val dir = sessionsDir() ?: return false
        return try {
            writeAtomic(dir.resolve("$id.json"), JSON.encodeToString(JsonObject.serializer(), rewritten))
            true
        } catch (t: Throwable) {
            log.warn("renameSession failed: ${t.message}")
            false
        }
    }

    /**
     * Flip the `favorite` flag on an existing session. Returns `false`
     * when the id is unsafe, missing on disk, or the write fails.
     *
     * Idempotent — setting the same value twice is a no-op (aside from
     * the `updated_at` bump). Preserves `id`, `title`, `created_at`,
     * `messages`, `model` verbatim; only `favorite` and `updated_at`
     * change on disk.
     *
     * Treats a session file missing the `favorite` key as `favorite=false`
     * (the documented migration behaviour) — the next write lands the
     * field in place.
     */
    fun setFavorite(id: String, favorite: Boolean): Boolean {
        val existing = load(id) ?: return false
        val rewritten = buildJsonObject {
            for ((k, v) in existing) {
                if (k != "favorite" && k != "updated_at") put(k, v)
            }
            put("favorite", favorite)
            put("updated_at", System.currentTimeMillis())
        }
        val dir = sessionsDir() ?: return false
        return try {
            writeAtomic(dir.resolve("$id.json"), JSON.encodeToString(JsonObject.serializer(), rewritten))
            true
        } catch (t: Throwable) {
            log.warn("setFavorite failed: ${t.message}")
            false
        }
    }

    // ----------------------------------------------------------------- Helpers

    private fun sessionsDir(): Path? {
        val base = project.basePath ?: return null
        return Paths.get(base, ".rubberduck", "sessions")
    }

    /**
     * A safe session id is a UUID-shaped token: letters, digits, dash,
     * underscore; between 1 and 64 characters. Rejects `..`, slashes, and
     * Windows drive letters — nothing caller-supplied ever escapes the
     * sessions directory.
     */
    private fun isSafeId(id: String): Boolean = ID_PATTERN.matches(id)

    private fun readJson(path: Path): JsonObject? {
        if (!Files.isRegularFile(path)) return null
        return runCatching {
            val text = Files.readString(path)
            JSON.parseToJsonElement(text).jsonObject
        }.getOrNull()
    }

    private fun writeAtomic(target: Path, content: String) {
        val tmp = target.resolveSibling(target.fileName.toString() + ".tmp")
        Files.writeString(
            tmp, content,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        )
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: Throwable) {
            // Fallback for filesystems without atomic-move support (rare on
            // modern Windows / macOS / Linux). Non-atomic replace is still
            // acceptable — a crashed write leaves a `.tmp` sibling that is
            // ignored by [list] and can be cleaned up manually.
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun deriveTitle(messages: JsonArray?): String {
        val src = messages ?: return "New chat"
        for (m in src) {
            val obj = m as? JsonObject ?: continue
            val role = (obj["role"] as? JsonPrimitive)?.content
            if (role != "user") continue
            val content = (obj["content"] as? JsonPrimitive)?.content ?: continue
            val head = content.trim().replace(Regex("\\s+"), " ").take(80)
            if (head.isNotEmpty()) return head
        }
        return "New chat"
    }

    private fun errorObj(msg: String): JsonObject = buildJsonObject {
        put("ok", false)
        put("error", msg)
    }

    companion object {
        private val ID_PATTERN = Regex("^[A-Za-z0-9_-]{1,64}$")

        private val JSON = Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
            explicitNulls = false
        }

        fun getInstance(project: Project): SessionStore =
            project.getService(SessionStore::class.java)
    }
}
