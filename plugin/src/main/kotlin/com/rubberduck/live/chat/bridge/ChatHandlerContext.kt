package com.rubberduck.live.chat.bridge

import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.rubberduck.live.chat.SessionFileResolver
import com.rubberduck.live.chat.SessionStore
import com.rubberduck.live.services.CodeIndexer
import com.rubberduck.live.services.SkillsManager
import com.rubberduck.live.settings.RubberDuckSettings

/**
 * Shared state & dependencies every handler needs. Modeled after cc-gui's
 * `HandlerContext` (Java) but translated to idiomatic Kotlin — services
 * resolve lazily so unit tests can inject fakes without standing up every
 * project service.
 *
 * Everything behind a function pointer (`activeEditorProvider`,
 * `notifier`, `clock`) is there so tests can swap in a deterministic
 * fake. Production code uses the defaults which call straight into the
 * IntelliJ platform.
 */
data class ChatHandlerContext(
    val project: Project,

    /** Returns the currently focused text editor, or `null`. Overridable for tests. */
    val activeEditorProvider: () -> Editor? = {
        FileEditorManager.getInstance(project).selectedTextEditor
    },

    /** Sends a balloon notification to the user. Overridable for tests. */
    val notifier: (message: String, type: NotificationType) -> Unit = { msg, type ->
        Notifications.Bus.notify(
            Notification("RubberDuck Live", "RubberDuck Chat", msg, type),
            project,
        )
    },

    /**
     * Monotonic millisecond source for rate-limiting. Production uses
     * [System.currentTimeMillis]; tests plug in a mutable counter so the
     * 200 ms window can be exercised deterministically.
     */
    val clock: () -> Long = { System.currentTimeMillis() },

    /** Settings accessor — indirection lets tests stub per-call. */
    val settingsProvider: () -> RubberDuckSettings = { RubberDuckSettings.getInstance() },

    /** Lazily resolved project service; safe to reuse. */
    val sessionStoreProvider: () -> SessionStore = { SessionStore.getInstance(project) },

    /** Resolver for redacted → [com.intellij.openapi.vfs.VirtualFile]. */
    val fileResolverProvider: () -> SessionFileResolver = {
        SessionFileResolver(project)
    },

    /** Skills service accessor; nullable because not all tests need it. */
    val skillsProvider: () -> SkillsManager = { SkillsManager.getInstance(project) },

    /** Code indexer accessor; used by [com.rubberduck.live.chat.bridge.handlers.SearchHandler]. */
    val indexerProvider: () -> CodeIndexer = { CodeIndexer.getInstance(project) },
) {

    // Convenience sugar so handlers read `ctx.settings` rather than `ctx.settingsProvider()`.
    val settings: RubberDuckSettings get() = settingsProvider()
    val sessionStore: SessionStore get() = sessionStoreProvider()
    val fileResolver: SessionFileResolver get() = fileResolverProvider()
    val skills: SkillsManager get() = skillsProvider()
    val indexer: CodeIndexer get() = indexerProvider()
    fun activeEditor(): Editor? = activeEditorProvider()
}
