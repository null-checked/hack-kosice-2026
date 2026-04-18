package com.rubberduck.live.completion

import com.intellij.codeInsight.inline.completion.InlineCompletionEvent
import com.intellij.codeInsight.inline.completion.InlineCompletionProvider
import com.intellij.codeInsight.inline.completion.InlineCompletionProviderID
import com.intellij.codeInsight.inline.completion.InlineCompletionRequest
import com.intellij.codeInsight.inline.completion.elements.InlineCompletionGrayTextElement
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionSingleSuggestion
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionSuggestion
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.rubberduck.live.model.CompleteRequest
import com.rubberduck.live.net.BackendClient
import com.rubberduck.live.settings.RubberDuckSettings
import com.rubberduck.live.util.PathRedactor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Inline (ghost text) completion provider backed by the RubberDuck Live
 * backend's `POST /complete` endpoint.
 *
 * Gated behind [RubberDuckSettings.State.enableInlineCompletion] (ON by
 * default as of v0.3; flip off in Settings → Tools → RubberDuck Live) **and**
 * the privacy toggle (strict-privacy disables this provider entirely since
 * the prefix/suffix window crosses the loopback socket).
 *
 * Tabby-inspired reliability:
 * - **Dead-end skip:** we don't ship context that's unlikely to yield a
 *   useful completion (inside an open string literal, on a bare comment
 *   line, with < 3 chars of prefix).
 * - **In-memory cache:** completions are keyed on a short hash of the
 *   tail of the prefix + head of the suffix; repeated lookups (common when
 *   the caret bounces between positions) skip the backend round-trip.
 * - **INFO-level logs:** every decision (accepted, cached, skipped,
 *   backend error) is logged at INFO so users can diagnose "why didn't
 *   ghost text appear?" from the sandbox log without enabling debug.
 */
@Suppress("UnstableApiUsage")
class RubberDuckInlineCompletionProvider : InlineCompletionProvider {

    private val log = Logger.getInstance(RubberDuckInlineCompletionProvider::class.java)

    /** ctxKey → (completion, epoch-ms) for 10s TTL dedup. */
    private val cache = ConcurrentHashMap<String, CachedCompletion>()

    override val id: InlineCompletionProviderID = ID

    override fun isEnabled(event: InlineCompletionEvent): Boolean {
        val settings = RubberDuckSettings.getInstance().state
        if (!settings.enableInlineCompletion) {
            log.debug("isEnabled=false (setting off)")
            return false
        }
        if (settings.privacyMode) {
            log.info("Inline completion: disabled (privacy mode on)")
            return false
        }
        return true
    }

    override suspend fun getSuggestion(request: InlineCompletionRequest): InlineCompletionSuggestion {
        return InlineCompletionSingleSuggestion.build {
            emit(fetchCompletion(request))
        }
    }

    private suspend fun fetchCompletion(request: InlineCompletionRequest): InlineCompletionGrayTextElement {
        val snapshot = ReadAction.compute<Snapshot, RuntimeException> {
            val doc = request.document
            val text = doc.charsSequence.toString()
            val offset = request.endOffset.coerceIn(0, text.length)
            val vf = request.file.virtualFile
            val language = request.file.language.id.lowercase()
            val redacted = PathRedactor.redact(request.file.project, vf?.path)
            Snapshot(
                prefix = text.substring(0, offset),
                suffix = text.substring(offset),
                language = language,
                filePath = redacted,
            )
        }

        val skipReason = skipReasonFor(snapshot)
        if (skipReason != null) {
            log.info("Inline completion: skipped ($skipReason)")
            return InlineCompletionGrayTextElement("")
        }

        val trimmedPrefix = snapshot.prefix.takeLastContextWindow(MAX_PREFIX_CHARS)
        val trimmedSuffix = snapshot.suffix.take(MAX_SUFFIX_CHARS)
        val ctxKey = cacheKey(trimmedPrefix, trimmedSuffix)

        // Cache lookup — 10s TTL.
        val now = System.currentTimeMillis()
        cache[ctxKey]?.let { cached ->
            if (now - cached.ts < CACHE_TTL_MS) {
                log.info("Inline completion: cache hit key=${ctxKey.take(12)}…")
                return InlineCompletionGrayTextElement(cached.text)
            }
        }

        val apiRequest = CompleteRequest(
            language = snapshot.language,
            prefix = trimmedPrefix,
            suffix = trimmedSuffix,
            file_path = snapshot.filePath,
            max_tokens = 96,
        )
        log.info(
            "Inline completion: request language=${snapshot.language} " +
                "prefix_chars=${trimmedPrefix.length} suffix_chars=${trimmedSuffix.length}",
        )

        val completion = withContext(Dispatchers.IO) {
            runCatching { BackendClient.getInstance().complete(apiRequest) }
                .onFailure { log.info("Inline completion: backend error — ${it.message}") }
                .getOrNull()
        }
        val text = completion?.completion?.trim().orEmpty()
        if (text.isEmpty()) {
            log.info("Inline completion: empty response (model=${completion?.model ?: "n/a"})")
        } else {
            log.info(
                "Inline completion: accepted chars=${text.length} " +
                    "stop=${completion?.stop_reason} model=${completion?.model}",
            )
            cache[ctxKey] = CachedCompletion(text, now)
            pruneCacheIfLarge()
        }
        return InlineCompletionGrayTextElement(text)
    }

    /**
     * Return a short human-readable reason to skip requesting a completion,
     * or null if we should proceed. Heuristics inspired by Tabby's IntelliJ
     * client: don't waste requests on contexts that almost never yield
     * useful suggestions.
     */
    private fun skipReasonFor(s: Snapshot): String? {
        val prefix = s.prefix
        if (prefix.length < MIN_PREFIX_CHARS) return "prefix too short"
        val lineStart = prefix.lastIndexOf('\n') + 1
        val currentLine = prefix.substring(lineStart)

        // Inside an open string literal on the current line (unmatched ").
        val unmatchedDoubleQuotes = currentLine.count { it == '"' } % 2 != 0
        val unmatchedSingleQuotes = currentLine.count { it == '\'' } % 2 != 0
        if (unmatchedDoubleQuotes || unmatchedSingleQuotes) {
            // If the suffix on the same line closes it, allow — otherwise skip.
            val suffixLine = s.suffix.substringBefore('\n')
            val closesDouble = unmatchedDoubleQuotes && suffixLine.contains('"')
            val closesSingle = unmatchedSingleQuotes && suffixLine.contains('\'')
            if (!closesDouble && !closesSingle) return "inside open string"
        }

        // Bare comment line — "#" or "//" followed only by whitespace.
        val trimmed = currentLine.trimStart()
        if ((trimmed == "#" || trimmed.startsWith("# ") ||
                trimmed == "//" || trimmed.startsWith("// ")) &&
            trimmed.length < 6
        ) {
            return "empty comment"
        }

        return null
    }

    private fun cacheKey(prefix: String, suffix: String): String {
        // Hash only the tail of the prefix + head of suffix: caret moves
        // within the same block should often hit the same key.
        val pTail = prefix.takeLast(CACHE_CTX_PREFIX_CHARS)
        val sHead = suffix.take(CACHE_CTX_SUFFIX_CHARS)
        return (pTail.hashCode().toLong() shl 32 xor sHead.hashCode().toLong()).toString(16)
    }

    private fun pruneCacheIfLarge() {
        if (cache.size <= CACHE_MAX_ENTRIES) return
        val cutoff = System.currentTimeMillis() - CACHE_TTL_MS
        cache.entries.removeIf { it.value.ts < cutoff }
        // Still too big? drop oldest.
        if (cache.size > CACHE_MAX_ENTRIES) {
            cache.entries.sortedBy { it.value.ts }
                .take(cache.size - CACHE_MAX_ENTRIES)
                .forEach { cache.remove(it.key) }
        }
    }

    private data class Snapshot(
        val prefix: String,
        val suffix: String,
        val language: String,
        val filePath: String?,
    )

    private data class CachedCompletion(val text: String, val ts: Long)

    private fun String.takeLastContextWindow(n: Int): String =
        if (length <= n) this else substring(length - n)

    companion object {
        private val ID = InlineCompletionProviderID("com.rubberduck.live.inline")
        private const val MAX_PREFIX_CHARS = 4_000
        private const val MAX_SUFFIX_CHARS = 1_000
        private const val MIN_PREFIX_CHARS = 3
        private const val CACHE_TTL_MS = 10_000L
        private const val CACHE_MAX_ENTRIES = 128
        private const val CACHE_CTX_PREFIX_CHARS = 256
        private const val CACHE_CTX_SUFFIX_CHARS = 64
    }
}
