package com.rubberduck.live.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import com.intellij.util.ui.FormBuilder
import com.rubberduck.live.net.BackendClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * Preferences panel under `Settings → Tools → RubberDuck Live`.
 *
 * The layout is intentionally plain Swing (not Kotlin UI DSL) to keep the
 * dependency surface minimal and compatible across 2024.2+.
 */
class RubberDuckConfigurable : Configurable {

    private val searchField = JBTextField()
    private val backendUrlField = JBTextField()
    private val dashboardUrlField = JBTextField()
    private val sendEventsBox = JBCheckBox("Send events to dashboard")
    private val frequencyBox = ComboBox(InterventionFrequency.values())
    private val privacyBox = JBCheckBox("Strict privacy: never send code off-device")
    private val inlineCompletionBox = JBCheckBox("Enable inline completion (ghost text after a pause)")
    private val mockModeBox = JBCheckBox("Enable Mock Mode (canned responses)")
    private val antiPatternRadarBox = JBCheckBox("Show anti-pattern indicators in project view")
    // Chat (Phase 4) — wired minimally here so Settings doesn't lie about
    // what the chat tool window is using. Full model-picker UX + skills
    // integration arrives in Step 7.
    private val chatEnabledBox = JBCheckBox("Enable embedded Chat tool window (JCEF)")
    // Editable combo box so users can pick from `chatAvailableModels` or
    // type a custom one (free text is committed on Apply).
    private val chatModelCombo: ComboBox<String> = ComboBox<String>().apply { isEditable = true }
    private val chatAvailableModelsField = JBTextField().apply {
        toolTipText = "Comma-separated list of models offered in the chat toolbar dropdown."
    }
    private val chatMaxHistorySpinner = javax.swing.JSpinner(
        javax.swing.SpinnerNumberModel(8000, 1000, 100_000, 1000)
    )
    private val chatAttachOpenFilesBox = JBCheckBox("Auto-attach open files as chat context")
    private val chatAttachDiagnosticsBox = JBCheckBox("Auto-attach current diagnostics as chat context")
    private val demoMockLabel = JBLabel("")
    private val providerLabel = JBLabel("")
    private val connectionStatus = JBLabel(" ")
    private val testButton = JButton("Test connection")
    private val allSettingRows = mutableListOf<Pair<String, java.awt.Component>>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun getDisplayName(): String = "RubberDuck Live"

    override fun createComponent(): JComponent {
        testButton.addActionListener { doTestConnection() }

        // Build settings rows for search/filter
        allSettingRows.clear()
        allSettingRows += listOf(
            "Backend URL" to backendUrlField,
            "Dashboard URL" to dashboardUrlField,
            "Send events to dashboard" to sendEventsBox,
            "Intervention frequency" to frequencyBox,
            "Strict privacy" to privacyBox,
            "Enable inline completion" to inlineCompletionBox,
            "Enable Mock Mode" to mockModeBox,
            "Show anti-pattern indicators in project view" to antiPatternRadarBox,
            "LLM provider/model" to providerLabel,
            "Backend DEMO_MOCK" to demoMockLabel,
            "Test connection" to testButton,
            "Connection status" to connectionStatus
        )

        val formBuilder = FormBuilder.createFormBuilder()
            .addLabeledComponent("Search:", searchField, 1, false)
            .addLabeledComponent("Backend URL:", backendUrlField, 1, false)
            .addLabeledComponent("Dashboard URL:", dashboardUrlField, 1, false)
            .addComponent(sendEventsBox, 1)
            .addLabeledComponent("Intervention frequency:", frequencyBox, 1, false)
            .addComponent(privacyBox, 1)
            .addComponent(inlineCompletionBox, 1)
            .addComponent(mockModeBox, 1)
            .addComponent(antiPatternRadarBox, 1)
            .addSeparator()
            .addLabeledComponent("LLM provider/model:", providerLabel, 1, false)
            .addLabeledComponent("Backend DEMO_MOCK:", demoMockLabel, 1, false)
            .addComponent(testButton, 1)
            .addComponent(connectionStatus, 1)
            .addSeparator()
            // --- Chat (Phase 4 MVP row set) ---
            .addComponent(JBLabel("<html><b>Chat</b></html>"), 1)
            .addComponent(chatEnabledBox, 1)
            .addLabeledComponent("Default chat model:", chatModelCombo, 1, false)
            .addLabeledComponent("Available models (comma-separated):", chatAvailableModelsField, 1, false)
            .addLabeledComponent("Max history tokens:", chatMaxHistorySpinner, 1, false)
            .addComponent(chatAttachOpenFilesBox, 1)
            .addComponent(chatAttachDiagnosticsBox, 1)
            .addComponentFillVertically(JPanel(), 0)
        val panel: JPanel = formBuilder.panel

        // Search filter logic
        searchField.toolTipText = "Type to filter settings"
        searchField.addKeyListener(object : KeyAdapter() {
            override fun keyReleased(e: KeyEvent) {
                val query = searchField.text.trim().lowercase()
                for ((label, comp) in allSettingRows) {
                    val visible = query.isEmpty() || label.lowercase().contains(query)
                    comp.parent?.let { it.isVisible = visible }
                }
            }
        })

        reset()
        return panel
    }

    override fun isModified(): Boolean {
        val s = RubberDuckSettings.getInstance().state
        return backendUrlField.text.trim() != s.backendUrl ||
            dashboardUrlField.text.trim() != s.dashboardUrl ||
            sendEventsBox.isSelected != s.sendEventsToDashboard ||
            (frequencyBox.selectedItem as InterventionFrequency) != s.interventionFrequency ||
            privacyBox.isSelected != s.privacyMode ||
            inlineCompletionBox.isSelected != s.enableInlineCompletion ||
            mockModeBox.isSelected != s.demoMockDisplay ||
            antiPatternRadarBox.isSelected != s.showAntiPatternRadar ||
            chatEnabledBox.isSelected != s.chatEnabled ||
            currentChatModel() != s.chatModel ||
            parseModelList(chatAvailableModelsField.text) != s.chatAvailableModels.toList() ||
            (chatMaxHistorySpinner.value as Int) != s.chatMaxHistoryTokens ||
            chatAttachOpenFilesBox.isSelected != s.chatAttachOpenFiles ||
            chatAttachDiagnosticsBox.isSelected != s.chatAttachDiagnostics
    }

    override fun apply() {
        val s = RubberDuckSettings.getInstance().state
        s.backendUrl = backendUrlField.text.trim().ifEmpty { defaultBackendUrl() }
        s.dashboardUrl = dashboardUrlField.text.trim().ifEmpty { defaultDashboardUrl() }
        s.sendEventsToDashboard = sendEventsBox.isSelected
        s.interventionFrequency = frequencyBox.selectedItem as InterventionFrequency
        s.privacyMode = privacyBox.isSelected
        s.enableInlineCompletion = inlineCompletionBox.isSelected
        s.demoMockDisplay = mockModeBox.isSelected
        s.showAntiPatternRadar = antiPatternRadarBox.isSelected
        s.chatEnabled = chatEnabledBox.isSelected
        val parsedModels = parseModelList(chatAvailableModelsField.text)
        s.chatAvailableModels = parsedModels.toMutableList().ifEmpty {
            mutableListOf("gpt-5.4", "gpt-5", "gpt-4o-mini", "gpt-4o", "claude-3-5-sonnet-20241022", "llama3.1:8b", "qwen2.5-coder:7b")
        }
        s.chatModel = currentChatModel().ifEmpty { "gpt-5.4" }
        // Ensure the selected model is also present in the list.
        if (s.chatAvailableModels.none { it.equals(s.chatModel, ignoreCase = true) }) {
            s.chatAvailableModels.add(s.chatModel)
        }
        s.chatMaxHistoryTokens = chatMaxHistorySpinner.value as Int
        s.chatAttachOpenFiles = chatAttachOpenFilesBox.isSelected
        s.chatAttachDiagnostics = chatAttachDiagnosticsBox.isSelected
    }

    override fun reset() {
        val s = RubberDuckSettings.getInstance().state
        backendUrlField.text = s.backendUrl
        dashboardUrlField.text = s.dashboardUrl
        sendEventsBox.isSelected = s.sendEventsToDashboard
        frequencyBox.selectedItem = s.interventionFrequency
        privacyBox.isSelected = s.privacyMode
        inlineCompletionBox.isSelected = s.enableInlineCompletion
        mockModeBox.isSelected = s.demoMockDisplay
        antiPatternRadarBox.isSelected = s.showAntiPatternRadar
        providerLabel.text = if (s.lastSeenLlmProvider.isNotEmpty())
            "${s.lastSeenLlmProvider} / ${s.lastSeenLlmModel}" else "(not probed yet)"
        demoMockLabel.text = if (s.demoMockDisplay) "ON" else "off"
        chatEnabledBox.isSelected = s.chatEnabled
        // Rebuild combobox from current available-models list.
        chatModelCombo.removeAllItems()
        for (m in s.chatAvailableModels) chatModelCombo.addItem(m)
        chatModelCombo.selectedItem = s.chatModel
        chatAvailableModelsField.text = s.chatAvailableModels.joinToString(", ")
        chatMaxHistorySpinner.value = s.chatMaxHistoryTokens
        chatAttachOpenFilesBox.isSelected = s.chatAttachOpenFiles
        chatAttachDiagnosticsBox.isSelected = s.chatAttachDiagnostics
    }

    /** Read the current value of the editable model combo (selection or typed text). */
    private fun currentChatModel(): String {
        val edited = (chatModelCombo.editor?.item as? String)?.trim().orEmpty()
        if (edited.isNotEmpty()) return edited
        return (chatModelCombo.selectedItem as? String)?.trim().orEmpty()
    }

    private fun parseModelList(text: String): List<String> =
        text.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    private fun doTestConnection() {
        connectionStatus.text = "Pinging…"
        scope.launch {
            val client = BackendClient.getInstance()
            val url = backendUrlField.text.trim().trimEnd('/')
            val result = runCatching { client.health("$url/api/v1") }
            val text = result.fold(
                onSuccess = { h ->
                    val s = RubberDuckSettings.getInstance().state
                    s.lastSeenLlmProvider = h.llm_provider
                    s.lastSeenLlmModel = h.llm_model
                    s.demoMockDisplay = h.demo_mock
                    "OK — ${h.llm_provider}/${h.llm_model} (demo_mock=${h.demo_mock})"
                },
                onFailure = { "FAILED — ${it.message ?: it::class.simpleName}" },
            )
            withContext(Dispatchers.Main) {
                SwingUtilities.invokeLater {
                    connectionStatus.text = text
                    reset()
                }
            }
        }
    }
}
