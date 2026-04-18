package com.rubberduck.live.intervention

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project

/**
 * Thin adapter around [NotificationGroupManager] that surfaces a passive
 * info balloon. v0.3 dropped the earlier `Ask me / Not now` invitation
 * pattern: Socratic questions now appear directly as fire-and-forget
 * notifications, no user click required. The user can engage further by
 * opening the tool-window chat panel and asking their own question.
 */
object SocraticNotifier {

    private const val GROUP = "RubberDuck Live"

    /** Show a plain info balloon. Used by the Socratic auto-flow and by
     *  action handlers (Generate Tests / Suggest Refactor) for toasts. */
    fun showInfo(project: Project, title: String, content: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(GROUP)
            .createNotification(title, content, NotificationType.INFORMATION)
            .notify(project)
    }
}
