package com.rubberduck.live.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBPanel
import com.rubberduck.live.services.SkillsManager
import javax.swing.JComponent
import javax.swing.JPanel

class SkillsConfigurable(private val project: Project) : Configurable {
    private lateinit var skillsManager: SkillsManager
    private val checkBoxes = mutableMapOf<String, JBCheckBox>()

    override fun getDisplayName(): String = "RubberDuck Skills"

    override fun createComponent(): JComponent {
        skillsManager = SkillsManager.getInstance(project)
        checkBoxes.clear()
        val panel = JBPanel<JBPanel<*>>()
        panel.layout = javax.swing.BoxLayout(panel, javax.swing.BoxLayout.Y_AXIS)
        for (skill in skillsManager.getAllSkills()) {
            val checkBox = JBCheckBox(skill.name, skillsManager.isEnabled(skill.name))
            checkBox.toolTipText = skill.description
            checkBoxes[skill.name] = checkBox
            val row = JPanel()
            row.layout = javax.swing.BoxLayout(row, javax.swing.BoxLayout.X_AXIS)
            row.add(checkBox)
            row.add(javax.swing.Box.createHorizontalStrut(8))
            row.add(javax.swing.JLabel(skill.description))
            panel.add(row)
        }
        return panel
    }

    override fun isModified(): Boolean {
        val savedSkills = RubberDuckSettings.getInstance().state.enabledSkills
        for ((skillName, checkBox) in checkBoxes) {
            if (savedSkills.contains(skillName) != checkBox.isSelected) {
                return true
            }
        }
        // Also check for skills that exist in settings but not in UI (shouldn't happen, but safe)
        val uiSkills = checkBoxes.filterValues { it.isSelected }.keys
        return savedSkills != uiSkills
    }

    override fun apply() {
        for ((skillName, checkBox) in checkBoxes) {
            skillsManager.setEnabled(skillName, checkBox.isSelected)
        }
    }

    override fun reset() {
        val savedSkills = RubberDuckSettings.getInstance().state.enabledSkills
        for ((skillName, checkBox) in checkBoxes) {
            checkBox.isSelected = savedSkills.contains(skillName)
        }
    }

    override fun getHelpTopic(): String? = null
}
