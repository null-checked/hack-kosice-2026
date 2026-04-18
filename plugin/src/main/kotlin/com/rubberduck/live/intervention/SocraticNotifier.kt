package com.rubberduck.live.intervention

import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.rubberduck.live.detector.EventEmitter
import com.rubberduck.live.model.EventType

/**
 * Thin adapter around [NotificationGroupManager] that surfaces a non-modal
 * balloon offering `Ask me` / `Not now`.
 */
object SocraticNotifier {

    private const val GROUP = "RubberDuck Live"

    /** Display a Socratic invitation. Callbacks fire on the EDT. */
    fun showInvitation(
        project: Project,
        stuckReason: String,
        interventionId: String,
        onAccept: () -> Unit,
        onDismiss: () -> Unit,
    ) {
        val group = NotificationGroupManager.getInstance().getNotificationGroup(GROUP)
        val notif: Notification = group.createNotification(
            "🦆 RubberDuck Live",
            "Stuck? I noticed a $stuckReason pattern. Want a rubber-duck question?",
            NotificationType.INFORMATION,
        )
        notif.addAction(object : NotificationAction("Ask me") {
            override fun actionPerformed(e: AnActionEvent, notification: Notification) {
                EventEmitter.emit(
                    EventType.INTERVENTION_ACCEPTED,
                    payload = EventEmitter.json(
                        "intervention_id" to interventionId,
                        "kind" to "socratic",
                    ),
                )
                notification.expire()
                // Open tool window so the question has a home.
                ToolWindowManager.getInstance(project).getToolWindow("RubberDuck Live")?.show()
                onAccept()
            }
        })
        notif.addAction(object : NotificationAction("Not now") {
            override fun actionPerformed(e: AnActionEvent, notification: Notification) {
                EventEmitter.emit(
                    EventType.INTERVENTION_DISMISSED,
                    payload = EventEmitter.json(
                        "intervention_id" to interventionId,
                        "kind" to "socratic",
                    ),
                )
                notification.expire()
                onDismiss()
            }
        })
        notif.notify(project)
        EventEmitter.emit(
            EventType.INTERVENTION_SHOWN,
            payload = EventEmitter.json(
                "intervention_id" to interventionId,
                "kind" to "socratic",
            ),
        )
    }

    /** Show a plain info balloon (used after fetching the Socratic question). */
    fun showInfo(project: Project, title: String, content: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(GROUP)
            .createNotification(title, content, NotificationType.INFORMATION)
            .notify(project)
    }
}
