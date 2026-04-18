package com.rubberduck.live.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.components.service
import com.intellij.ui.IconManager
import com.intellij.ui.icons.RowIcon
import com.intellij.openapi.fileTypes.FileIconProvider
import com.rubberduck.live.services.AntiPatternRadar
import com.rubberduck.live.settings.RubberDuckSettings
import javax.swing.Icon

class AntiPatternIconProvider : FileIconProvider {
    override fun getIcon(file: VirtualFile, flags: Int, project: Project?): Icon? {
        if (project == null) return null
        if (!RubberDuckSettings.instance.showAntiPatternRadar) return null
        val radar = project.service<AntiPatternRadar>()
        val severity = radar.getFileSeverity(file) ?: return null
        val overlay: Icon = when (severity) {
            AntiPatternRadar.Severity.HIGH -> IconLoader.getIcon("/icons/antipattern_red_dot.svg", javaClass)
            AntiPatternRadar.Severity.MEDIUM -> IconLoader.getIcon("/icons/antipattern_yellow_dot.svg", javaClass)
            else -> return null
        }
        val baseIcon = IconManager.getInstance().getIcon(file, flags, project)
        if (baseIcon == null) return overlay
        val rowIcon = RowIcon(baseIcon, overlay)
        rowIcon.setIcon(overlay, 1)
        return rowIcon
    }
}