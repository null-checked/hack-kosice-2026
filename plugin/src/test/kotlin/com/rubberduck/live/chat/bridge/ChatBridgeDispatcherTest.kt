package com.rubberduck.live.chat.bridge

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.rubberduck.live.chat.bridge.ChatBridgeDispatcher.DispatchResult
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Pure-logic coverage for [ChatBridgeDispatcher]. Does not touch JCEF,
 * does not install any real handlers — uses the tiny in-class [FakeHandler]
 * fixture so the behaviour under test is entirely in this file.
 *
 * Extends [BasePlatformTestCase] for the IntelliJ test runner plumbing and
 * to give us a live `project` handle (required by [ChatHandlerContext]'s
 * non-null project argument). All assertions operate on the pure
 * [ChatBridgeDispatcher.dispatch] method, which is JCEF-free.
 */
class ChatBridgeDispatcherTest : BasePlatformTestCase() {

    private lateinit var ctx: ChatHandlerContext
    private lateinit var dispatcher: ChatBridgeDispatcher

    override fun setUp() {
        super.setUp()
        ctx = ChatHandlerContext(project = project)
        dispatcher = ChatBridgeDispatcher(ctx)
    }

    // -- routing ---------------------------------------------------------------

    fun testRoutesToRegisteredHandler() {
        val echo = FakeHandler("echo") { payload, _ -> JsonPrimitive("got:$payload") }
        dispatcher.register(echo)
        val r = dispatcher.dispatch("echo", "\"hi\"")
        assertTrue("expected Ok, got $r", r is DispatchResult.Ok)
        assertEquals(JsonPrimitive("got:\"hi\""), (r as DispatchResult.Ok).value)
    }

    fun testUnknownActionReturnsStructuredError() {
        val r = dispatcher.dispatch("missing", "{}")
        assertTrue(r is DispatchResult.Error)
        assertEquals("unknown-action", (r as DispatchResult.Error).code)
        assertEquals("missing", r.action)
        // Wire-format check — the JSON string form surfaces `ok: false` + `error`.
        val encoded = dispatcher.dispatchToJsonString("missing", "{}")
        assertTrue("expected structured error JSON, got: $encoded", encoded.contains("\"ok\":false"))
        assertTrue(encoded.contains("\"error\":\"unknown-action\""))
    }

    fun testHandlerExceptionIsCaughtAndReportedStructured() {
        dispatcher.register(FakeHandler("boom") { _, _ -> throw IllegalStateException("kaboom") })
        val r = dispatcher.dispatch("boom", "{}")
        assertTrue(r is DispatchResult.Error)
        val err = r as DispatchResult.Error
        assertEquals("handler-exception", err.code)
        // The message should mention the cause but never the stack trace.
        assertEquals("kaboom", err.message)
    }

    fun testMalformedJsonPayloadDoesNotBreakDispatcher() {
        // The dispatcher itself is payload-agnostic; it only cares that the
        // handler returns a JsonElement. A handler that blindly parses must
        // still not crash the dispatcher — the caught exception surfaces as
        // a structured error, not a stray throw.
        dispatcher.register(
            FakeHandler("parse") { payload, _ ->
                kotlinx.serialization.json.Json.parseToJsonElement(payload)
            },
        )
        val r = dispatcher.dispatch("parse", "{not valid json")
        assertTrue("expected Error for bad json, got $r", r is DispatchResult.Error)
        assertEquals("handler-exception", (r as DispatchResult.Error).code)
    }

    fun testSuccessfulDispatchReturnsHandlerOutputVerbatim() {
        val payload = buildJsonObject { put("key", "value") }
        dispatcher.register(FakeHandler("pass") { _, _ -> payload })
        val r = dispatcher.dispatch("pass", "{}")
        assertTrue(r is DispatchResult.Ok)
        assertSame(payload, (r as DispatchResult.Ok).value)
    }

    // -- registration invariants -----------------------------------------------

    fun testDuplicateRegistrationThrows() {
        dispatcher.register(FakeHandler("dup") { _, _ -> JsonPrimitive(1) })
        val ex = runCatching {
            dispatcher.register(FakeHandler("dup") { _, _ -> JsonPrimitive(2) })
        }.exceptionOrNull()
        assertNotNull("expected duplicate to throw", ex)
        assertTrue(
            "message should name the action: ${ex?.message}",
            ex?.message?.contains("'dup'") == true,
        )
    }

    fun testRegisteredActionsSetReflectsRegistration() {
        dispatcher.registerAll(
            FakeHandler("a") { _, _ -> JsonPrimitive(1) },
            FakeHandler("b") { _, _ -> JsonPrimitive(2) },
        )
        assertEquals(setOf("a", "b"), dispatcher.registeredActions)
    }

    fun testDispatchToJsonStringEncodesHandlerResultVerbatim() {
        dispatcher.register(
            FakeHandler("obj") { _, _ ->
                buildJsonObject { put("x", 1); put("y", 2) }
            },
        )
        val s = dispatcher.dispatchToJsonString("obj", "{}")
        // kotlinx.serialization is deterministic for object key order → safe to
        // substring-check.
        assertTrue(s, s.contains("\"x\":1"))
        assertTrue(s, s.contains("\"y\":2"))
    }

    // -- fixture ---------------------------------------------------------------

    private class FakeHandler(
        override val name: String,
        private val fn: (String, ChatHandlerContext) -> JsonElement,
    ) : ChatBridgeHandler() {
        override fun handle(payload: String, ctx: ChatHandlerContext): JsonElement = fn(payload, ctx)
    }
}
