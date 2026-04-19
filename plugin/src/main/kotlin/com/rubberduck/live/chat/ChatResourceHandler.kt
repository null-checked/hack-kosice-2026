package com.rubberduck.live.chat

import com.intellij.openapi.diagnostic.Logger
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference

/**
 * Extracts the shared `chat-webui/` bundle from classpath resources
 * (`webui/chat/…`) into a per-JVM temporary directory so that JCEF can
 * load it via a plain `file://` URL.
 *
 * Why temp-dir extraction instead of classpath / custom scheme?
 *
 * JCEF's security model blocks `jar:` / classpath-style URLs for
 * `fetch()` and ES-module resolution, and IntelliJ's own custom-scheme
 * handler API is platform-version sensitive and a pain to wire
 * correctly for something as trivial as 4 static files. Extracting to
 * a temp dir is the pragmatic, proven pattern that several production
 * plugins (including the JetBrains Claude GUI reference we studied)
 * use for exactly this case.
 *
 * Extraction is idempotent: the first caller wins and every subsequent
 * call returns the cached directory.  We register a JVM shutdown hook
 * to best-effort cleanup.
 */
object ChatResourceHandler {

    private val log = Logger.getInstance(ChatResourceHandler::class.java)
    private val tempDirRef = AtomicReference<Path?>()

    /** Files copied out of `resources/webui/chat/` into the temp dir. */
    private val RESOURCES = listOf(
        "index.html",
        "app.js",
        "styles.css",
        "README.md",
        "vendor/marked.min.js",
        "vendor/highlight.min.js",
        "vendor/highlight.css",
        "vendor/dompurify.min.js",
    )

    /**
     * Ensure the bundle is extracted and return the root temp directory.
     * Safe to call multiple times; thread-safe.
     */
    @Synchronized
    fun ensureExtracted(): Path {
        tempDirRef.get()?.let {
            if (Files.exists(it.resolve("index.html"))) return it
        }
        val dir = Files.createTempDirectory("rubberduck-chat-")
        val classLoader = javaClass.classLoader
        for (rel in RESOURCES) {
            val target = dir.resolve(rel)
            Files.createDirectories(target.parent ?: dir)
            val stream = classLoader.getResourceAsStream("webui/chat/$rel")
                ?: error("Missing chat web UI resource: webui/chat/$rel — did you run `make sync-chat-webui`?")
            stream.use { src ->
                Files.newOutputStream(target).use { out -> src.copyTo(out) }
            }
        }
        tempDirRef.set(dir)
        log.info("Extracted chat-webui bundle to $dir")
        Runtime.getRuntime().addShutdownHook(Thread {
            runCatching { dir.toFile().deleteRecursively() }
        })
        return dir
    }

    /** Convenience: `file://` URL pointing at `index.html`. */
    fun indexFileUrl(): String {
        val dir = ensureExtracted()
        return dir.resolve("index.html").toUri().toString()
    }
}
