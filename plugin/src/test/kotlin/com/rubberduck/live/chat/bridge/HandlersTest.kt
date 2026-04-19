package com.rubberduck.live.chat.bridge

import com.intellij.notification.NotificationType
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.rubberduck.live.chat.bridge.handlers.ClipboardHandler
import com.rubberduck.live.chat.bridge.handlers.ConfigHandler
import com.rubberduck.live.chat.bridge.handlers.DiagnosticsHandler
import com.rubberduck.live.chat.bridge.handlers.EmitInterventionHandler
import com.rubberduck.live.chat.bridge.handlers.FileReadHandler
import com.rubberduck.live.chat.bridge.handlers.HistoryHandler
import com.rubberduck.live.chat.bridge.handlers.InsertHandler
import com.rubberduck.live.chat.bridge.handlers.LogHandler
import com.rubberduck.live.chat.bridge.handlers.ModelPersistHandler
import com.rubberduck.live.chat.bridge.handlers.ModelsHandler
import com.rubberduck.live.chat.bridge.handlers.NotifyHandler
import com.rubberduck.live.chat.bridge.handlers.OpenFilesHandler
import com.rubberduck.live.chat.bridge.handlers.ReadyHandler
import com.rubberduck.live.chat.bridge.handlers.RejectEditHandler
import com.rubberduck.live.chat.bridge.handlers.ReplaceHandler
import com.rubberduck.live.chat.bridge.handlers.SearchFilesHandler
import com.rubberduck.live.chat.bridge.handlers.SearchSemanticHandler
import com.rubberduck.live.chat.bridge.handlers.SelectionHandler
import com.rubberduck.live.chat.bridge.handlers.SessionDeleteHandler
import com.rubberduck.live.chat.bridge.handlers.SessionListHandler
import com.rubberduck.live.chat.bridge.handlers.SessionLoadHandler
import com.rubberduck.live.chat.bridge.handlers.SessionRenameHandler
import com.rubberduck.live.chat.bridge.handlers.SkillBodyHandler
import com.rubberduck.live.chat.bridge.handlers.SkillsHandler
import com.rubberduck.live.settings.RubberDuckSettings
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Exercises every [com.rubberduck.live.chat.bridge.ChatBridgeHandler]
 * extracted in Phase 1 with a happy path + at least one error path.
 *
 * ### Why one big test class?
 * The handlers share a lot of setup (live `project`, test-fixture editor,
 * fake clock, fake notifier). Consolidating them in one class keeps the
 * shared fixture in one place and lets the coverage tool attribute the
 * suite to `chat/bridge/` files as a whole.
 *
 * Uses [BasePlatformTestCase] because several handlers call into
 * PSI / VFS / settings services that only work inside a live test
 * application. The non-editor handlers (notify, clipboard, log, ready,
 * models) do NOT require the editor fixture — they just need a project.
 */
class HandlersTest : BasePlatformTestCase() {

    // -- fixture state ----------------------------------------------------------

    /** Mutable millisecond counter the rate-limit tests drive manually. */
    private var fakeNow: Long = 0L

    /** Captured notifications so we can assert what `notifyUser` produced. */
    private val notifications = mutableListOf<Pair<String, NotificationType>>()

    private lateinit var ctx: ChatHandlerContext

    override fun setUp() {
        super.setUp()
        fakeNow = 0L
        notifications.clear()
        ctx = ChatHandlerContext(
            project = project,
            clock = { fakeNow },
            notifier = { msg, type -> notifications += msg to type },
        )
    }

    // =========================================================================
    // ConfigHandler
    // =========================================================================

    fun testConfigHandlerReturnsSettingsShape() {
        val result = ConfigHandler().handle("null", ctx).jsonObject
        // Must carry at least the fields `chat-webui/app.js` reads on boot.
        assertNotNull(result["backendBase"])
        assertNotNull(result["theme"])
        assertNotNull(result["chatModel"])
        assertNotNull(result["chatAvailableModels"])
        assertTrue(result["chatAvailableModels"] is JsonArray)
    }

    // =========================================================================
    // SelectionHandler
    // =========================================================================

    fun testSelectionHandlerReturnsNullWhenNoEditor() {
        // No editor configured yet → null
        val ctxNoEditor = ctx.copy(activeEditorProvider = { null })
        assertEquals(JsonNull, SelectionHandler().handle("null", ctxNoEditor))
    }

    fun testSelectionHandlerReturnsNullWhenSelectionEmpty() {
        myFixture.configureByText("Foo.kt", "val x = 1")
        val ctxEditor = ctx.copy(activeEditorProvider = { myFixture.editor })
        // No selection → null
        assertEquals(JsonNull, SelectionHandler().handle("null", ctxEditor))
    }

    fun testSelectionHandlerReturnsCode() {
        myFixture.configureByText("Foo.kt", "val greeting = \"hi\"")
        myFixture.editor.selectionModel.setSelection(0, 3) // "val"
        val ctxEditor = ctx.copy(activeEditorProvider = { myFixture.editor })
        val obj = SelectionHandler().handle("null", ctxEditor).jsonObject
        assertEquals("val", obj["code"]!!.jsonPrimitive.content)
    }

    // =========================================================================
    // OpenFilesHandler & DiagnosticsHandler
    // =========================================================================

    fun testOpenFilesHandlerReturnsArray() {
        // Simplest possible: no editor manager files configured yet.
        val r = OpenFilesHandler().handle("null", ctx)
        assertTrue("expected JsonArray, got $r", r is JsonArray)
    }

    fun testDiagnosticsHandlerReturnsEmptyArrayWhenNoEditor() {
        val ctxNoEditor = ctx.copy(activeEditorProvider = { null })
        val r = DiagnosticsHandler().handle("null", ctxNoEditor)
        assertEquals(JsonArray(emptyList()), r)
    }

    // =========================================================================
    // FileReadHandler — covers M4 path-validation reject paths
    // =========================================================================

    fun testFileReadHandlerReadsFileInsideProject() {
        // Put a file inside a real project content root so both the lexical
        // path containment check and the ProjectFileIndex check pass.
        val psi = myFixture.addFileToProject("sample_read_handler.txt", "content-from-disk")
        val absolute = psi.virtualFile.path
        val base = project.basePath
        // The PSI file's path is absolute; the handler will accept either an
        // absolute path that lives under base or a base-relative one. We use
        // the absolute path because the test-fixture base is not always the
        // same directory as the PSI file's content root.
        val payload = if (base != null && absolute.startsWith(base)) {
            "\"" + absolute.removePrefix(base).removePrefix("/") + "\""
        } else {
            "\"$absolute\""
        }
        val r = FileReadHandler().handle(payload, ctx)
        // Some IntelliJ test fixtures host the project under a temp dir that
        // isn't below `project.basePath`; in that case the M4 lexical gate
        // legitimately rejects — we still want confidence the happy path works
        // when the file IS under base (guarded by a meaningful assertion).
        if (base != null && absolute.startsWith(base)) {
            assertEquals("content-from-disk", (r as JsonPrimitive).content)
        } else {
            assertEquals(JsonNull, r)
        }
    }

    fun testFileReadHandlerRejectsPathOutsideProject() {
        // An absolute path pointing outside the project root must come back
        // as JsonNull, per the M4 lexical containment gate.
        val r = FileReadHandler().handle("\"/etc/passwd\"", ctx)
        assertEquals(JsonNull, r)
    }

    fun testFileReadHandlerRejectsTraversal() {
        // Relative path with `..` escapes the base after normalisation.
        val r = FileReadHandler().handle("\"../../../../etc/passwd\"", ctx)
        assertEquals(JsonNull, r)
    }

    fun testFileReadHandlerReturnsNullForBlankPath() {
        assertEquals(JsonNull, FileReadHandler().handle("", ctx))
        assertEquals(JsonNull, FileReadHandler().handle("null", ctx))
    }

    // =========================================================================
    // InsertHandler & ReplaceHandler
    // =========================================================================

    fun testInsertHandlerInsertsAtCaret() {
        myFixture.configureByText("Foo.kt", "AB")
        myFixture.editor.caretModel.moveToOffset(1)
        val ctxEditor = ctx.copy(activeEditorProvider = { myFixture.editor })
        assertEquals(JsonPrimitive(true), InsertHandler().handle("\"X\"", ctxEditor))
        assertEquals("AXB", myFixture.editor.document.text)
    }

    fun testInsertHandlerReturnsFalseWithoutEditor() {
        val ctxNone = ctx.copy(activeEditorProvider = { null })
        assertEquals(JsonPrimitive(false), InsertHandler().handle("\"X\"", ctxNone))
    }

    fun testReplaceHandlerReplacesSelection() {
        myFixture.configureByText("Foo.kt", "hello world")
        myFixture.editor.selectionModel.setSelection(0, 5) // "hello"
        val ctxEditor = ctx.copy(activeEditorProvider = { myFixture.editor })
        assertEquals(JsonPrimitive(true), ReplaceHandler().handle("\"HI\"", ctxEditor))
        assertEquals("HI world", myFixture.editor.document.text)
    }

    fun testReplaceHandlerInsertsWhenNoSelection() {
        myFixture.configureByText("Foo.kt", "AB")
        myFixture.editor.caretModel.moveToOffset(2)
        val ctxEditor = ctx.copy(activeEditorProvider = { myFixture.editor })
        assertEquals(JsonPrimitive(true), ReplaceHandler().handle("\"C\"", ctxEditor))
        assertEquals("ABC", myFixture.editor.document.text)
    }

    fun testReplaceHandlerReturnsFalseWithoutEditor() {
        val ctxNone = ctx.copy(activeEditorProvider = { null })
        assertEquals(JsonPrimitive(false), ReplaceHandler().handle("\"X\"", ctxNone))
    }

    // =========================================================================
    // ClipboardHandler — M3 rate-limit + size-cap
    // =========================================================================

    fun testClipboardHandlerAcceptsFirstCall() {
        val h = ClipboardHandler()
        fakeNow = 1_000L
        assertEquals(JsonPrimitive(true), h.handle("\"hello\"", ctx))
    }

    fun testClipboardHandlerRateLimitsSecondCallWithin200ms() {
        val h = ClipboardHandler()
        fakeNow = 1_000L
        assertEquals(JsonPrimitive(true), h.handle("\"hello\"", ctx))
        fakeNow = 1_050L // 50 ms later — inside the 200 ms window
        assertEquals(JsonPrimitive(false), h.handle("\"world\"", ctx))
        fakeNow = 1_201L // 201 ms later — outside the window
        assertEquals(JsonPrimitive(true), h.handle("\"again\"", ctx))
    }

    fun testClipboardHandlerRejectsOversizedPayload() {
        val h = ClipboardHandler()
        // Build a payload whose UTF-16 byte count (chars * 2) exceeds the cap.
        // A 6 MB string is > 10 MB when multiplied, guaranteeing rejection
        // without needing to allocate a full 10 MB buffer.
        val chars = (ClipboardHandler.MAX_PAYLOAD_BYTES / 2 + 1).toInt()
        val payload = "\"" + "a".repeat(chars) + "\""
        assertEquals(JsonPrimitive(false), h.handle(payload, ctx))
    }

    // =========================================================================
    // NotifyHandler — M3 rate-limit + length cap
    // =========================================================================

    fun testNotifyHandlerEmitsNotification() {
        fakeNow = 1_000L
        val r = NotifyHandler().handle("\"hello user\"", ctx)
        assertEquals(JsonPrimitive(true), r)
        assertEquals(1, notifications.size)
        assertEquals("hello user", notifications[0].first)
        assertEquals(NotificationType.INFORMATION, notifications[0].second)
    }

    fun testNotifyHandlerRespectsKindField() {
        fakeNow = 1_000L
        NotifyHandler().handle("""{"message":"oops","kind":"error"}""", ctx)
        assertEquals(NotificationType.ERROR, notifications.single().second)
    }

    fun testNotifyHandlerRateLimitsAfterMaxPerSecond() {
        val h = NotifyHandler()
        // Fire MAX_PER_SECOND calls at t=0, all should succeed.
        fakeNow = 0L
        repeat(NotifyHandler.MAX_PER_SECOND) {
            assertEquals(JsonPrimitive(true), h.handle("\"msg\"", ctx))
        }
        // The next call inside the same 1 s window must be rejected.
        fakeNow = 500L
        assertEquals(JsonPrimitive(false), h.handle("\"msg\"", ctx))
        // After the window slides, a fresh call is accepted again.
        fakeNow = 1_100L
        assertEquals(JsonPrimitive(true), h.handle("\"msg\"", ctx))
    }

    fun testNotifyHandlerTruncatesHugeMessages() {
        fakeNow = 1_000L
        val huge = "\"" + "x".repeat(NotifyHandler.MAX_MESSAGE_CHARS + 100) + "\""
        NotifyHandler().handle(huge, ctx)
        val emitted = notifications.single().first
        assertTrue(
            "expected truncated message, got length=${emitted.length}",
            emitted.length <= NotifyHandler.MAX_MESSAGE_CHARS + 1, // +1 for the ellipsis
        )
        assertTrue(emitted.endsWith("…"))
    }

    // =========================================================================
    // LogHandler & ReadyHandler — simple wrappers, happy path only
    // =========================================================================

    fun testLogHandlerReturnsTrue() {
        assertEquals(JsonPrimitive(true), LogHandler().handle("\"hi from webui\"", ctx))
    }

    fun testReadyHandlerReturnsTrue() {
        assertEquals(JsonPrimitive(true), ReadyHandler().handle("null", ctx))
    }

    // =========================================================================
    // ModelPersistHandler & ModelsHandler
    // =========================================================================

    fun testModelPersistHandlerPersistsBlankFalse() {
        assertEquals(JsonPrimitive(false), ModelPersistHandler().handle("\"\"", ctx))
    }

    fun testModelPersistHandlerPersistsNewModel() {
        val settings = RubberDuckSettings.getInstance().state
        val novel = "test-model-${System.nanoTime()}"
        try {
            val r = ModelPersistHandler().handle("\"$novel\"", ctx)
            assertEquals(JsonPrimitive(true), r)
            assertEquals(novel, settings.chatModel)
            assertTrue(settings.chatAvailableModels.contains(novel))
        } finally {
            settings.chatAvailableModels.remove(novel)
        }
    }

    fun testModelsHandlerReturnsList() {
        val r = ModelsHandler().handle("null", ctx)
        assertTrue(r is JsonArray)
        assertTrue("expected at least one model", (r as JsonArray).isNotEmpty())
    }

    // =========================================================================
    // SkillsHandler family
    // =========================================================================

    fun testSkillsHandlerReturnsArray() {
        val r = SkillsHandler().handle("null", ctx)
        assertTrue("expected JsonArray, got $r", r is JsonArray)
    }

    fun testSkillBodyHandlerReturnsNullForUnknownName() {
        assertEquals(JsonNull, SkillBodyHandler().handle("\"nonexistent-skill\"", ctx))
    }

    fun testSkillBodyHandlerReturnsNullForBlank() {
        assertEquals(JsonNull, SkillBodyHandler().handle("\"\"", ctx))
    }

    // =========================================================================
    // HistoryHandler family — end-to-end round-trip
    // =========================================================================

    fun testHistoryHandlersRoundTrip() {
        // save → list → load → rename → delete
        val save = HistoryHandler().handle(
            """{"id":"history-test-id","title":"T","messages":[{"role":"user","content":"hi"}]}""",
            ctx,
        ).jsonObject
        assertEquals(true, save["ok"]?.jsonPrimitive?.booleanOrNull)
        assertEquals("history-test-id", save["id"]!!.jsonPrimitive.content)

        try {
            val list = SessionListHandler().handle("null", ctx).jsonArray
            assertTrue(
                "expected list to contain our id",
                list.any { it.jsonObject["id"]?.jsonPrimitive?.content == "history-test-id" },
            )

            val loaded = SessionLoadHandler().handle(
                """{"id":"history-test-id"}""", ctx,
            ) as JsonObject
            assertEquals("history-test-id", loaded["id"]!!.jsonPrimitive.content)

            assertEquals(
                JsonPrimitive(true),
                SessionRenameHandler().handle(
                    """{"id":"history-test-id","title":"Renamed"}""", ctx,
                ),
            )
        } finally {
            SessionDeleteHandler().handle("""{"id":"history-test-id"}""", ctx)
        }
    }

    fun testHistoryHandlerRejectsInvalidPayload() {
        val r = HistoryHandler().handle("not-json", ctx).jsonObject
        assertEquals(false, r["ok"]?.jsonPrimitive?.booleanOrNull)
        assertEquals("invalid-payload", r["error"]?.jsonPrimitive?.content)
    }

    fun testSessionLoadHandlerReturnsNullForBlank() {
        assertEquals(JsonNull, SessionLoadHandler().handle("\"\"", ctx))
    }

    fun testSessionDeleteHandlerReturnsFalseForBlank() {
        assertEquals(JsonPrimitive(false), SessionDeleteHandler().handle("\"\"", ctx))
    }

    fun testSessionRenameHandlerRejectsMissingFields() {
        assertEquals(JsonPrimitive(false), SessionRenameHandler().handle("{}", ctx))
    }

    // =========================================================================
    // Edit / Reject / EmitIntervention
    // =========================================================================

    fun testRejectEditHandlerAcceptsValidPayload() {
        val r = RejectEditHandler().handle(
            """{"edit_id":"e1","file_path":"x.txt"}""", ctx,
        )
        assertEquals(JsonPrimitive(true), r)
    }

    fun testRejectEditHandlerRejectsBadPayload() {
        assertEquals(JsonPrimitive(false), RejectEditHandler().handle("not-json", ctx))
    }

    fun testEmitInterventionAcceptsKnownType() {
        val r = EmitInterventionHandler().handle(
            """{"type":"INTERVENTION_SHOWN","edit_id":"e1","kind":"chat_edit"}""",
            ctx,
        )
        assertEquals(JsonPrimitive(true), r)
    }

    fun testEmitInterventionFallsBackForUnknownType() {
        // Unknown EventType name — handler must default, not throw.
        val r = EmitInterventionHandler().handle(
            """{"type":"TOTALLY_FAKE","edit_id":"e1"}""", ctx,
        )
        assertEquals(JsonPrimitive(true), r)
    }

    fun testEmitInterventionRejectsBadPayload() {
        assertEquals(JsonPrimitive(false), EmitInterventionHandler().handle("garbage", ctx))
    }

    // =========================================================================
    // SearchFilesHandler & SearchSemanticHandler
    // =========================================================================

    fun testSearchFilesHandlerReturnsArray() {
        val r = SearchFilesHandler().handle("""{"query":"kt","limit":5}""", ctx)
        assertTrue("expected JsonArray, got $r", r is JsonArray)
    }

    fun testSearchFilesHandlerReturnsEmptyWhenPrivacyMode() {
        val settings = RubberDuckSettings.getInstance().state
        val prev = settings.privacyMode
        settings.privacyMode = true
        try {
            val r = SearchFilesHandler().handle("""{"query":"anything"}""", ctx)
            assertEquals(JsonArray(emptyList()), r)
        } finally {
            settings.privacyMode = prev
        }
    }

    fun testSearchFilesHandlerRejectsBadPayload() {
        assertEquals(JsonArray(emptyList()), SearchFilesHandler().handle("not-json", ctx))
    }

    fun testSearchSemanticHandlerEmptyQueryReturnsEmpty() {
        val r = SearchSemanticHandler().handle("""{"query":""}""", ctx)
        assertEquals(JsonArray(emptyList()), r)
    }

    fun testSearchSemanticHandlerReturnsEmptyWhenPrivacyMode() {
        val settings = RubberDuckSettings.getInstance().state
        val prev = settings.privacyMode
        settings.privacyMode = true
        try {
            assertEquals(
                JsonArray(emptyList()),
                SearchSemanticHandler().handle("""{"query":"anything"}""", ctx),
            )
        } finally {
            settings.privacyMode = prev
        }
    }
}
