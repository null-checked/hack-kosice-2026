package com.rubberduck.live.chat.bridge

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.Disposer
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Routes a raw `(action, payload)` pair coming from the JCEF webview to
 * the registered [ChatBridgeHandler]. **Pure and unit-testable** —
 * [dispatch] takes only strings, returns only JSON, and never touches
 * JCEF or the EDT.
 *
 * The JCEF wiring (one [JBCefJSQuery] per registered handler plus the
 * bootstrap `window.rubberduck.*` injection) lives in [install] and is
 * the only part that requires a live browser; `ChatBridge` is the only
 * production caller.
 */
class ChatBridgeDispatcher(
    private val ctx: ChatHandlerContext,
) : Disposable {

    private val log = Logger.getInstance(ChatBridgeDispatcher::class.java)
    private val handlers = LinkedHashMap<String, ChatBridgeHandler>()
    private val queries = mutableListOf<JBCefJSQuery>()
    private val methodSnippets = StringBuilder()

    /** View of currently registered action names. Exposed for tests & diagnostics. */
    val registeredActions: Set<String> get() = handlers.keys.toSet()

    /**
     * Register a handler. Duplicate names throw — this is a programmer
     * error, not a runtime condition, and catching it early avoids a
     * class of "which handler wins?" bugs.
     */
    fun register(handler: ChatBridgeHandler): ChatBridgeDispatcher {
        val existing = handlers.putIfAbsent(handler.name, handler)
        require(existing == null) {
            "Duplicate ChatBridgeHandler registration for '${handler.name}' " +
                "(existing: ${existing!!::class.simpleName}, new: ${handler::class.simpleName})"
        }
        return this
    }

    /** Convenience bulk register. */
    fun registerAll(vararg items: ChatBridgeHandler): ChatBridgeDispatcher {
        items.forEach { register(it) }
        return this
    }

    /**
     * Dispatch an incoming `(action, payload)` without any JCEF dependency.
     *
     * - Unknown action  → [DispatchResult.Error] with `code = "unknown-action"`.
     * - Handler throws → [DispatchResult.Error] with `code = "handler-exception"`
     *   (exception message is included but the stack is logged, not leaked).
     * - Success        → [DispatchResult.Ok] with the handler's verbatim JSON.
     *
     * Callers that need a raw JSON string (e.g. the JCEF shim) can use
     * [dispatchToJsonString] which serialises [DispatchResult] consistently.
     */
    fun dispatch(action: String, payload: String): DispatchResult {
        val handler = handlers[action]
            ?: return DispatchResult.Error(
                action = action,
                code = "unknown-action",
                message = "No handler registered for action '$action'",
            )
        return try {
            DispatchResult.Ok(action = action, value = handler.handle(payload, ctx))
        } catch (t: Throwable) {
            log.warn("Bridge.$action threw: ${t.message}", t)
            DispatchResult.Error(
                action = action,
                code = "handler-exception",
                // Don't leak stack traces, but surface the class/message.
                message = t.message ?: t::class.java.simpleName,
            )
        }
    }

    /** Dispatch and encode — used by the JCEF shim and by tests alike. */
    fun dispatchToJsonString(action: String, payload: String): String {
        return when (val result = dispatch(action, payload)) {
            is DispatchResult.Ok -> ChatBridgeHandler.JSON.encodeToString(
                JsonElement.serializer(),
                result.value,
            )
            is DispatchResult.Error -> ChatBridgeHandler.JSON.encodeToString(
                JsonElement.serializer(),
                buildJsonObject {
                    put("ok", JsonPrimitive(false))
                    put("error", JsonPrimitive(result.code))
                    put("message", JsonPrimitive(result.message))
                    put("action", JsonPrimitive(result.action))
                },
            )
        }
    }

    // ------------------------------------------------------------- JCEF glue

    /**
     * Wire every registered handler to a [JBCefJSQuery] on [browser] and
     * return the bootstrap JS that installs `window.rubberduck.<name>`
     * wrappers on every page load. Must be called after all [register]
     * calls and before the browser loads the chat page.
     *
     * Separated from [dispatch] so pure unit tests never have to stand up
     * a live browser.
     */
    fun install(browser: JBCefBrowser): String {
        for ((name, _) in handlers) {
            val query = JBCefJSQuery.create(browser as JBCefBrowserBase)
            query.addHandler { raw ->
                val payload = raw ?: ""
                when (val result = dispatch(name, payload)) {
                    is DispatchResult.Ok -> JBCefJSQuery.Response(
                        ChatBridgeHandler.JSON.encodeToString(
                            JsonElement.serializer(),
                            result.value,
                        )
                    )
                    is DispatchResult.Error -> JBCefJSQuery.Response(
                        null,
                        ERROR_CODE_BRIDGE,
                        result.message,
                    )
                }
            }
            queries += query
            val inject = query.inject(
                "JSON.stringify(arg === undefined ? null : arg)",
                "function(r){ try { resolve(r ? JSON.parse(r) : null); } catch(e) { resolve(r); } }",
                "function(ec,msg){ reject(new Error(msg || ('bridge error ' + ec))); }",
            )
            methodSnippets.append(
                """
                window.rubberduck.$name = function(arg) {
                  return new Promise(function(resolve, reject) {
                    $inject
                  });
                };
                """.trimIndent(),
            ).append('\n')
        }
        return """
            (function() {
              window.rubberduck = window.rubberduck || {};
              $methodSnippets
            })();
        """.trimIndent()
    }

    override fun dispose() {
        queries.forEach { runCatching { Disposer.dispose(it) } }
        queries.clear()
    }

    /** Result of a single [dispatch] call. Sealed so callers can exhaustively match. */
    sealed class DispatchResult {
        abstract val action: String

        data class Ok(override val action: String, val value: JsonElement) : DispatchResult()

        data class Error(
            override val action: String,
            val code: String,
            val message: String,
        ) : DispatchResult()
    }

    companion object {
        /** Matches the sentinel code we ship to JCEF's errorCallback on handler failure. */
        const val ERROR_CODE_BRIDGE: Int = -1
    }
}
