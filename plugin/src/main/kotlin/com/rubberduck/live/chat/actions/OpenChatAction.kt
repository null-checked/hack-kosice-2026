package com.rubberduck.live.chat.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.wm.ToolWindowManager

/**
 * Activates (and un-hides) the RubberDuck Live tool window, then selects
 * the Chat tab. Surfaced in `Tools` menu and the editor right-click menu
 * so users can jump into chat without hunting through the UI.
 */
class OpenChatAction : AnAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val manager = ToolWindowManager.getInstance(project)
        val window = manager.getToolWindow(TOOL_WINDOW_ID) ?: return
        window.activate {
            // Select the first content ("Chat" is placed as the leftmost tab).
            val cm = window.contentManager
            val chat = cm.contents.firstOrNull { it.displayName == "Chat" }
            if (chat != null) cm.setSelectedContent(chat)
        }
    }

    companion object {
        const val TOOL_WINDOW_ID = "RubberDuck Live"
    }
}
