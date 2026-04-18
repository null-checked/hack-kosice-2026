package com.rubberduck.live.net

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.rubberduck.live.model.GenerateTestsRequest
import com.rubberduck.live.model.GenerateTestsResponse
import com.rubberduck.live.model.HealthResponse
import com.rubberduck.live.model.SocraticRequest
import com.rubberduck.live.model.SocraticResponse
import com.rubberduck.live.model.SuggestRefactorRequest
import com.rubberduck.live.model.SuggestRefactorResponse
import com.rubberduck.live.settings.RubberDuckSettings
import kotlinx.serialization.encodeToString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Tiny HTTP client over OkHttp that speaks the four JSON endpoints of the
 * RubberDuck Live backend. All calls are **blocking** — callers must dispatch
 * them on `Dispatchers.IO` (see [com.rubberduck.live.actions]).
 */
@Service(Service.Level.APP)
class BackendClient {

    private val log = Logger.getInstance(BackendClient::class.java)

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private fun base(): String = RubberDuckSettings.getInstance().backendApiBase

    /** `GET /health`. Optionally override the base URL (used from the settings test button). */
    @Throws(IOException::class)
    fun health(apiBase: String = base()): HealthResponse {
        val req = Request.Builder().url("$apiBase/health").get().build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("health HTTP ${resp.code}: $body")
            return JsonCodec.json.decodeFromString(HealthResponse.serializer(), body)
        }
    }

    @Throws(IOException::class)
    fun generateTests(req: GenerateTestsRequest): GenerateTestsResponse =
        post("/generate-tests", JsonCodec.json.encodeToString(req), GenerateTestsResponse.serializer())

    @Throws(IOException::class)
    fun suggestRefactor(req: SuggestRefactorRequest): SuggestRefactorResponse =
        post("/suggest-refactor", JsonCodec.json.encodeToString(req), SuggestRefactorResponse.serializer())

    @Throws(IOException::class)
    fun socraticQuestion(req: SocraticRequest): SocraticResponse =
        post("/socratic-question", JsonCodec.json.encodeToString(req), SocraticResponse.serializer())

    private fun <T> post(
        path: String,
        bodyJson: String,
        deserializer: kotlinx.serialization.DeserializationStrategy<T>,
    ): T {
        val request = Request.Builder()
            .url(base() + path)
            .post(bodyJson.toRequestBody(jsonMedia))
            .header("Content-Type", "application/json")
            .build()
        client.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                log.warn("POST $path failed HTTP ${resp.code}: ${body.take(300)}")
                throw IOException("POST $path HTTP ${resp.code}: ${body.take(300)}")
            }
            return JsonCodec.json.decodeFromString(deserializer, body)
        }
    }

    companion object {
        fun getInstance(): BackendClient =
            ApplicationManager.getApplication().getService(BackendClient::class.java)
    }
}
