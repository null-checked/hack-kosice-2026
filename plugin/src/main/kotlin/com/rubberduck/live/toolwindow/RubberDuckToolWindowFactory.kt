package com.rubberduck.live.toolwindow

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.rubberduck.live.detector.EventBus
import com.rubberduck.live.detector.SessionContext
import com.rubberduck.live.model.DeveloperEvent
import com.rubberduck.live.model.SocraticResponse
import com.rubberduck.live.net.BackendClient
import com.rubberduck.live.net.EventsWebSocket
import com.rubberduck.live.settings.RubberDuckSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridLayout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.atomic.AtomicReference
import javax.swing.BorderFactory
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JList
import javax.swing.JPanel

/**
 * Three-section tool window (Status / Recent events / Last Socratic). Exposes
 * a static [updateSocratic] hook so the [com.rubberduck.live.intervention.StuckInterventionCoordinator]
 * can push a response without holding a reference to this instance.
 */
class RubberDuckToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = RubberDuckToolWindow.build(project)
        val content = com.intellij.ui.content.ContentFactory.getInstance().createContent(panel, null, false)
        toolWindow.contentManager.addContent(content)
    }
}

/** Actual panel + a global hook for the intervention coordinator. */
object RubberDuckToolWindow {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val eventsModel = DefaultListModel<String>()
    private val statusLabel = JBLabel("● disconnected").apply {
        font = font.deriveFont(font.size2D * 1.5f)
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(java.awt.Color.LIGHT_GRAY, 2, true),
            BorderFactory.createEmptyBorder(4, 12, 4, 12)
        )
        isOpaque = true
        horizontalAlignment = javax.swing.SwingConstants.LEFT
    }
    private var lastLatencyMs: Long? = null
    private val sessionLabel = JBLabel("session: (not started)")
    private val providerLabel = JBLabel("llm: unknown")
    private val socraticArea = JBLabel("<html><i>No Socratic question yet. Keep coding.</i></html>")

    private val tsFmt = SimpleDateFormat("HH:mm:ss")
    private val currentProject = AtomicReference<Project?>()

    fun build(project: Project): JPanel {
        currentProject.set(project)
        val root = JPanel(BorderLayout(8, 8))
        root.border = BorderFactory.createEmptyBorder(8, 8, 8, 8)

        // -- Status section ---------------------------------------------------
        val status = JPanel(GridLayout(0, 1, 4, 4))
        status.border = BorderFactory.createTitledBorder("Status")
        status.add(statusLabel)
        status.add(sessionLabel)
        status.add(providerLabel)
        val dashBtn = JButton("Open Dashboard")
        dashBtn.addActionListener { com.intellij.ide.BrowserUtil.browse("http://localhost:3000") }
        val btnRow = JPanel(FlowLayout(FlowLayout.LEFT))
        btnRow.add(dashBtn)
        status.add(btnRow)

        // -- Events section ---------------------------------------------------
        val events = JPanel(BorderLayout())
        events.border = BorderFactory.createTitledBorder("Recent events")
        val list = JList(eventsModel)
        val scroll = JBScrollPane(list)
        scroll.preferredSize = Dimension(260, 220)
        events.add(scroll, BorderLayout.CENTER)

        // -- Socratic section -------------------------------------------------
        val socratic = JPanel(BorderLayout())
        socratic.border = BorderFactory.createTitledBorder("Last Socratic question")
        socraticArea.verticalAlignment = javax.swing.SwingConstants.TOP
        socratic.add(socraticArea, BorderLayout.CENTER)

        val top = JPanel(GridLayout(0, 1, 8, 8))
        top.add(status)
        top.add(events)
        root.add(top, BorderLayout.CENTER)
        root.add(socratic, BorderLayout.SOUTH)

        sessionLabel.text = "session: ${SessionContext.getInstance().sessionId}"
        startBackgroundUpdaters()
        return root
    }

    private fun startBackgroundUpdaters() {
        scope.launch {
            EventBus.getInstance().events.collect { ev -> appendEvent(ev) }
        }
        scope.launch { healthLoop() }
    }

    private fun appendEvent(ev: DeveloperEvent) {
        val label = "${tsFmt.format(Date(ev.ts))}  ${ev.type}  ${ev.file ?: "-"}"
        ApplicationManager.getApplication().invokeLater {
            eventsModel.addElement(label)
            while (eventsModel.size() > 20) eventsModel.remove(0)
        }
    }

    private suspend fun healthLoop() {
        while (true) {
            val client = BackendClient.getInstance()
            val result = runCatching { client.health() }
            val ws = EventsWebSocket.getInstance().isConnected
            ApplicationManager.getApplication().invokeLater {
                result.onSuccess { h ->
                    val s = RubberDuckSettings.getInstance().state
                    s.lastSeenLlmProvider = h.llm_provider
                    s.lastSeenLlmModel = h.llm_model
                    s.demoMockDisplay = h.demo_mock
                    // Latency measurement
                    val latency = h.latency_ms ?: -1
                    lastLatencyMs = if (latency >= 0) latency.toLong() else null
                    // Color logic
                    val (iconColor, bgColor, statusText) = when {
                        !ws ->
                            Triple("#FFD600", java.awt.Color(255, 249, 196), "WS reconnecting") // yellow
                        latency >= 0 && latency < 150 ->
                            Triple("#00C853", java.awt.Color(232, 245, 233), "Excellent") // green
                        latency in 150..499 ->
                            Triple("#FFD600", java.awt.Color(255, 249, 196), "OK") // yellow
                        latency >= 500 ->
                            Triple("#D50000", java.awt.Color(255, 235, 238), "Slow") // red
                        else ->
                            Triple("#BDBDBD", java.awt.Color(238, 238, 238), "Unknown") // gray
                    }
                    val latencyHtml = lastLatencyMs?.let { "<span style='font-size:smaller;color:#888'>&nbsp;(${it}ms)</span>" } ?: ""
                    statusLabel.text = "<html>"
                        + "<span style='font-size:1.2em;color:$iconColor;'>&#9679;</span> "
                        + "<b>${if (ws) "connected" else "disconnected"}</b>"
                        + " $statusText $latencyHtml"
                        + "</html>"
                    statusLabel.background = bgColor
                    providerLabel.text = "llm: ${h.llm_provider} / ${h.llm_model}" + if (h.demo_mock) "  [DEMO_MOCK]" else ""
                }.onFailure {
                    statusLabel.text = "<html><span style='font-size:1.2em;color:#D50000;'>&#9679;</span> <b>backend unreachable</b></html>"
                    statusLabel.background = java.awt.Color(255, 235, 238)
                }
            }
            delay(10_000)
        }
    }

    /** Pushed by the intervention coordinator after fetching a Socratic response. */
    fun updateSocratic(response: SocraticResponse, stuckReason: String) {
        val followUps = response.follow_ups.joinToString("<li>", prefix = "<li>")
            .takeIf { response.follow_ups.isNotEmpty() } ?: ""
        val html = buildString {
            append("<html><b>Rule:</b> ").append(stuckReason).append("<br/>")
            append("<b>Question:</b> ").append(response.question).append("<br/>")
            if (followUps.isNotEmpty()) {
                append("<b>Follow-ups:</b><ul>").append(followUps).append("</ul>")
            }
            append("<i>confidence ").append(String.format("%.2f", response.confidence)).append("</i></html>")
        }
        ApplicationManager.getApplication().invokeLater { socraticArea.text = html }
    }
}
