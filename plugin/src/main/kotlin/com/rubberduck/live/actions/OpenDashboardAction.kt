package com.rubberduck.live.actions

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.rubberduck.live.settings.RubberDuckSettings

/** Opens the companion Next.js dashboard using the URL configured in
 *  Settings → Tools → RubberDuck Live. */
class OpenDashboardAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        BrowserUtil.browse(RubberDuckSettings.getInstance().dashboardUrl)
    }
}
