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
 * Resolve the first-run default URL with no hardcoded host/port.
 * Priority: JVM `-Drubberduck.*` property → environment variable → fallback.
 * The Gradle `runIde` task populates the system properties from
 * `backend/.env`, so dev builds always match whatever the backend is
 * configured to.
 */
private fun resolveDefault(
    systemPropKey: String,
    envKey: String,
    fallback: String,
): String =
    System.getProperty(systemPropKey)?.takeIf { it.isNotBlank() }
        ?: System.getenv(envKey)?.takeIf { it.isNotBlank() }
        ?: fallback

internal fun defaultBackendUrl(): String =
    resolveDefault(
        systemPropKey = "rubberduck.backend.url",
        envKey = "RUBBERDUCK_BACKEND_URL",
        fallback = "http://127.0.0.1:8765",
    )

internal fun defaultDashboardUrl(): String =
    resolveDefault(
        systemPropKey = "rubberduck.dashboard.url",
        envKey = "RUBBERDUCK_DASHBOARD_URL",
        fallback = "http://localhost:3000",
    )

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
        // First-run defaults are resolved from (in priority order):
        //   1. JVM system properties set by Gradle's `runIde` task, which
        //      reads backend/.env at configure time — single source of truth.
        //   2. Environment variables for users running the built plugin.
        //   3. `127.0.0.1:8765` / `localhost:3000` as last-resort fallbacks.
        // After first run, the values are persisted and user-editable via
        // Settings → Tools → RubberDuck Live.
        var backendUrl: String = defaultBackendUrl()
        var dashboardUrl: String = defaultDashboardUrl()
        var sendEventsToDashboard: Boolean = true
        var interventionFrequency: InterventionFrequency = InterventionFrequency.NORMAL
        var privacyMode: Boolean = false
        var demoMockDisplay: Boolean = false // mirrors backend /health demo_mock
        var showAntiPatternRadar: Boolean = true
        // Displayed in the settings panel as read-only diagnostics.
        var lastSeenLlmProvider: String = ""
        var lastSeenLlmModel: String = ""
        // Inline completion (Tier C). ON by default now that the feature is
        // actually reliable — an off-by-default toggle made users conclude
        // the feature didn't exist. Opt-out in Settings → Tools → RubberDuck.
        var enableInlineCompletion: Boolean = true
        // Phase 9.C — markdown-based Skills system. Project-scoped skills
        // live under `$projectDir/<skillsDirectory>/<skill>/SKILL.md` and
        // are loaded at runtime by `SkillsLoader`. `enabledSkills` stores
        // the skill `name:` frontmatter field for each skill the user has
        // toggled on in Settings → Tools → RubberDuck Live — Skills.
        var skillsDirectory: String = ".skills"
        var enabledSkills: MutableSet<String> = mutableSetOf(
            "Test Generator",
            "Refactor Suggester",
            "Code Explainer",
            "Socratic Coach",
        )

        // Phase 4 — embedded chat tool window. The JCEF-hosted web UI
        // reads these via the `window.rubberduck.getConfig()` /
        // `listModels()` bridge methods; the full Configurable UX
        // lands in Step 7, but the fields exist here now so Step 6 can
        // already wire context builders against them without a schema
        // bump.
        var chatEnabled: Boolean = true
        var chatModel: String = "gpt-4o-mini"
        var chatAvailableModels: MutableList<String> = mutableListOf(
            "gpt-4o-mini",
            "gpt-4o",
            "claude-sonnet-4",
            "ollama/llama3.1",
        )
        var chatMaxHistoryTokens: Int = 8000
        var chatAttachOpenFiles: Boolean = false
        var chatAttachDiagnostics: Boolean = false
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
    val dashboardUrl: String get() = state.dashboardUrl.trimEnd('/')
    var showAntiPatternRadar: Boolean
        get() = state.showAntiPatternRadar
        set(value) { state.showAntiPatternRadar = value }

    companion object {
        fun getInstance(): RubberDuckSettings =
            ApplicationManager.getApplication().getService(RubberDuckSettings::class.java)
    }
}
