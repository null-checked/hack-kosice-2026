package com.rubberduck.live.chat.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.rubberduck.live.chat.ChatBrowserPanel
import com.rubberduck.live.chat.ChatPanelRegistry

/**
 * Shared base for IDE actions that forward a single context-action name
 * into the chat web UI via `window.rubberduckUI.execContextAction(name)`.
 *
 * Mirrors the action-forwarding pattern from
 * `TO_BE_REMOVED/jetbrainsccgui/src/main/java/com/github/claudecodegui/action/ChatToolWindowAction.java`:
 * all keybindings and menu entries are thin Kotlin classes that resolve
 * the live panel via [ChatPanelRegistry] and dispatch a string command —
 * the IDE side never touches the DOM directly.
 */
abstract class ChatToolWindowAction : AnAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible =
            project != null && ChatPanelRegistry.get(project) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val panel = ChatPanelRegistry.get(project) ?: return
        perform(e, panel)
    }

    /** Subclass hook. */
    protected abstract fun perform(e: AnActionEvent, panel: ChatBrowserPanel)
}
