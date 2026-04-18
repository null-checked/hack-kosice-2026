package com.rubberduck.live.intervention

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.ProjectManager
import com.rubberduck.live.detector.EventEmitter
import com.rubberduck.live.detector.SessionContext
import com.rubberduck.live.model.DeveloperEvent
import com.rubberduck.live.model.EventType
import com.rubberduck.live.model.SocraticRequest
import com.rubberduck.live.model.SocraticResponse
import com.rubberduck.live.net.BackendClient
import com.rubberduck.live.settings.InterventionFrequency
import com.rubberduck.live.settings.RubberDuckSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Orchestrates the Socratic intervention flow triggered by
 * [com.rubberduck.live.detector.StuckDetector].
 *
 * v0.3 simplification: the old `Ask me / Not now` invitation was removed.
 * When a stuck moment is detected, we directly fetch a Socratic question
 * and show it as a non-intrusive info balloon — no click required, no
 * acceptance state. The user can ignore it, or open the tool-window chat
 * panel if they want to dig deeper.
 *
 * Responsibilities:
 * - **Global throttle** (≤ 1 balloon per 2 min, scaled by [InterventionFrequency]).
 * - **Privacy gate** (no code sent when strict privacy is on — fall back to a
 *   stock nudge).
 * - **Emit a single `INTERVENTION_SHOWN`** — no ACCEPTED/DISMISSED for the
 *   passive flow (the balloon is informational, not actionable).
 */
@Service(Service.Level.APP)
class StuckInterventionCoordinator {

    private val log = Logger.getInstance(StuckInterventionCoordinator::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var lastInterventionTs: Long = 0

    /** Called from [com.rubberduck.live.detector.StuckDetector] on the EDT. */
    fun onStuckDetected(event: DeveloperEvent, rule: String) {
        val now = System.currentTimeMillis()
        val globalWindow = globalThrottleMs()
        if (now - lastInterventionTs < globalWindow) {
            log.debug("Intervention throttled (global)")
            return
        }
        lastInterventionTs = now
        scope.launch { fetchAndDisplay(rule) }
    }

    private fun fetchAndDisplay(stuckReason: String) {
        val project = ProjectManager.getInstance().openProjects.firstOrNull() ?: return
        val interventionId = UUID.randomUUID().toString()
        val settings = RubberDuckSettings.getInstance().state

        // Privacy: in strict mode we still nudge, but with a stock message
        // that doesn't require sending code to the backend.
        if (settings.privacyMode) {
            ApplicationManager.getApplication().invokeLater {
                SocraticNotifier.showInfo(
                    project,
                    "🦆 RubberDuck noticed a $stuckReason pattern",
                    "Take a breath. State the invariant out loud. (Strict privacy is on — no code sent.)",
                )
                emitShown(interventionId)
            }
            return
        }

        val currentCode = runCatching {
            ReadAction.compute<String, RuntimeException> {
                val fem = FileEditorManager.getInstance(project)
                val vf = fem.selectedFiles.firstOrNull() ?: return@compute ""
                val doc = FileDocumentManager.getInstance().getDocument(vf)
                doc?.text.orEmpty()
            }
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
                follow_ups = emptyList(),
                confidence = 0.0,
            )
        }

        ApplicationManager.getApplication().invokeLater {
            SocraticNotifier.showInfo(
                project,
                "🦆 RubberDuck asks…",
                response.question,
            )
            emitShown(interventionId)
        }
    }

    private fun emitShown(interventionId: String) {
        EventEmitter.emit(
            EventType.INTERVENTION_SHOWN,
            payload = EventEmitter.json(
                "intervention_id" to interventionId,
                "kind" to "socratic",
            ),
        )
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
