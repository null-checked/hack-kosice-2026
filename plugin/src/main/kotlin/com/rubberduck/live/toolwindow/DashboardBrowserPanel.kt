package com.rubberduck.live.toolwindow

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefClient
import com.rubberduck.live.settings.RubberDuckSettings
import org.cef.CefApp
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JPanel

/**
 * JCEF-backed dashboard view for the tool window. Embeds the Next.js dashboard
 * running at the URL configured in [RubberDuckSettings] so the in-IDE tool
 * window renders the exact same visuals (wellness score, heatmap, flow-state
 * chart, anti-patterns, stuck timeline, socratic feed) instead of a plain
 * Swing list.
 *
 * Uses **off-screen rendering (OSR)** — historically more reliable than the
 * windowed (native HWND) mode inside dockable tool windows on Windows, where
 * windowed JCEF frequently ends up with a blank area due to z-order / repaint
 * quirks of the embedded native child window.
 *
 * Gracefully degrades: if [JBCefApp.isSupported] returns false (rare on CE
 * 2024.2+ but possible on older JBR builds), callers fall back to the plain
 * [RubberDuckToolWindow] panel — see [RubberDuckToolWindowFactory].
 */
class DashboardBrowserPanel(parentDisposable: Disposable) : JPanel(BorderLayout()), Disposable {

    private val log = Logger.getInstance(DashboardBrowserPanel::class.java)

    private val browser: JBCefBrowser = JBCefBrowser.createBuilder()
        // OSR=true avoids the native-HWND z-order / repaint bug inside
        // dockable tool windows that keeps the dashboard area blank.
        .setOffScreenRendering(true)
        .build()

    private val statusLabel = JBLabel(" ")

    init {
        Disposer.register(parentDisposable, this)
        Disposer.register(this, browser)

        // Surface load errors + success in the status strip so it's obvious
        // when the backend is down vs the dashboard is blank.
        browser.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(b: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
                if (frame?.isMain == true) {
                    log.info("Dashboard loaded (HTTP $httpStatusCode): ${dashboardUrl()}")
                    statusLabel.text = "${dashboardUrl()}  (HTTP $httpStatusCode)"
                }
            }

            override fun onLoadError(
                b: CefBrowser?,
                frame: CefFrame?,
                errorCode: org.cef.handler.CefLoadHandler.ErrorCode?,
                errorText: String?,
                failedUrl: String?,
            ) {
                if (frame?.isMain == true) {
                    log.warn("Dashboard load failed: $failedUrl — $errorCode / $errorText")
                    statusLabel.text = "load failed — $errorCode"
                }
            }
        }, browser.cefBrowser)

        border = BorderFactory.createEmptyBorder(4, 4, 4, 4)

        val topStrip = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2))
        statusLabel.text = "Loading " + dashboardUrl() + " …"
        topStrip.add(statusLabel)
        val reloadBtn = JButton("Reload")
        reloadBtn.addActionListener { loadDashboard() }
        topStrip.add(reloadBtn)
        val openInBrowserBtn = JButton("Open in browser")
        openInBrowserBtn.addActionListener {
            com.intellij.ide.BrowserUtil.browse(dashboardUrl())
        }
        topStrip.add(openInBrowserBtn)
        val devToolsBtn = JButton("DevTools")
        devToolsBtn.toolTipText = "Open Chromium DevTools against the embedded dashboard"
        devToolsBtn.addActionListener {
            runCatching { browser.openDevtools() }
                .onFailure { log.warn("Failed to open DevTools: ${it.message}") }
        }
        topStrip.add(devToolsBtn)

        add(topStrip, BorderLayout.NORTH)

        val component = browser.component
        component.preferredSize = Dimension(640, 480)
        add(component, BorderLayout.CENTER)

        loadDashboard()
    }

    private fun dashboardUrl(): String =
        RubberDuckSettings.getInstance().dashboardUrl

    private fun loadDashboard() {
        val url = dashboardUrl()
        statusLabel.text = "Loading $url …"
        log.info("Loading RubberDuck dashboard in JCEF (OSR): $url")
        browser.loadURL(url)
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
