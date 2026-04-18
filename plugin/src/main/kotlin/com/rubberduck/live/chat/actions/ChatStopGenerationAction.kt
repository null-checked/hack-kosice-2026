package com.rubberduck.live.chat.actions

import com.intellij.openapi.actionSystem.AnActionEvent
import com.rubberduck.live.chat.ChatBrowserPanel

class ChatStopGenerationAction : ChatToolWindowAction() {
    override fun perform(e: AnActionEvent, panel: ChatBrowserPanel) {
        panel.executeContextAction("stop")
    }
}
