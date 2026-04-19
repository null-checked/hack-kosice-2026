package com.rubberduck.live.chat.bridge

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.rubberduck.live.chat.SessionStore
import com.rubberduck.live.chat.bridge.handlers.FileSaveHandler
import com.rubberduck.live.chat.bridge.handlers.HistoryExportMarkdownHandler
import com.rubberduck.live.chat.bridge.handlers.HistoryHandler
import com.rubberduck.live.chat.bridge.handlers.HistorySearchHandler
import com.rubberduck.live.chat.bridge.handlers.HistoryToggleFavoriteHandler
import com.rubberduck.live.chat.bridge.handlers.SessionListHandler
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Phase 3 · Feature A — history search, favorites, markdown export tests.
 *
 * Uses [BasePlatformTestCase] so [SessionStore] can reach a live per-project
 * temp directory for its per-session JSON writes. The store is
 * project-scoped, so every test class gets an isolated sandbox — no extra
 * tempdir fiddling required.
 */
class HistoryFeatureATest : BasePlatformTestCase() {

    private lateinit var ctx: ChatHandlerContext

    override fun setUp() {
        super.setUp()
        ctx = ChatHandlerContext(project = project)
        // Wipe any sessions a prior test may have left around so the
        // search/list assertions have a known starting state.
        val dir = sessionsDir()
        if (Files.isDirectory(dir)) {
            Files.newDirectoryStream(dir).use { stream ->
                for (p in stream) runCatching { Files.deleteIfExists(p) }
            }
        }
    }

    // ------------------------------------------------------------- helpers

    private fun sessionsDir(): Path = Paths.get(project.basePath!!, ".rubberduck", "sessions")

    private fun saveSession(id: String, title: String, messages: String, favorite: Boolean = false) {
        val payload = """
            {"id":"$id","title":"$title","favorite":$favorite,"messages":$messages}
        """.trimIndent()
        val r = HistoryHandler().handle(payload, ctx).jsonObject
        assertEquals("save failed for $id", true, r["ok"]?.jsonPrimitive?.booleanOrNull)
    }

    // =========================================================================
    // historySearch
    // =========================================================================

    fun testHistorySearchEmptyQueryReturnsAll() {
        saveSession("feat-a-1", "Alpha", """[{"role":"user","content":"hello"}]""")
        saveSession("feat-a-2", "Beta", """[{"role":"user","content":"world"}]""")

        val resp = HistorySearchHandler().handle("""{"query":""}""", ctx).jsonObject
        val sessions = resp["sessions"]!!.jsonArray
        val ids = sessions.map { it.jsonObject["id"]!!.jsonPrimitive.content }
        assertTrue("expected both ids in results, got=$ids",
            ids.contains("feat-a-1") && ids.contains("feat-a-2"))
    }

    fun testHistorySearchFiltersAndSnippetsMessageBody() {
        saveSession(
            "feat-a-search-hit",
            "Refactor discussion",
            """[{"role":"user","content":"please rewrite the payload parser to be stricter"}]""",
        )
        saveSession(
            "feat-a-search-miss",
            "Unrelated",
            """[{"role":"user","content":"nothing here"}]""",
        )

        val resp = HistorySearchHandler().handle("""{"query":"payload parser"}""", ctx).jsonObject
        val sessions = resp["sessions"]!!.jsonArray
        assertEquals(1, sessions.size)
        val hit = sessions[0].jsonObject
        assertEquals("feat-a-search-hit", hit["id"]!!.jsonPrimitive.content)
        val snippet = hit["snippet"]!!.jsonPrimitive.content
        assertTrue("snippet should contain matched substring: '$snippet'",
            snippet.lowercase().contains("payload parser"))
    }

    fun testHistorySearchSortsFavoritesFirst() {
        // Two matching sessions — the non-favorite is written second so
        // `updated_at` is newer; favorite-first should still win the sort.
        saveSession("feat-a-fav", "Keyword alpha", """[{"role":"user","content":"x"}]""", favorite = true)
        Thread.sleep(5)
        saveSession("feat-a-plain", "Keyword beta", """[{"role":"user","content":"x"}]""", favorite = false)

        val resp = HistorySearchHandler().handle("""{"query":"keyword"}""", ctx).jsonObject
        val sessions = resp["sessions"]!!.jsonArray
        assertEquals(2, sessions.size)
        assertEquals("feat-a-fav", sessions[0].jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals(true, sessions[0].jsonObject["favorite"]!!.jsonPrimitive.booleanOrNull)
    }

    // =========================================================================
    // historyToggleFavorite
    // =========================================================================

    fun testHistoryToggleFavoritePersistsAcrossReload() {
        saveSession("feat-a-toggle", "T", """[{"role":"user","content":"x"}]""")
        val resp = HistoryToggleFavoriteHandler().handle(
            """{"id":"feat-a-toggle","favorite":true}""", ctx,
        ).jsonObject
        assertEquals(true, resp["ok"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(true, resp["favorite"]?.jsonPrimitive?.booleanOrNull)

        // Reload by going back through the store — simulates a cold start.
        val reloaded = SessionStore.getInstance(project).load("feat-a-toggle")!!
        assertEquals(true, reloaded["favorite"]!!.jsonPrimitive.booleanOrNull)
    }

    fun testHistoryToggleFavoriteIsIdempotent() {
        saveSession("feat-a-idemp", "T", """[{"role":"user","content":"x"}]""")
        val h = HistoryToggleFavoriteHandler()
        val r1 = h.handle("""{"id":"feat-a-idemp","favorite":true}""", ctx).jsonObject
        val r2 = h.handle("""{"id":"feat-a-idemp","favorite":true}""", ctx).jsonObject
        assertEquals(true, r1["ok"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(true, r2["ok"]?.jsonPrimitive?.booleanOrNull)
        val reloaded = SessionStore.getInstance(project).load("feat-a-idemp")!!
        assertEquals(true, reloaded["favorite"]!!.jsonPrimitive.booleanOrNull)
    }

    fun testHistoryToggleFavoriteRejectsBadPayload() {
        val bad = HistoryToggleFavoriteHandler().handle("garbage", ctx).jsonObject
        assertEquals(false, bad["ok"]?.jsonPrimitive?.booleanOrNull)
    }

    fun testSessionListIncludesFavoriteFlag() {
        saveSession("feat-a-list-fav", "T", """[{"role":"user","content":"x"}]""", favorite = true)
        val list = SessionListHandler().handle("null", ctx).jsonArray
        val row = list.map { it.jsonObject }.firstOrNull {
            it["id"]?.jsonPrimitive?.content == "feat-a-list-fav"
        } ?: error("expected list row for feat-a-list-fav")
        assertEquals(true, row["favorite"]?.jsonPrimitive?.booleanOrNull)
    }

    // =========================================================================
    // Migration — session file written without `favorite` still loads
    // =========================================================================

    fun testMigrationSessionFileMissingFavoriteLoadsAsFalseAndCanBeToggled() {
        // Hand-write a legacy session file — no `favorite` field at all.
        val dir = sessionsDir()
        Files.createDirectories(dir)
        val legacy = """
            {"id":"feat-a-legacy","title":"Legacy","created_at":1,"updated_at":2,
             "messages":[{"role":"user","content":"old data"}]}
        """.trimIndent()
        Files.writeString(dir.resolve("feat-a-legacy.json"), legacy)

        // List must now surface favorite=false without crashing.
        val list = SessionListHandler().handle("null", ctx).jsonArray
        val row = list.map { it.jsonObject }.firstOrNull {
            it["id"]?.jsonPrimitive?.content == "feat-a-legacy"
        } ?: error("expected legacy session in list")
        assertEquals(false, row["favorite"]?.jsonPrimitive?.booleanOrNull)

        // Toggle → true, reload, should persist.
        val resp = HistoryToggleFavoriteHandler().handle(
            """{"id":"feat-a-legacy","favorite":true}""", ctx,
        ).jsonObject
        assertEquals(true, resp["ok"]?.jsonPrimitive?.booleanOrNull)

        val reloaded = SessionStore.getInstance(project).load("feat-a-legacy")!!
        assertEquals(true, reloaded["favorite"]!!.jsonPrimitive.booleanOrNull)
    }

    // =========================================================================
    // historyExportMarkdown
    // =========================================================================

    fun testHistoryExportMarkdownProducesWellFormedDocWithCodeFences() {
        val messages = """[
            {"role":"user","content":"How do I write a hello world in Kotlin?","createdAt":"2025-01-01T00:00:00Z"},
            {"role":"assistant","content":"Use this:\n\n```kotlin\nfun main() { println(\"Hello\") }\n```\n","createdAt":"2025-01-01T00:00:05Z"}
        ]""".trimIndent()
        saveSession("feat-a-export", "Hello Kotlin", messages)

        val resp = HistoryExportMarkdownHandler().handle(
            """{"id":"feat-a-export"}""", ctx,
        ).jsonObject
        val md = resp["markdown"]!!.jsonPrimitive.content
        val filename = resp["filename"]!!.jsonPrimitive.content

        // Structure checks.
        assertTrue("title heading missing", md.startsWith("# Hello Kotlin"))
        assertTrue("user section missing", md.contains("## User"))
        assertTrue("assistant section missing", md.contains("## Assistant"))
        // Every message body appears verbatim.
        assertTrue("user body missing", md.contains("How do I write a hello world in Kotlin?"))
        // Fenced code block preserved verbatim.
        assertTrue("code fence missing", md.contains("```kotlin"))
        assertTrue("code body missing", md.contains("fun main() { println(\"Hello\") }"))

        // Filename shape.
        assertTrue("filename prefix", filename.startsWith("rubberduck-"))
        assertTrue("filename slug", filename.contains("hello-kotlin"))
        assertTrue("filename ext", filename.endsWith(".md"))
        // Timestamp chunk yyyyMMdd-HHmm = 13 chars between last '-' and '.md'.
        val stamp = filename.substringAfterLast('-').removeSuffix(".md")
        assertEquals("timestamp length: '$stamp'", 4, stamp.length) // HHmm
    }

    fun testHistoryExportMarkdownReturnsErrorForUnknownId() {
        val resp = HistoryExportMarkdownHandler().handle(
            """{"id":"does-not-exist"}""", ctx,
        ).jsonObject
        assertEquals(false, resp["ok"]?.jsonPrimitive?.booleanOrNull)
        assertEquals("not-found", resp["error"]?.jsonPrimitive?.content)
    }

    fun testExportFilenameBuilderSlugifiesAndTimestamps() {
        // `buildFilename` is a pure companion helper — drives the same
        // format the handler emits but lets us inject a fixed clock.
        val epochMs = 1_700_000_000_000L // 2023-11-14 22:13 UTC
        val name = HistoryExportMarkdownHandler.buildFilename(
            title = "My  Crazy / Title!!",
            fallbackId = "abc",
            nowMillis = epochMs,
        )
        assertTrue("got: $name", name.startsWith("rubberduck-my-crazy-title-"))
        assertTrue("got: $name", name.endsWith(".md"))
    }

    fun testExportFilenameFallsBackToIdWhenSlugEmpty() {
        val name = HistoryExportMarkdownHandler.buildFilename(
            title = "   ",
            fallbackId = "abc-def",
            nowMillis = 0L,
        )
        assertTrue("got: $name", name.startsWith("rubberduck-abc-def-"))
    }

    // =========================================================================
    // FileSaveHandler — non-UI pure helpers
    // =========================================================================

    fun testFileSaveHandlerSanitizesFilename() {
        val clean = FileSaveHandler.sanitizeFilename("rubberduck-Hello World / v1.md")
        // Whitespace + punctuation collapse to dashes; only `[A-Za-z0-9._-]` survive.
        assertTrue("unexpected: '$clean'", clean.matches(Regex("[A-Za-z0-9._-]+")))
        assertFalse("no raw slash", clean.contains('/'))
        assertFalse("no space", clean.contains(' '))
    }

    fun testFileSaveHandlerSanitizeFallsBackForBlank() {
        assertEquals("rubberduck-export", FileSaveHandler.sanitizeFilename(""))
        assertEquals("rubberduck-export", FileSaveHandler.sanitizeFilename("   "))
        // All-reject characters also fall back.
        assertEquals("rubberduck-export", FileSaveHandler.sanitizeFilename("/////"))
    }

    fun testFileSaveHandlerWriteBytesRoundTrip() {
        val tmp = Files.createTempFile("rubberduck-export-", ".md")
        try {
            val content = "# Hello\n\n```kotlin\nfun x() {}\n```\n"
            FileSaveHandler.writeBytes(tmp, content)
            val read = Files.readString(tmp)
            assertEquals(content, read)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    fun testFileSaveHandlerInvalidPayload() {
        val resp = FileSaveHandler().handle("garbage", ctx).jsonObject
        assertEquals(false, resp["saved"]?.jsonPrimitive?.booleanOrNull)
        assertEquals("invalid-payload", resp["error"]?.jsonPrimitive?.content)
    }
}
