package com.rubberduck.live.net

import kotlinx.serialization.json.Json

/**
 * Single shared kotlinx.serialization [Json] instance configured for the
 * backend's wire expectations (ignore unknown fields, lenient, pretty-off).
 */
object JsonCodec {
    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
    }
}
