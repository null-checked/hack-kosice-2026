package com.rubberduck.live.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.rubberduck.live.services.SkillsManager
import com.rubberduck.live.skills.SkillDefinition
import com.rubberduck.live.skills.SkillsLoader
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.ActionEvent
import javax.swing.*

class SkillsToolWindowPanel(project: Project, skillsLoader: SkillsLoader) : JPanel(BorderLayout()) {
    private val skillsManager = SkillsManager.getInstance(project)
    private val listModel = DefaultListModel<SkillEntry>()
    private val skillsList = JBList(listModel)
    private val refreshButton = JButton("Refresh")
    private val runButton = JButton("Run Skill")
    private val openButton = JButton("Open Skill File")

    data class SkillEntry(
        val skill: SkillDefinition,
        var enabled: Boolean
    )

    init {
        border = JBUI.Borders.empty(10)
        preferredSize = Dimension(400, 300)
        skillsList.cellRenderer = SkillCellRenderer()
        add(JBScrollPane(skillsList), BorderLayout.CENTER)

        val buttonPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(refreshButton)
            add(Box.createHorizontalStrut(8))
            add(runButton)
            add(Box.createHorizontalStrut(8))
            add(openButton)
        }
        add(buttonPanel, BorderLayout.SOUTH)

        refreshButton.addActionListener { reloadSkills(skillsLoader) }
        runButton.addActionListener { runSelectedSkill() }
        openButton.addActionListener { openSelectedSkillFile(project) }

        reloadSkills(skillsLoader)
    }

    private fun reloadSkills(skillsLoader: SkillsLoader) {
        listModel.clear()
        skillsLoader.getAllSkills().forEach { skill ->
            listModel.addElement(SkillEntry(skill, skillsManager.isEnabled(skill.name)))
        }
    }

    private fun runSelectedSkill() {
        val entry = skillsList.selectedValue ?: return
        Messages.showDialog(
            this,
            entry.skill.content,
            "Skill: ${entry.skill.name}",
            arrayOf("OK"),
            0,
            null
        )
    }

    private fun openSelectedSkillFile(project: Project) {
        val entry = skillsList.selectedValue ?: return
        val vfs = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
        val vf = vfs.findFileByIoFile(entry.skill.filePath.toFile())
        if (vf != null) {
            com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).openFile(vf, true)
        } else {
            Messages.showErrorDialog(this, "Skill file not found.", "Error")
        }
    }

    private class SkillCellRenderer : JLabel(), ListCellRenderer<SkillEntry> {
        override fun getListCellRendererComponent(
            list: JList<out SkillEntry>,
            value: SkillEntry?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): java.awt.Component {
            text = if (value != null) {
                val enabledIcon = if (value.enabled) "✅" else "❌"
                "<html>$enabledIcon <b>${value.skill.name}</b> <i>(${value.skill.trigger})</i><br>${value.skill.description}</html>"
            } else ""
            background = if (isSelected) list.selectionBackground else list.background
            foreground = if (isSelected) list.selectionForeground else list.foreground
            isOpaque = true
            border = JBUI.Borders.empty(4, 8)
            return this
        }
    }
}
