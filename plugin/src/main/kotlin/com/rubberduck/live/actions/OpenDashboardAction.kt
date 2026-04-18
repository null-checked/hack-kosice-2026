package com.rubberduck.live.actions

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent

/** Opens the companion Next.js dashboard at `http://localhost:3000`. */
class OpenDashboardAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        BrowserUtil.browse("http://localhost:3000")
    }
}
