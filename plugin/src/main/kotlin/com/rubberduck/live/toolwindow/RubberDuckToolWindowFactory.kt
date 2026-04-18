package com.rubberduck.live.toolwindow

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.rubberduck.live.detector.EventBus
import com.rubberduck.live.detector.EventEmitter
import com.rubberduck.live.detector.SessionContext
import com.rubberduck.live.model.ChatTurn
import com.rubberduck.live.model.CodeChatRequest
import com.rubberduck.live.model.ContextFile
import com.rubberduck.live.model.DeveloperEvent
import com.rubberduck.live.model.EventType
import com.rubberduck.live.net.BackendClient
import com.rubberduck.live.net.EventsWebSocket
import com.rubberduck.live.settings.RubberDuckSettings
import com.rubberduck.live.toolwindow.chat.AssistantMessageComponent
import com.rubberduck.live.toolwindow.chat.AtMentionController
import com.rubberduck.live.toolwindow.chat.SessionFileResolver
import com.rubberduck.live.toolwindow.chat.UserMessageComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridLayout
import java.awt.Rectangle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JList
import javax.swing.JPanel

/**
 * Two-tab tool window: a JCEF-embedded Next.js dashboard for live wellness
 * metrics, and a Swing chat panel for ad-hoc code Q&A. The chat panel supports
 * `@`-mention file attachment and inline Apply/Reject cards for LLM-proposed
 * code edits; see [RubberDuckToolWindow] for the implementation.
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

    private const val MAX_CONTEXT_FILE_BYTES = 64L * 1024
    private const val MAX_TOTAL_CONTEXT_BYTES = 256L * 1024

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val eventsModel = DefaultListModel<String>()
    /** Single compact breadcrumb at the top of the tab: llm + session + mode. */
    private val breadcrumb = JBLabel("<html>&middot; connecting…</html>")

    // ---- Code Q&A chat panel -----------------------------------------------
    // v0.3: chat is no longer seeded by stuck moments. It's always available
    // for the user to ask questions about their current file. Hits the new
    // `POST /api/v1/code-chat` endpoint which returns direct answers (not
    // Socratic probes).
    //
    // v0.4: the transcript moved from a single HTML `JTextPane` to a vertical
    // stack of `JPanel`s (one per turn). Each assistant turn can carry zero or
    // more `CodeEdit` cards (rendered inside `AssistantMessageComponent`).
    // Users can `@`-mention project files to attach their contents as
    // `context_files` on the outgoing request.
    private val transcriptPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = true
        border = BorderFactory.createEmptyBorder(4, 4, 4, 4)
    }
    private val transcriptScroll = JBScrollPane(transcriptPanel).apply {
        preferredSize = Dimension(260, 260)
        verticalScrollBar.unitIncrement = 16
        border = BorderFactory.createEmptyBorder()
    }
    private val inputField = JBTextField().apply {
        toolTipText = "Ask a question (use @ to mention project files)"
    }
    private val sendButton = JButton("Send")
    private val clearButton = JButton("Clear")
    private val coachHintLabel = JBLabel(
        "<html><i>Ask me anything about your code. Type @ to mention a file.</i></html>",
    )
    private val conversation: MutableList<ChatTurn> = mutableListOf()

    private val tsFmt = SimpleDateFormat("HH:mm:ss")
    private val currentProject = AtomicReference<Project?>()
    @Volatile private var sessionResolver: SessionFileResolver? = null
    @Volatile private var atMentionController: AtMentionController? = null
    /** One-time per-session hint so we don't spam the chat with the same notice. */
    @Volatile private var privacyHintShown: Boolean = false

    fun build(project: Project): JPanel {
        currentProject.set(project)

        // Per-project mention infrastructure. The object-singleton nature of
        // this tool window means we re-create these each time `build()` fires
        // (e.g. project reopened) — the previous controller's listener would
        // be attached to the same `inputField`, so dispose it before installing
        // the new one.
        atMentionController?.dispose()
        val resolver = SessionFileResolver(project)
        sessionResolver = resolver
        val controller = AtMentionController(project, inputField, resolver)
        controller.install()
        atMentionController = controller

        val root = JPanel(BorderLayout(6, 6))
        root.border = BorderFactory.createEmptyBorder(6, 8, 8, 8)

        // -- Top breadcrumb: llm / session / mode in one short line ----------
        val breadcrumbBar = JPanel(BorderLayout())
        breadcrumbBar.add(breadcrumb, BorderLayout.WEST)

        // -- Chat panel (primary, CENTER) ------------------------------------
        val chat = JPanel(BorderLayout(4, 4))
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

        initializeTranscript(project, resolver)
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
    //
    // v0.4: the turn can also carry `@file` mentions (picked up by
    // `AtMentionController` in the input field) which are attached as
    // `context_files` on the request, and the response can include
    // structured `edits` rendered as inline Apply/Reject cards.

    private fun sendReply() {
        val text = inputField.text?.trim().orEmpty()
        if (text.isEmpty()) return
        // Preserve existing blanket privacy gate — strict privacy blocks the
        // entire send today. The attachment-only gate below is a strictly
        // narrower rule that kicks in if/when this blanket block is relaxed.
        if (RubberDuckSettings.getInstance().state.privacyMode) return

        inputField.text = ""
        val userTurn = ChatTurn(role = "user", text = text)
        conversation.add(userTurn)
        appendTurnComponent(UserMessageComponent(text))

        sendButton.isEnabled = false
        inputField.isEnabled = false
        val snapshot = conversation.toList()
        val project = currentProject.get()
        val resolver = sessionResolver

        scope.launch {
            val (currentCode, language, filePath) = captureActiveEditor(project)
            val contextFiles = collectContextFiles(project, text)
            val response = runCatching {
                BackendClient.getInstance().codeChat(
                    CodeChatRequest(
                        conversation = snapshot,
                        current_code = currentCode,
                        file_path = filePath,
                        language = language,
                        context_files = contextFiles,
                        allow_edits = true,
                    ),
                )
            }
            ApplicationManager.getApplication().invokeLater {
                response
                    .onSuccess { resp ->
                        val turn = ChatTurn(
                            role = "assistant",
                            text = resp.answer,
                            edits = resp.edits,
                        )
                        conversation.add(turn)
                        // One intervention_id per edit — threads SHOWN → ACCEPTED/DISMISSED
                        // so downstream metrics can correlate them 1:1 and acceptance-rate
                        // stays bounded to [0, 1] per edit.
                        val interventionIds = resp.edits.map { UUID.randomUUID().toString() }
                        for ((i, edit) in resp.edits.withIndex()) {
                            emitChatEditShown(
                                interventionId = interventionIds[i],
                                filePath = edit.file_path,
                                editIndex = i,
                                totalEdits = resp.edits.size,
                            )
                        }
                        if (project != null && resolver != null) {
                            appendTurnComponent(
                                AssistantMessageComponent(
                                    turn.text,
                                    turn.edits,
                                    project,
                                    resolver,
                                    interventionIds,
                                ),
                            )
                        } else {
                            appendTurnComponent(UserMessageComponent(turn.text))
                        }
                    }
                    .onFailure {
                        val errorText = "(backend unreachable — try again in a moment)"
                        conversation.add(ChatTurn(role = "assistant", text = errorText))
                        appendTurnComponent(UserMessageComponent(errorText))
                    }
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
            ReadAction.compute<EditorSnapshot, RuntimeException> {
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

    /**
     * Parse `@path` mentions out of [text] and attach their contents as
     * `ContextFile`s. Each file must pass three gates:
     *   1. Privacy mode must be OFF.
     *   2. Individual file size < 64 KB.
     *   3. Cumulative attached size < 256 KB across all attachments.
     *
     * Files that fail a gate generate an inline system-style chat notice so the
     * user sees why their `@`-mention was dropped, rather than silently
     * shipping without context.
     *
     * Must run off the EDT (VFS + index access requires a read action).
     */
    private fun collectContextFiles(project: Project?, text: String): List<ContextFile> {
        if (project == null) return emptyList()
        val resolver = sessionResolver ?: return emptyList()
        val mentions = AtMentionController.parseMentions(text)
        if (mentions.isEmpty()) return emptyList()

        val privacy = RubberDuckSettings.getInstance().state.privacyMode
        if (privacy) {
            // Privacy mode is ON — drop all attachments with one-time notice.
            // (Today this path is unreachable because `sendReply` short-circuits
            // the whole send under privacy; kept here for when that blanket
            // block is someday relaxed.)
            if (!privacyHintShown) {
                privacyHintShown = true
                appendSystemNotice(
                    "Privacy mode: @-mention file attachments are disabled.",
                )
            }
            return emptyList()
        }

        val collected = ArrayList<ContextFile>()
        var totalBytes = 0L
        for (redacted in mentions) {
            val vf: VirtualFile? = ReadAction.compute<VirtualFile?, RuntimeException> {
                resolver.resolve(redacted)
            }
            if (vf == null || !vf.isValid || vf.isDirectory) {
                appendSystemNotice("Skipped @$redacted: file not found in project.")
                continue
            }
            val size = vf.length
            if (size > MAX_CONTEXT_FILE_BYTES) {
                appendSystemNotice(
                    "Skipped @$redacted: file ${formatKb(size)} > 64KB limit.",
                )
                continue
            }
            if (totalBytes + size > MAX_TOTAL_CONTEXT_BYTES) {
                appendSystemNotice(
                    "Skipped @$redacted: would exceed 256KB total attachment budget.",
                )
                continue
            }
            val content = runCatching {
                ReadAction.compute<String, RuntimeException> {
                    VfsUtilCore.loadText(vf)
                }
            }.getOrNull()
            if (content == null) {
                appendSystemNotice("Skipped @$redacted: could not read file contents.")
                continue
            }
            collected.add(ContextFile(path = redacted, content = content))
            totalBytes += size
        }
        return collected
    }

    /**
     * Append a neutral system-style notice turn to the transcript. Encodes
     * it as an assistant turn prefixed with a warning glyph so it renders
     * consistently with other assistant bubbles — the point is to be visible,
     * not structurally special.
     */
    private fun appendSystemNotice(text: String) {
        ApplicationManager.getApplication().invokeLater {
            val notice = "\u26A0 $text"
            conversation.add(ChatTurn(role = "assistant", text = notice))
            appendTurnComponent(UserMessageComponent(notice))
        }
    }

    /**
     * Emit a single `INTERVENTION_SHOWN` event for one chat-proposed edit.
     * Mirrors [com.rubberduck.live.intervention.StuckInterventionCoordinator.emitShown]
     * but tagged `kind = "chat_edit"`. One SHOWN per edit (not per turn) so
     * each card's later ACCEPTED/DISMISSED correlates 1:1 via [interventionId],
     * and acceptance-rate metrics stay bounded to [0, 1].
     */
    private fun emitChatEditShown(
        interventionId: String,
        filePath: String,
        editIndex: Int,
        totalEdits: Int,
    ) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val payload: Map<String, JsonElement> = buildMap {
                put("intervention_id", JsonPrimitive(interventionId))
                put("kind", JsonPrimitive("chat_edit"))
                put("edit_index", JsonPrimitive(editIndex))
                put("edit_count", JsonPrimitive(totalEdits))
                put("file_path", JsonPrimitive(filePath))
            }
            EventEmitter.emit(EventType.INTERVENTION_SHOWN, file = filePath, payload = payload)
        }
    }

    private fun clearConversation() {
        ApplicationManager.getApplication().invokeLater {
            conversation.clear()
            sessionResolver?.clear()
            coachHintLabel.text =
                "<html><i>Ask me anything about your code. Type @ to mention a file.</i></html>"
            inputField.text = ""
            privacyHintShown = false
            transcriptPanel.removeAll()
            transcriptPanel.add(buildEmptyPlaceholder())
            transcriptPanel.revalidate()
            transcriptPanel.repaint()
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
            else -> "Ask a question (use @ to mention project files)"
        }
    }

    /**
     * Populate the transcript on tool-window build. Handles two cases:
     *   • `conversation` is empty (typical fresh launch) → show placeholder.
     *   • `conversation` carries over from a prior tool-window session (the
     *     singleton holds state across re-opens) → re-render turns. Per-card
     *     applied/rejected status is **not** restored in this path because we
     *     don't persist it — acceptable for the re-open edge case; normal
     *     in-session use goes through [appendTurnComponent] and keeps state.
     */
    private fun initializeTranscript(project: Project, resolver: SessionFileResolver) {
        transcriptPanel.removeAll()
        if (conversation.isEmpty()) {
            transcriptPanel.add(buildEmptyPlaceholder())
        } else {
            for (turn in conversation) {
                val comp: JPanel = when (turn.role) {
                    "assistant" -> AssistantMessageComponent(turn.text, turn.edits, project, resolver)
                    else -> UserMessageComponent(turn.text)
                }
                transcriptPanel.add(comp)
            }
        }
        transcriptPanel.revalidate()
        transcriptPanel.repaint()
    }

    /**
     * Lazily build the "empty transcript" placeholder shown when the chat has
     * no turns yet. Re-created each call so the same component isn't reparented
     * when the panel is cleared and re-populated.
     */
    private fun buildEmptyPlaceholder(): JBLabel = JBLabel(
        "<html><i style='color: #888888;'>Ask me anything about your code. " +
            "Type @ to mention a file.</i></html>",
    ).apply {
        alignmentX = JPanel.LEFT_ALIGNMENT
        border = BorderFactory.createEmptyBorder(4, 4, 4, 4)
    }

    /**
     * Append one chat-turn component to the transcript in the order it was
     * produced, preserving any state on components already rendered (e.g. a
     * previous assistant turn's applied/rejected card status).
     *
     * Strips the "empty transcript" placeholder on first real turn, then just
     * appends. Must be called on the EDT; auto-scrolls the transcript to the
     * bottom after layout settles so the newest turn is visible.
     */
    private fun appendTurnComponent(component: JPanel) {
        // Clear the empty-state placeholder if it's the only thing in the panel.
        if (transcriptPanel.componentCount == 1 && transcriptPanel.getComponent(0) is JBLabel) {
            transcriptPanel.remove(0)
        }
        transcriptPanel.add(component)
        transcriptPanel.revalidate()
        transcriptPanel.repaint()

        // Auto-scroll to bottom. Deferred to invokeLater so the new
        // component's preferred size is resolved before we measure.
        ApplicationManager.getApplication().invokeLater {
            val h = transcriptPanel.height
            val w = transcriptPanel.width.coerceAtLeast(1)
            transcriptPanel.scrollRectToVisible(Rectangle(0, h.coerceAtLeast(0), w, 1))
        }
    }

    private fun formatKb(bytes: Long): String {
        val kb = bytes / 1024.0
        return if (kb < 10) String.format("%.1fKB", kb) else "${bytes / 1024}KB"
    }
}
