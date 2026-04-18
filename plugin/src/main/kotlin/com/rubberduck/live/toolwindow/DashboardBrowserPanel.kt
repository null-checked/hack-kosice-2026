package com.rubberduck.live.toolwindow

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.rubberduck.live.settings.RubberDuckSettings
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JPanel

/**
 * JCEF-backed dashboard view for the tool window. Embeds the Next.js dashboard
 * running at `http://localhost:3000` so the in-IDE tool window renders the
 * exact same visuals (wellness score, heatmap, flow-state chart, anti-patterns,
 * stuck timeline, socratic feed) instead of a plain Swing list.
 *
 * Gracefully degrades: if [JBCefApp.isSupported] returns false (rare on CE
 * 2024.2+ but possible on older JBR builds), callers fall back to the plain
 * [RubberDuckToolWindow] panel — see [RubberDuckToolWindowFactory].
 */
class DashboardBrowserPanel(parentDisposable: Disposable) : JPanel(BorderLayout()), Disposable {

    private val log = Logger.getInstance(DashboardBrowserPanel::class.java)
    private val browser: JBCefBrowser = JBCefBrowser.createBuilder()
        .setOffScreenRendering(false)
        .build()

    private val statusLabel = JBLabel(" ")

    init {
        Disposer.register(parentDisposable, this)
        Disposer.register(this, browser)

        border = BorderFactory.createEmptyBorder(4, 4, 4, 4)

        // Slim top strip: plugin-side connection hint + reload button. The
        // actual "wellness" visuals come from the embedded dashboard below.
        val topStrip = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2))
        statusLabel.text = "Loading dashboard from " + dashboardUrl() + "…"
        topStrip.add(statusLabel)
        val reloadBtn = JButton("Reload")
        reloadBtn.addActionListener { loadDashboard() }
        topStrip.add(reloadBtn)
        val openInBrowserBtn = JButton("Open in browser")
        openInBrowserBtn.addActionListener {
            com.intellij.ide.BrowserUtil.browse(dashboardUrl())
        }
        topStrip.add(openInBrowserBtn)

        add(topStrip, BorderLayout.NORTH)

        val component = browser.component
        component.preferredSize = Dimension(640, 480)
        add(component, BorderLayout.CENTER)

        loadDashboard()
    }

    private fun dashboardUrl(): String {
        // The dashboard URL is derived from the backend URL's origin — they
        // run on the same host, but on different ports (3000 vs 8765). We
        // only override the port; host comes from settings so SSH-forwarded
        // setups still work.
        val backend = RubberDuckSettings.getInstance().backendUrl
        val host = runCatching {
            val u = java.net.URI(backend)
            u.host ?: "localhost"
        }.getOrDefault("localhost")
        return "http://$host:3000"
    }

    private fun loadDashboard() {
        val url = dashboardUrl()
        statusLabel.text = "Loading $url …"
        log.info("Loading RubberDuck dashboard in JCEF: $url")
        browser.loadURL(url)
        statusLabel.text = url
    }

    override fun dispose() {
        // Disposer.register takes care of browser cleanup; this is a no-op
        // hook so callers can pass us to a content factory that expects a
        // Disposable.
    }

    companion object {
        /** Whether JCEF is available on the current JBR. */
        fun isSupported(): Boolean = runCatching { JBCefApp.isSupported() }.getOrDefault(false)
    }
}
