package com.rubberduck.live.services

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.util.Alarm
import com.rubberduck.live.model.IndexBuildRequest
import com.rubberduck.live.model.IndexFile
import com.rubberduck.live.model.IndexHit
import com.rubberduck.live.model.IndexSearchRequest
import com.rubberduck.live.net.BackendClient
import com.rubberduck.live.settings.RubberDuckSettings
import com.rubberduck.live.util.PathRedactor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Project-level service that maintains a semantic file index in the
 * backend by walking the project's content roots, reading each file,
 * and POSTing the corpus to `/index/build`. Queries are answered by
 * `/index/search` — the web UI reaches it through the
 * [com.rubberduck.live.chat.ChatBridge] `searchSemantic` method.
 *
 * Lifecycle:
 *  - First build is kicked off from [RubberDuckStartup] on project open.
 *  - A [BulkFileListener] subscribes to the project message bus; any
 *    content-change event schedules a debounced re-index (1 s) so that
 *    a big refactor with dozens of saves doesn't thrash the backend.
 *  - All I/O runs on the pooled thread — VFS reads are wrapped in
 *    [ReadAction.compute] so we never block on the EDT.
 *
 * Privacy: [RubberDuckSettings.State.privacyMode] short-circuits every
 * path (build and search). When on, the corpus stays empty and searches
 * return zero hits — consistent with the rest of the plugin's outbound
 * gate.
 *
 * Scope cuts (per the unified-chat plan):
 *  - Binary files, directories, and anything over [MAX_FILE_BYTES] are
 *    skipped — they'd waste tokens and won't meaningfully rank anyway.
 *  - The corpus is capped at [MAX_DOC_COUNT] files on very large monorepos
 *    so the backend's in-memory BM25 footprint stays bounded.
 *  - No persistence. Index is rebuilt on project open every time; this
 *    is fine for < 2 k files. A future variant would key a sqlite-vec
 *    index by project path.
 */
@Service(Service.Level.PROJECT)
class CodeIndexer(private val project: Project) : Disposable {

    private val log = Logger.getInstance(CodeIndexer::class.java)
    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)
    private val indexing = AtomicBoolean(false)
    private var lastIndexedCount = 0
    private var listenerInstalled = false

    // ------------------------------------------------------ Lifecycle

    /**
     * Kick off an initial build and wire the VFS listener for debounced
     * re-indexing. Safe to call more than once — the listener is installed
     * at most once, and a build already in flight is not duplicated.
     */
    fun onStartup() {
        if (!listenerInstalled) {
            installVfsListener()
            listenerInstalled = true
        }
        schedule(delayMs = 0)
    }

    /** Force a rebuild immediately. Exposed for the settings Test button. */
    fun rebuildNow() = schedule(delayMs = 0)

    /**
     * Run a semantic search against the current corpus. Returns an empty
     * list when strict privacy is enabled or the backend is unreachable —
     * callers never see exceptions, which matches the UI's best-effort
     * posture for autocomplete.
     */
    fun search(query: String, k: Int = 5): List<IndexHit> {
        if (RubberDuckSettings.getInstance().state.privacyMode) return emptyList()
        return try {
            BackendClient.getInstance().indexSearch(IndexSearchRequest(query, k)).hits
        } catch (t: Throwable) {
            log.warn("indexSearch failed: ${t.message}")
            emptyList()
        }
    }

    /** Document count last successfully pushed. Used by UI badges. */
    fun indexedCount(): Int = lastIndexedCount

    override fun dispose() {
        runCatching { alarm.cancelAllRequests() }
    }

    // ------------------------------------------------------ Internals

    private fun installVfsListener() {
        // VFS listener via the application message bus — filter to this
        // project's content roots inside the handler so the subscription
        // remains cheap even for unrelated-project edits.
        val conn = ApplicationManager.getApplication().messageBus.connect(this)
        conn.subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                if (project.isDisposed) return
                val fileIndex = ReadAction.compute<ProjectFileIndex, RuntimeException> {
                    ProjectFileIndex.getInstance(project)
                }
                val hit = events.any { ev ->
                    val f = ev.file ?: return@any false
                    ReadAction.compute<Boolean, RuntimeException> {
                        !f.isDirectory && fileIndex.isInContent(f)
                    }
                }
                if (hit) schedule(delayMs = DEBOUNCE_MS)
            }
        })
    }

    private fun schedule(delayMs: Int) {
        if (RubberDuckSettings.getInstance().state.privacyMode) {
            log.debug("CodeIndexer: privacy mode on — skipping build")
            return
        }
        alarm.cancelAllRequests()
        alarm.addRequest({ runBuild() }, delayMs)
    }

    /**
     * Pooled-thread build body. Enumerates the project's content files,
     * reads each one via [VfsUtilCore.loadText], redacts the path, then
     * POSTs the whole corpus to `/index/build`.
     */
    private fun runBuild() {
        if (project.isDisposed) return
        if (!indexing.compareAndSet(false, true)) return
        try {
            val docs = ReadAction.compute<List<IndexFile>, RuntimeException> {
                if (project.isDisposed) return@compute emptyList()
                val fileIndex = ProjectFileIndex.getInstance(project)
                val collected = ArrayList<IndexFile>()
                fileIndex.iterateContent { vf ->
                    if (collected.size >= MAX_DOC_COUNT) return@iterateContent false
                    if (!vf.isValid || vf.isDirectory) return@iterateContent true
                    if (vf.fileType.isBinary) return@iterateContent true
                    if (vf.length > MAX_FILE_BYTES) return@iterateContent true
                    val content = loadText(vf) ?: return@iterateContent true
                    val redacted = PathRedactor.redact(project, vf.path) ?: vf.path
                    collected.add(IndexFile(path = redacted, content = content))
                    true
                }
                collected
            }
            if (docs.isEmpty()) {
                log.info("CodeIndexer: corpus empty for ${project.name}")
                return
            }
            val resp = BackendClient.getInstance().indexBuild(IndexBuildRequest(docs))
            lastIndexedCount = resp.indexed
            log.info("CodeIndexer: pushed ${docs.size} files; backend acked indexed=${resp.indexed}")
        } catch (t: Throwable) {
            log.warn("CodeIndexer: build failed — ${t.message}")
        } finally {
            indexing.set(false)
        }
    }

    private fun loadText(vf: VirtualFile): String? {
        return try {
            VfsUtilCore.loadText(vf)
        } catch (_: Throwable) {
            null
        }
    }

    companion object {
        /** Cap on corpus size — large monorepos gracefully truncate. */
        private const val MAX_DOC_COUNT = 2_000

        /** Per-file byte cap — skip huge generated files / lockfiles. */
        private const val MAX_FILE_BYTES = 1_000_000L

        /** Debounce window between save and re-index kicks. */
        private const val DEBOUNCE_MS = 1_000

        fun getInstance(project: Project): CodeIndexer =
            project.getService(CodeIndexer::class.java)
    }
}
