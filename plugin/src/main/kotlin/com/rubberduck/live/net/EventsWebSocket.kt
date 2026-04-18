package com.rubberduck.live.net

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.rubberduck.live.detector.EventBus
import com.rubberduck.live.detector.SessionContext
import com.rubberduck.live.model.DeveloperEvent
import com.rubberduck.live.settings.RubberDuckSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Persistent WebSocket bridge from the plugin to the backend's `/events`
 * endpoint. Responsibilities:
 *
 * - Dials the configured URL and sends the `hello` envelope.
 * - Bridges [EventBus] outbound: wraps each [DeveloperEvent] in the `event`
 *   envelope and ships it (**after** path redaction done upstream).
 * - 20 s heartbeat ping (per architecture §4 / phase 2 notes).
 * - Exponential reconnect with backoff 1s → 2s → 4s → 8s → 16s → 30s.
 *
 * All work happens on a dedicated [CoroutineScope] using `Dispatchers.IO`.
 */
@Service(Service.Level.APP)
class EventsWebSocket {

    private val log = Logger.getInstance(EventsWebSocket::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)
    private val socketRef = AtomicReference<WebSocket?>(null)
    @Volatile private var connected: Boolean = false
    @Volatile private var listenerJob: Job? = null
    @Volatile private var heartbeatJob: Job? = null

    val isConnected: Boolean get() = connected

    /** Start the connect/reconnect loop. Idempotent. */
    fun start() {
        if (!running.compareAndSet(false, true)) return
        scope.launch { connectLoop() }
        // Subscribe once; coroutine lives with the scope.
        listenerJob = scope.launch {
            EventBus.getInstance().events.collect { ev -> sendEvent(ev) }
        }
    }

    fun stop() {
        running.set(false)
        listenerJob?.cancel()
        heartbeatJob?.cancel()
        socketRef.getAndSet(null)?.close(1000, "plugin stopping")
        scope.cancel()
    }

    private suspend fun connectLoop() {
        var delayMs = 1000L
        while (running.get() && scope.isActive) {
            val url = RubberDuckSettings.getInstance().backendWsUrl
            try {
                val request = Request.Builder().url(url).build()
                log.info("WS connecting to $url")
                val listener = Listener()
                val ws = BackendClient.getInstance().client.newWebSocket(request, listener)
                socketRef.set(ws)
                // Wait for close / failure (listener toggles [connected]).
                while (connected.not() && running.get()) {
                    delay(100)
                    if (listener.failed.get()) break
                }
                if (listener.failed.get() || !connected) {
                    throw IllegalStateException("WS did not open")
                }
                // Start heartbeat.
                heartbeatJob?.cancel()
                heartbeatJob = scope.launch { heartbeatLoop(ws) }
                // Block until socket is closed/failed.
                listener.awaitClose()
                delayMs = 1000L // reset backoff after a healthy run
            } catch (t: Throwable) {
                log.debug("WS connect failed: ${t.message}")
            } finally {
                connected = false
                heartbeatJob?.cancel()
                socketRef.getAndSet(null)?.cancel()
            }
            if (!running.get()) break
            delay(delayMs)
            delayMs = (delayMs * 2).coerceAtMost(30_000L)
        }
    }

    private suspend fun heartbeatLoop(ws: WebSocket) {
        while (running.get() && connected) {
            delay(20_000)
            val ping = envelope("ping", JsonObject(emptyMap()))
            ws.send(ping)
        }
    }

    private fun sendHello(ws: WebSocket) {
        val payload = buildJsonObject {
            put("role", "plugin")
            put("session_id", SessionContext.getInstance().sessionId)
        }
        ws.send(envelope("hello", payload))
    }

    private fun sendEvent(event: DeveloperEvent) {
        val ws = socketRef.get() ?: return
        if (!connected) return
        if (!RubberDuckSettings.getInstance().state.sendEventsToDashboard) return
        val payload = JsonCodec.json.encodeToJsonElement(DeveloperEvent.serializer(), event) as JsonObject
        ws.send(envelope("event", payload))
    }

    private fun envelope(type: String, payload: JsonObject): String {
        val env = buildJsonObject {
            put("type", JsonPrimitive(type))
            put("timestamp", JsonPrimitive(System.currentTimeMillis()))
            put("payload", payload)
        }
        return JsonCodec.json.encodeToString(JsonObject.serializer(), env)
    }

    private inner class Listener : WebSocketListener() {
        val failed = AtomicBoolean(false)
        private val closeSignal = kotlinx.coroutines.CompletableDeferred<Unit>()

        suspend fun awaitClose() = closeSignal.await()

        override fun onOpen(webSocket: WebSocket, response: Response) {
            connected = true
            sendHello(webSocket)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            // Backend acks are informational; log at debug.
            log.debug("WS message: ${text.take(200)}")
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            log.debug("WS binary frame (${bytes.size} bytes)")
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            connected = false
            closeSignal.complete(Unit)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            failed.set(true)
            connected = false
            closeSignal.complete(Unit)
        }
    }

    companion object {
        fun getInstance(): EventsWebSocket =
            ApplicationManager.getApplication().getService(EventsWebSocket::class.java)
    }
}
