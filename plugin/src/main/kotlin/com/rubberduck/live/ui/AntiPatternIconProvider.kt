package com.rubberduck.live.ui

import com.intellij.ide.FileIconProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.vfs.VirtualFile
import com.rubberduck.live.services.AntiPatternRadar
import com.rubberduck.live.settings.RubberDuckSettings
import javax.swing.Icon

/**
 * Decorates files in the Project View with a small colored overlay when the
 * [AntiPatternRadar] considers them a frustration hot-spot (≥ 3 stuck
 * moments in the last 24 h → yellow; ≥ 6 → red).
 *
 * Registered as a `com.intellij.fileIconProvider` extension point in
 * plugin.xml. Disabled via Settings → Tools → RubberDuck Live → "Show
 * anti-pattern indicators in project view".
 */
class AntiPatternIconProvider : FileIconProvider {
    override fun getIcon(file: VirtualFile, flags: Int, project: Project?): Icon? {
        if (project == null) return null
        if (!RubberDuckSettings.getInstance().showAntiPatternRadar) return null
        val radar = AntiPatternRadar.getInstance(project)
        val severity = radar.getFileSeverity(file) ?: return null
        return when (severity) {
            AntiPatternRadar.Severity.HIGH ->
                IconLoader.getIcon("/icons/antipattern_red_dot.svg", javaClass)
            AntiPatternRadar.Severity.MEDIUM ->
                IconLoader.getIcon("/icons/antipattern_yellow_dot.svg", javaClass)
            AntiPatternRadar.Severity.LOW -> null  // too weak to annotate
        }
    }
}
