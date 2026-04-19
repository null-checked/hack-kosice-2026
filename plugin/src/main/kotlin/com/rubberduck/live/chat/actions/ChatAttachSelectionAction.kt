package com.rubberduck.live.chat.actions

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.wm.ToolWindowManager
import com.rubberduck.live.chat.ChatBrowserPanel

/**
 * Right-click → "Attach Current Selection to Chat".
 *
 * Opens the chat tool window (so the newly-added chip is visible) and
 * tells the web UI to run its `attach-selection` context action, which
 * in turn calls back into `window.rubberduck.getSelection()` to pull
 * the active editor selection across the bridge.
 */
class ChatAttachSelectionAction : ChatToolWindowAction() {
    override fun perform(e: AnActionEvent, panel: ChatBrowserPanel) {
        val project = e.project
        if (project != null) {
            val tw = ToolWindowManager.getInstance(project).getToolWindow(OpenChatAction.TOOL_WINDOW_ID)
            tw?.activate(null)
        }
        panel.executeContextAction("attach-selection")
    }

    override fun update(e: AnActionEvent) {
        super.update(e)
        // Only meaningful when there's a selection in the active editor.
        val editor = e.getData(com.intellij.openapi.actionSystem.CommonDataKeys.EDITOR)
        val hasSelection = editor?.selectionModel?.hasSelection() == true
        if (!hasSelection) e.presentation.isEnabled = false
    }
}
