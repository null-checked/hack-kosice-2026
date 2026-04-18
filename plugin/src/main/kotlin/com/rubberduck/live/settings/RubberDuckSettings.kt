package com.rubberduck.live.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

/** Intervention frequency presets — scale throttle windows inside the coordinator. */
enum class InterventionFrequency { MINIMAL, NORMAL, CHATTY }

/**
 * Persistent user-facing settings for RubberDuck Live.
 *
 * Storage lives in IntelliJ's per-IDE options directory as
 * `rubberduck-live-settings.xml`.
 */
@Service(Service.Level.APP)
@State(
    name = "RubberDuckSettings",
    storages = [Storage("rubberduck-live-settings.xml")],
)
class RubberDuckSettings : PersistentStateComponent<RubberDuckSettings.State> {

    /** DTO holding the actual persisted fields. */
    class State {
        var backendUrl: String = "http://127.0.0.1:8766"
        var sendEventsToDashboard: Boolean = true
        var interventionFrequency: InterventionFrequency = InterventionFrequency.NORMAL
        var privacyMode: Boolean = false
        var demoMockDisplay: Boolean = false // mirrors backend /health demo_mock
        // Displayed in the settings panel as read-only diagnostics.
        var lastSeenLlmProvider: String = ""
        var lastSeenLlmModel: String = ""
        // Inline completion (Tier C). ON by default now that the feature is
        // actually reliable — an off-by-default toggle made users conclude
        // the feature didn't exist. Opt-out in Settings → Tools → RubberDuck.
        var enableInlineCompletion: Boolean = true
    }

    private var state = State()

    override fun getState(): State = state
    override fun loadState(s: State) {
        XmlSerializerUtil.copyBean(s, state)
    }

    // Convenience accessors ---------------------------------------------------

    val backendUrl: String get() = state.backendUrl.trimEnd('/')
    val backendApiBase: String get() = "$backendUrl/api/v1"
    val backendWsUrl: String get() = "ws" + backendApiBase.removePrefix("http") + "/events"

    companion object {
        fun getInstance(): RubberDuckSettings =
            ApplicationManager.getApplication().getService(RubberDuckSettings::class.java)
    }
}
