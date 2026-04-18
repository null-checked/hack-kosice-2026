package com.rubberduck.live.toolwindow

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.rubberduck.live.detector.EventBus
import com.rubberduck.live.detector.SessionContext
import com.rubberduck.live.model.ChatTurn
import com.rubberduck.live.model.CodeChatRequest
import com.rubberduck.live.model.DeveloperEvent
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
import javax.swing.JTextPane
import javax.swing.SwingUtilities
import javax.swing.text.html.HTMLEditorKit

/**
 * Three-section tool window (Status / Recent events / Last Socratic). Exposes
 * a static [updateSocratic] hook so the [com.rubberduck.live.intervention.StuckInterventionCoordinator]
 * can push a response without holding a reference to this instance.
 */
class RubberDuckToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val contentFactory = com.intellij.ui.content.ContentFactory.getInstance()

        // Primary tab — embed the live Next.js dashboard via JCEF so the
        // tool window inherits the full wellness UI (heatmap, flow chart,
        // anti-patterns, stuck timeline) instead of plain Swing rows.
        if (DashboardBrowserPanel.isSupported()) {
            val dashboardPanel = DashboardBrowserPanel(toolWindow.disposable)
            val dashTab = contentFactory.createContent(dashboardPanel, "Dashboard", false)
            dashTab.isCloseable = false
            toolWindow.contentManager.addContent(dashTab)
        }

        // Secondary tab — the Chat panel (user asks questions about their
        // code) plus a small llm/session breadcrumb and a collapsed "Recent
        // events" strip for diagnostics. Also the fallback if JCEF is down.
        val chatPanel = RubberDuckToolWindow.build(project)
        val chatTab = contentFactory.createContent(chatPanel, "Chat", false)
        chatTab.isCloseable = false
        toolWindow.contentManager.addContent(chatTab)
    }
}

/** Actual panel + a global hook for the intervention coordinator. */
object RubberDuckToolWindow {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val eventsModel = DefaultListModel<String>()
    /** Single compact breadcrumb at the top of the tab: llm + session + mode. */
    private val breadcrumb = JBLabel("<html>&middot; connecting…</html>")

    // ---- Code Q&A chat panel -----------------------------------------------
    // v0.3: chat is no longer seeded by stuck moments. It's always available
    // for the user to ask questions about their current file. Hits the new
    // `POST /api/v1/code-chat` endpoint which returns direct answers (not
    // Socratic probes).
    private val transcriptPane = JTextPane().apply {
        isEditable = false
        contentType = "text/html"
        editorKit = HTMLEditorKit()
    }
    private val inputField = JBTextField().apply {
        toolTipText = "Ask a question about the file you're looking at"
    }
    private val sendButton = JButton("Send")
    private val clearButton = JButton("Clear")
    private val coachHintLabel = JBLabel(
        "<html><i>Ask me anything about your code.</i></html>",
    )
    private val conversation: MutableList<ChatTurn> = mutableListOf()

    private val tsFmt = SimpleDateFormat("HH:mm:ss")
    private val currentProject = AtomicReference<Project?>()

    fun build(project: Project): JPanel {
        currentProject.set(project)
        val root = JPanel(BorderLayout(6, 6))
        root.border = BorderFactory.createEmptyBorder(6, 8, 8, 8)

        // -- Top breadcrumb: llm / session / mode in one short line ----------
        val breadcrumbBar = JPanel(BorderLayout())
        breadcrumbBar.add(breadcrumb, BorderLayout.WEST)

        // -- Chat panel (primary, CENTER) ------------------------------------
        val chat = JPanel(BorderLayout(4, 4))
        val transcriptScroll = JBScrollPane(transcriptPane)
        transcriptScroll.preferredSize = Dimension(260, 260)
        chat.add(transcriptScroll, BorderLayout.CENTER)

        val inputRow = JPanel(BorderLayout(4, 4))
        inputRow.add(inputField, BorderLayout.CENTER)
        val buttonsRow = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0))
        buttonsRow.add(sendButton)
        buttonsRow.add(clearButton)
        inputRow.add(buttonsRow, BorderLayout.EAST)
        val bottomCol = JPanel(GridLayout(0, 1, 0, 2))
        bottomCol.add(coachHintLabel)
        bottomCol.add(inputRow)
        chat.add(bottomCol, BorderLayout.SOUTH)

        sendButton.addActionListener { sendReply() }
        inputField.addActionListener { sendReply() }        // Enter key
        clearButton.addActionListener { clearConversation() }
        updateInputEnablement()

        // -- Collapsed "Recent events" strip at the bottom for diagnostics ---
        val events = JPanel(BorderLayout())
        events.border = BorderFactory.createTitledBorder("Recent events")
        val list = JList(eventsModel)
        val scroll = JBScrollPane(list)
        scroll.preferredSize = Dimension(260, 110)
        events.add(scroll, BorderLayout.CENTER)

        root.add(breadcrumbBar, BorderLayout.NORTH)
        root.add(chat, BorderLayout.CENTER)
        root.add(events, BorderLayout.SOUTH)

        renderTranscript()
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
            val wsOk = EventsWebSocket.getInstance().isConnected
            val shortSession = SessionContext.getInstance().sessionId.take(8)
            ApplicationManager.getApplication().invokeLater {
                result.onSuccess { h ->
                    val s = RubberDuckSettings.getInstance().state
                    s.lastSeenLlmProvider = h.llm_provider
                    s.lastSeenLlmModel = h.llm_model
                    s.demoMockDisplay = h.demo_mock
                    val dot = if (wsOk) "<font color='#22c55e'>●</font>"
                    else "<font color='#f59e0b'>●</font>"
                    val mode = if (h.demo_mock) " · <font color='#f59e0b'>DEMO_MOCK</font>" else ""
                    breadcrumb.text =
                        "<html>$dot ${h.llm_provider} / ${h.llm_model}$mode " +
                            "<font color='#888888'>· session $shortSession…</font></html>"
                }.onFailure {
                    breadcrumb.text =
                        "<html><font color='#ef4444'>●</font> backend unreachable " +
                            "<font color='#888888'>· session $shortSession…</font></html>"
                }
            }
            delay(10_000)
        }
    }

    // -- chat helpers ---------------------------------------------------------
    // v0.3: the chat is general-purpose code Q&A. The user types a question
    // about the currently-open file; the backend answers it directly (no
    // Socratic counter-question). State is ephemeral; Clear resets the
    // whole transcript.

    private fun sendReply() {
        val text = inputField.text?.trim().orEmpty()
        if (text.isEmpty()) return
        if (RubberDuckSettings.getInstance().state.privacyMode) return

        inputField.text = ""
        conversation.add(ChatTurn(role = "user", text = text))
        renderTranscript()

        sendButton.isEnabled = false
        inputField.isEnabled = false
        val snapshot = conversation.toList()
        val project = currentProject.get()

        scope.launch {
            val (currentCode, language, filePath) = captureActiveEditor(project)
            val response = runCatching {
                BackendClient.getInstance().codeChat(
                    CodeChatRequest(
                        conversation = snapshot,
                        current_code = currentCode,
                        file_path = filePath,
                        language = language,
                    ),
                )
            }
            ApplicationManager.getApplication().invokeLater {
                response
                    .onSuccess {
                        conversation.add(ChatTurn(role = "assistant", text = it.answer))
                    }
                    .onFailure {
                        conversation.add(
                            ChatTurn(
                                role = "assistant",
                                text = "(backend unreachable — try again in a moment)",
                            ),
                        )
                    }
                renderTranscript()
                updateInputEnablement()
            }
        }
    }

    private data class EditorSnapshot(
        val code: String,
        val language: String,
        val filePath: String?,
    )

    private fun captureActiveEditor(project: Project?): EditorSnapshot {
        if (project == null) return EditorSnapshot("", "text", null)
        return runCatching {
            com.intellij.openapi.application.ReadAction.compute<EditorSnapshot, RuntimeException> {
                val fem = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project)
                val vf = fem.selectedFiles.firstOrNull()
                    ?: return@compute EditorSnapshot("", "text", null)
                val doc = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(vf)
                val code = doc?.text.orEmpty()
                val language = (vf.extension ?: "text").lowercase()
                val redacted = com.rubberduck.live.util.PathRedactor.redact(project, vf.path)
                EditorSnapshot(code, language, redacted)
            }
        }.getOrDefault(EditorSnapshot("", "text", null))
    }

    private fun clearConversation() {
        ApplicationManager.getApplication().invokeLater {
            conversation.clear()
            coachHintLabel.text = "<html><i>Ask me anything about your code.</i></html>"
            inputField.text = ""
            renderTranscript()
            updateInputEnablement()
        }
    }

    private fun updateInputEnablement() {
        val privacy = RubberDuckSettings.getInstance().state.privacyMode
        val hasTurns = conversation.isNotEmpty()
        inputField.isEnabled = !privacy
        sendButton.isEnabled = !privacy
        clearButton.isEnabled = hasTurns
        inputField.toolTipText = when {
            privacy -> "Strict privacy is on — chat would send your file to the backend. Disable it in Settings."
            else -> "Ask a question about the file you're looking at"
        }
    }

    private fun renderTranscript() {
        val html = buildString {
            append("<html><body style='font-family: sans-serif; font-size: 11px;'>")
            if (conversation.isEmpty()) {
                append("<i style='color: #888888;'>Ask me anything about your code.</i>")
            } else {
                for (turn in conversation) {
                    val isAssistant = turn.role == "assistant"
                    val bg = if (isAssistant) "#1f2937" else "#0b0f17"
                    val border = if (isAssistant) "#8b5cf6" else "#22d3ee"
                    val icon = if (isAssistant) "&#129428;" else "&#128100;"   // 🦆 vs 👤
                    append("<div style='margin: 4px 0; padding: 6px 8px; ")
                    append("background: ").append(bg).append("; ")
                    append("border-left: 3px solid ").append(border).append(";'>")
                    append(icon).append(" ").append(htmlEscape(turn.text))
                    append("</div>")
                }
            }
            append("</body></html>")
        }
        transcriptPane.text = html
        SwingUtilities.invokeLater {
            transcriptPane.caretPosition = transcriptPane.document.length
        }
    }

    private fun htmlEscape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\n", "<br/>")
}
