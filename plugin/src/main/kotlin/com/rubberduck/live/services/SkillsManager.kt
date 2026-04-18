package com.rubberduck.live.services

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.rubberduck.live.settings.RubberDuckSettings
import com.rubberduck.live.skills.SkillDefinition
import com.rubberduck.live.skills.SkillsLoader

/**
 * Thin facade over [SkillsLoader] that manages enabled/disabled state
 * via [RubberDuckSettings.enabledSkills]. All skill definitions come
 * from markdown files loaded by SkillsLoader — no hardcoded enum or list.
 */
@Service(Service.Level.PROJECT)
class SkillsManager(private val project: Project) {

    private val skillsLoader: SkillsLoader by lazy {
        val skillsDir = SkillsLoader.getDefaultSkillsDir(project)
        SkillsLoader(project, skillsDir)
    }

    private val settings: RubberDuckSettings
        get() = RubberDuckSettings.getInstance()

    /** Returns all loaded skill definitions. */
    fun getAllSkills(): List<SkillDefinition> = skillsLoader.getAllSkills()

    /** Returns only the skills that are currently enabled. */
    fun getEnabledSkills(): List<SkillDefinition> =
        skillsLoader.getAllSkills().filter { isEnabled(it.name) }

    /** Check if a skill (by its frontmatter `name`) is enabled. */
    fun isEnabled(skillName: String): Boolean =
        settings.state.enabledSkills.contains(skillName)

    /** Enable or disable a skill by its frontmatter `name`. */
    fun setEnabled(skillName: String, enabled: Boolean) {
        if (enabled) {
            settings.state.enabledSkills.add(skillName)
        } else {
            settings.state.enabledSkills.remove(skillName)
        }
    }

    /** Toggle the enabled state of a skill. */
    fun toggleSkill(skillName: String) {
        setEnabled(skillName, !isEnabled(skillName))
    }

    /** Look up a skill by its frontmatter `name`. */
    fun getSkillByName(name: String): SkillDefinition? =
        skillsLoader.getAllSkills().find { it.name == name }

    companion object {
        fun getInstance(project: Project): SkillsManager =
            project.getService(SkillsManager::class.java)
    }
}
