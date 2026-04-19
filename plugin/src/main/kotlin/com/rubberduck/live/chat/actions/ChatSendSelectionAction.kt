package com.rubberduck.live.chat.actions

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.wm.ToolWindowManager
import com.rubberduck.live.chat.ChatBrowserPanel

/**
 * Right-click → "Ask RubberDuck About Selection".
 *
 * Attaches the current editor selection as a context chip **and** sends a
 * default "explain" prompt, so the common "I have no idea what this code
 * does" flow is a single gesture rather than three clicks + typing.
 */
class ChatSendSelectionAction : ChatToolWindowAction() {
    override fun perform(e: AnActionEvent, panel: ChatBrowserPanel) {
        val project = e.project
        if (project != null) {
            val tw = ToolWindowManager.getInstance(project).getToolWindow(OpenChatAction.TOOL_WINDOW_ID)
            tw?.activate(null)
        }
        panel.executeContextAction("attach-selection")
        // Small delay via a second JS statement so attach-selection has time to
        // pull the selection across the async bridge before we kick off send.
        panel.executeContextAction("send", "/explain")
    }

    override fun update(e: AnActionEvent) {
        super.update(e)
        val editor = e.getData(CommonDataKeys.EDITOR)
        val hasSelection = editor?.selectionModel?.hasSelection() == true
        if (!hasSelection) e.presentation.isEnabled = false
    }
}
