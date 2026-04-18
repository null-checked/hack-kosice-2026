package com.rubberduck.live

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.Disposer
import com.rubberduck.live.detector.SessionContext
import com.rubberduck.live.detector.StuckDetector
import com.rubberduck.live.intervention.StuckInterventionCoordinator
import com.rubberduck.live.listeners.CaretDwellTracker
import com.rubberduck.live.listeners.EditorActivityListener
import com.rubberduck.live.listeners.FileFocusListener
import com.rubberduck.live.listeners.TestExecutionListener
import com.rubberduck.live.net.EventsWebSocket
import com.rubberduck.live.storage.EventLog
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Project start-up activity — registers all listeners, starts the WebSocket
 * bridge, and ensures application services are spun up.
 *
 * Implements [ProjectActivity] (the platform-2024.2+ coroutine-based
 * successor to `StartupActivity`).
 */
class RubberDuckStartup : ProjectActivity {

    private val log = Logger.getInstance(RubberDuckStartup::class.java)
    private val appInitDone = AtomicBoolean(false)

    override suspend fun execute(project: Project) {
        log.info("RubberDuck Live starting for project: ${project.name}")

        // Application-level singletons — start once.
        if (appInitDone.compareAndSet(false, true)) {
            EventLog.getInstance()
            SessionContext.getInstance()
            StuckDetector.getInstance().start()
            StuckInterventionCoordinator.getInstance() // side-effect: service init
            EventsWebSocket.getInstance().start()
        }

        // Per-project listeners, disposed with the project.
        val projectDisposable: Disposable = Disposer.newDisposable("RubberDuckLive:${project.name}")
        Disposer.register(project, projectDisposable)

        EditorActivityListener(project).install(projectDisposable)
        FileFocusListener(project).install(projectDisposable)
        CaretDwellTracker(project).install(projectDisposable)
        TestExecutionListener(project).install(projectDisposable)

        log.info("RubberDuck Live listeners installed; session=${SessionContext.getInstance().sessionId}")
    }
}
