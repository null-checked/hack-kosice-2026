package com.rubberduck.live.chat

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.lang.ref.WeakReference

/**
 * Project-level holder of a weak reference to the currently-alive
 * [ChatBrowserPanel].
 *
 * `AnAction` subclasses reach the panel through this registry instead
 * of walking `ToolWindowManager → ContentManager → Content → Component`
 * on every update/invoke — both for performance (actions' `update()`
 * runs on every keystroke) and simplicity.
 *
 * The reference is weak so that panel disposal (tool window close /
 * IDE shutdown) doesn't leak the JCEF browser via the action system.
 */
@Service(Service.Level.PROJECT)
class ChatPanelRegistry {

    @Volatile
    private var ref: WeakReference<ChatBrowserPanel> = WeakReference(null)

    fun register(panel: ChatBrowserPanel) {
        ref = WeakReference(panel)
    }

    fun unregister(panel: ChatBrowserPanel) {
        val current = ref.get()
        if (current === panel || current == null) {
            ref = WeakReference(null)
        }
    }

    fun get(): ChatBrowserPanel? = ref.get()

    companion object {
        fun get(project: Project): ChatBrowserPanel? =
            project.getService(ChatPanelRegistry::class.java)?.get()

        fun getInstance(project: Project): ChatPanelRegistry =
            project.getService(ChatPanelRegistry::class.java)
    }
}
