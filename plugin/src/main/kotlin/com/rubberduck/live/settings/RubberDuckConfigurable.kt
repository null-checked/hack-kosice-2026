package com.rubberduck.live.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
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

    private val backendUrlField = JBTextField()
    private val sendEventsBox = JBCheckBox("Send events to dashboard")
    private val frequencyBox = ComboBox(InterventionFrequency.values())
    private val privacyBox = JBCheckBox("Strict privacy: never send code off-device")
    private val demoMockLabel = JBLabel("")
    private val providerLabel = JBLabel("")
    private val connectionStatus = JBLabel(" ")
    private val testButton = JButton("Test connection")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun getDisplayName(): String = "RubberDuck Live"

    override fun createComponent(): JComponent {
        testButton.addActionListener { doTestConnection() }
        val panel: JPanel = FormBuilder.createFormBuilder()
            .addLabeledComponent("Backend URL:", backendUrlField, 1, false)
            .addComponent(sendEventsBox, 1)
            .addLabeledComponent("Intervention frequency:", frequencyBox, 1, false)
            .addComponent(privacyBox, 1)
            .addSeparator()
            .addLabeledComponent("LLM provider/model:", providerLabel, 1, false)
            .addLabeledComponent("Backend DEMO_MOCK:", demoMockLabel, 1, false)
            .addComponent(testButton, 1)
            .addComponent(connectionStatus, 1)
            .addComponentFillVertically(JPanel(), 0)
            .panel
        reset()
        return panel
    }

    override fun isModified(): Boolean {
        val s = RubberDuckSettings.getInstance().state
        return backendUrlField.text.trim() != s.backendUrl ||
            sendEventsBox.isSelected != s.sendEventsToDashboard ||
            (frequencyBox.selectedItem as InterventionFrequency) != s.interventionFrequency ||
            privacyBox.isSelected != s.privacyMode
    }

    override fun apply() {
        val s = RubberDuckSettings.getInstance().state
        s.backendUrl = backendUrlField.text.trim().ifEmpty { "http://127.0.0.1:8765" }
        s.sendEventsToDashboard = sendEventsBox.isSelected
        s.interventionFrequency = frequencyBox.selectedItem as InterventionFrequency
        s.privacyMode = privacyBox.isSelected
    }

    override fun reset() {
        val s = RubberDuckSettings.getInstance().state
        backendUrlField.text = s.backendUrl
        sendEventsBox.isSelected = s.sendEventsToDashboard
        frequencyBox.selectedItem = s.interventionFrequency
        privacyBox.isSelected = s.privacyMode
        providerLabel.text = if (s.lastSeenLlmProvider.isNotEmpty())
            "${s.lastSeenLlmProvider} / ${s.lastSeenLlmModel}" else "(not probed yet)"
        demoMockLabel.text = if (s.demoMockDisplay) "ON" else "off"
    }

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
