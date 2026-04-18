package com.rubberduck.live.chat

import com.intellij.ide.ui.LafManager
import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.registry.Registry
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * JCEF-hosted chat panel backed by the shared `chat-webui/` bundle.
 *
 * The panel:
 *   1. Extracts the web bundle to a temp directory (see [ChatResourceHandler]).
 *   2. Creates a [JBCefBrowser] pointing at the extracted `index.html`.
 *   3. Injects the `window.rubberduck.*` host bridge (see [ChatBridge])
 *      on every `onLoadStart` so the web UI can start calling it as soon
 *      as `app.js` runs.
 *   4. Syncs the document `data-theme` attribute with the IDE LaF so
 *      Darcula↔Light toggles look native.
 *   5. Registers itself in [ChatPanelRegistry] so IDE `AnAction`s can
 *      dispatch into `window.rubberduckUI.execContextAction(name)`
 *      without walking the tool-window tree.
 *
 * If JCEF isn't supported on the current JBR we render a tiny explainer
 * with a "How to enable JCEF" hint — we intentionally don't fall back
 * to a Swing chat UI because the whole point of this tab is the rich
 * web experience.
 */
class ChatBrowserPanel(
    val project: Project,
) : JBPanel<ChatBrowserPanel>(BorderLayout()), Disposable {

    private val log = Logger.getInstance(ChatBrowserPanel::class.java)

    private val browser: JBCefBrowser? =
        if (JBCefApp.isSupported()) JBCefBrowser.createBuilder().build() else null

    private val bridge: ChatBridge? = browser?.let {
        ChatBridge(project, it, ::executeJs)
    }

    init {
        if (browser == null || bridge == null) {
            add(buildJcefUnsupportedFallback(), BorderLayout.CENTER)
        } else {
            Disposer.register(this, browser)
            // Swallow-all Disposer entry so the bridge's JBCefJSQuery
            // instances die with us even if the panel is removed from
            // the tool window before the browser.
            Disposer.register(this) { bridge.dispose() }

            setupLoadHandler(browser, bridge)
            setupThemeListener()
            add(browser.component, BorderLayout.CENTER)
            browser.loadURL(ChatResourceHandler.indexFileUrl())

            ChatPanelRegistry.getInstance(project).register(this)
        }
    }

    // ----------------------------------------------------------- JS bridging

    /** Run [code] on the CEF browser thread (off-EDT is fine; JCEF posts it internally). */
    fun executeJs(code: String) {
        val b = browser ?: return
        ApplicationManager.getApplication().invokeLater {
            val url = runCatching { b.cefBrowser.url }.getOrNull() ?: ""
            b.cefBrowser.executeJavaScript(code, url, 0)
        }
    }

    /**
     * Dispatch a high-level context action into the web UI — mirrors the
     * `window.execContextAction(name, payload?)` pattern used by the
     * JetBrains Claude GUI reference so every IDE keybinding is a
     * one-liner on the Kotlin side.
     */
    fun executeContextAction(name: String, payload: String? = null) {
        val js = if (payload == null) {
            "if(window.rubberduckUI && window.rubberduckUI.execContextAction){window.rubberduckUI.execContextAction(${jsString(name)});}"
        } else {
            "if(window.rubberduckUI && window.rubberduckUI.execContextAction){window.rubberduckUI.execContextAction(${jsString(name)}, ${jsString(payload)});}"
        }
        executeJs(js)
    }

    // ----------------------------------------------------------- Internals

    private fun setupLoadHandler(browser: JBCefBrowser, bridge: ChatBridge) {
        val bootstrap = bridge.buildBootstrapScript()
        browser.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadStart(b: CefBrowser?, frame: CefFrame?, transitionType: org.cef.network.CefRequest.TransitionType?) {
                if (frame?.isMain == true) {
                    // Inject the bridge immediately so the web UI's `(window.rubberduck || null)`
                    // check succeeds before `app.js` does its feature-detect.
                    b?.executeJavaScript(bootstrap, b.url, 0)
                    // Seed the current theme as well.
                    b?.executeJavaScript(
                        "document.body && (document.body.dataset.theme = '${currentTheme()}');",
                        b.url, 0,
                    )
                }
            }

            override fun onLoadEnd(b: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
                if (frame?.isMain == true) {
                    log.info("Chat web UI loaded (HTTP $httpStatusCode): ${b?.url}")
                    // Re-apply theme post-load in case the body was replaced.
                    b?.executeJavaScript(
                        "document.body && (document.body.dataset.theme = '${currentTheme()}');",
                        b.url, 0,
                    )
                }
            }
        }, browser.cefBrowser)
    }

    private fun setupThemeListener() {
        val connection = ApplicationManager.getApplication().messageBus.connect(this)
        connection.subscribe(LafManagerListener.TOPIC, LafManagerListener {
            executeJs("document.body && (document.body.dataset.theme = '${currentTheme()}');")
        })
        // Defensive: keep a ref to the manager so the connection stays alive.
        runCatching { LafManager.getInstance() }
    }

    private fun currentTheme(): String = if (ChatBridge.isDarkTheme()) "dark" else "light"

    private fun jsString(s: String): String {
        val escaped = s
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "")
        return "'$escaped'"
    }

    private fun buildJcefUnsupportedFallback(): JPanel {
        val panel = JPanel(BorderLayout())
        val label = JBLabel(
            "<html><div style='text-align:center; padding:24px;'>" +
                "<b>JCEF (embedded Chromium) is disabled in this IDE.</b><br/>" +
                "RubberDuck Chat needs JCEF to render its streaming web UI.<br/><br/>" +
                "Enable it via <i>Help → Find Action… → Registry… → ide.browser.jcef.enabled</i>, " +
                "then restart the IDE." +
                "</div></html>",
            SwingConstants.CENTER,
        )
        panel.add(label, BorderLayout.CENTER)
        val actionBar = JPanel(FlowLayout(FlowLayout.CENTER))
        val openRegistry = JButton("Open Registry")
        openRegistry.addActionListener {
            // Best-effort: flip the flag at runtime so the user just has to
            // restart the IDE. If the registry key doesn't exist yet on this
            // platform version, the user still sees the instructions above.
            runCatching {
                val key = Registry.get("ide.browser.jcef.enabled")
                key.setValue(true)
            }
        }
        actionBar.add(openRegistry)
        panel.add(actionBar, BorderLayout.SOUTH)
        return panel
    }

    override fun dispose() {
        runCatching { ChatPanelRegistry.getInstance(project).unregister(this) }
        // Child disposables (browser, bridge) auto-cleaned via Disposer.register above.
    }

    companion object {
        fun isSupported(): Boolean = runCatching { JBCefApp.isSupported() }.getOrDefault(false)
    }
}
