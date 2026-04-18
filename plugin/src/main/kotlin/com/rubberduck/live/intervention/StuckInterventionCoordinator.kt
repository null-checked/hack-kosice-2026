package com.rubberduck.live.intervention

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.ProjectManager
import com.rubberduck.live.detector.SessionContext
import com.rubberduck.live.model.DeveloperEvent
import com.rubberduck.live.model.SocraticRequest
import com.rubberduck.live.model.SocraticResponse
import com.rubberduck.live.net.BackendClient
import com.rubberduck.live.settings.InterventionFrequency
import com.rubberduck.live.settings.RubberDuckSettings
import com.rubberduck.live.toolwindow.RubberDuckToolWindow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Orchestrates the Socratic intervention flow triggered by
 * [com.rubberduck.live.detector.StuckDetector].
 *
 * Responsibilities:
 * - Global throttle (≤ 1 intervention per 2 min, scaled by [InterventionFrequency]).
 * - Per-rule cooldown (10 min after a `Not now`).
 * - Hits `/socratic-question` on accept, pipes the response into the tool window.
 */
@Service(Service.Level.APP)
class StuckInterventionCoordinator {

    private val log = Logger.getInstance(StuckInterventionCoordinator::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var lastInterventionTs: Long = 0
    private val ruleDismissedUntil = ConcurrentHashMap<String, Long>()

    /** Called from [com.rubberduck.live.detector.StuckDetector] on the EDT. */
    fun onStuckDetected(event: DeveloperEvent, rule: String) {
        val now = System.currentTimeMillis()
        val globalWindow = globalThrottleMs()
        if (now - lastInterventionTs < globalWindow) {
            log.debug("Intervention throttled (global)")
            return
        }
        val silencedUntil = ruleDismissedUntil[rule] ?: 0
        if (now < silencedUntil) {
            log.debug("Rule $rule is silenced until $silencedUntil")
            return
        }
        lastInterventionTs = now
        val project = ProjectManager.getInstance().openProjects.firstOrNull() ?: return
        val interventionId = UUID.randomUUID().toString()

        SocraticNotifier.showInvitation(
            project = project,
            stuckReason = rule,
            interventionId = interventionId,
            onAccept = { scope.launch { fetchAndDisplay(rule) } },
            onDismiss = {
                ruleDismissedUntil[rule] = System.currentTimeMillis() + 10 * 60_000L
            },
        )
    }

    private fun fetchAndDisplay(stuckReason: String) {
        if (RubberDuckSettings.getInstance().state.privacyMode) {
            ApplicationManager.getApplication().invokeLater {
                val project = ProjectManager.getInstance().openProjects.firstOrNull() ?: return@invokeLater
                SocraticNotifier.showInfo(
                    project,
                    "RubberDuck Live — privacy mode",
                    "Strict privacy is on: code isn't sent. Disable it in Settings → Tools → RubberDuck Live to request a Socratic question.",
                )
            }
            return
        }
        val project = ProjectManager.getInstance().openProjects.firstOrNull() ?: return
        val currentCode = runCatching {
            val fem = FileEditorManager.getInstance(project)
            val vf = fem.selectedFiles.firstOrNull() ?: return@runCatching ""
            val doc = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(vf)
            doc?.text.orEmpty()
        }.getOrDefault("")
        val request = SocraticRequest(
            recent_events = SessionContext.getInstance().recent(20),
            current_code = currentCode,
            stuck_reason = stuckReason,
        )
        val response: SocraticResponse = try {
            BackendClient.getInstance().socraticQuestion(request)
        } catch (t: Throwable) {
            log.warn("Socratic request failed: ${t.message}")
            SocraticResponse(
                question = "(Backend unreachable — take a deep breath and describe the invariant out loud.)",
                follow_ups = listOf("What should be true before this line?", "What inputs have you actually tried?"),
                confidence = 0.0,
            )
        }
        ApplicationManager.getApplication().invokeLater {
            RubberDuckToolWindow.updateSocratic(response, stuckReason)
            SocraticNotifier.showInfo(
                project,
                "🦆 RubberDuck asks…",
                response.question,
            )
        }
    }

    private fun globalThrottleMs(): Long {
        return when (RubberDuckSettings.getInstance().state.interventionFrequency) {
            InterventionFrequency.MINIMAL -> 10 * 60_000L
            InterventionFrequency.NORMAL -> 2 * 60_000L
            InterventionFrequency.CHATTY -> 30_000L
        }
    }

    companion object {
        fun getInstance(): StuckInterventionCoordinator =
            ApplicationManager.getApplication().getService(StuckInterventionCoordinator::class.java)
    }
}
