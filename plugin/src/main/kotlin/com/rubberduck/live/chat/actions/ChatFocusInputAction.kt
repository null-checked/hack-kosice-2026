package com.rubberduck.live.chat.actions

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.wm.ToolWindowManager
import com.rubberduck.live.chat.ChatBrowserPanel

class ChatFocusInputAction : ChatToolWindowAction() {
    override fun perform(e: AnActionEvent, panel: ChatBrowserPanel) {
        val project = e.project
        if (project != null) {
            // Also raise the tool window so the focus request lands visibly.
            val tw = ToolWindowManager.getInstance(project).getToolWindow(OpenChatAction.TOOL_WINDOW_ID)
            if (tw != null && !tw.isActive) tw.activate(null)
        }
        panel.executeContextAction("focus-input")
    }
}
