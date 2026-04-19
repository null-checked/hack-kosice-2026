package com.rubberduck.live.skills

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.CopyOnWriteArrayList
import java.util.regex.Pattern
class SkillsLoader(private val project: Project, private val skillsDir: Path) {
    private val skills = CopyOnWriteArrayList<SkillDefinition>()
    private val yamlPattern = Pattern.compile("^---\\r?\\n(.*?)\\r?\\n---\\r?\\n?(.*)$", Pattern.DOTALL)

    init {
        copyBuiltinSkillsIfFirstRun()
        loadSkills()
        watchSkillsDirectory()
    }

    fun getAllSkills(): List<SkillDefinition> = skills.toList()

    fun getSkill(id: String): SkillDefinition? = skills.find { it.id == id }

    /**
     * Copy built-in skills from classpath resources to .skills/ directory on first run.
     * Each skill is copied from classpath:/skills/[name].md to .skills/[name]/SKILL.md.
     */
    private fun copyBuiltinSkillsIfFirstRun() {
        if (Files.exists(skillsDir) && skillsDir.toFile().listFiles()?.isNotEmpty() == true) {
            return // Directory exists and has content, skip copying
        }

        // Create .skills directory if it doesn't exist
        if (!Files.exists(skillsDir)) {
            Files.createDirectories(skillsDir)
        }

        // Read skill definitions from classpath resources
        val resourceFiles = listOf(
            "test-generator.md",
            "refactor-suggester.md",
            "code-explainer.md",
            "socratic-coach.md"
        )

        for (resourceFile in resourceFiles) {
            try {
                val skillName = resourceFile.removeSuffix(".md")
                val content = readClasspathResource("/skills/$resourceFile") ?: continue
                val skillDir = skillsDir.resolve(skillName)
                if (!Files.exists(skillDir)) {
                    Files.createDirectories(skillDir)
                }
                val skillFile = skillDir.resolve("SKILL.md")
                if (!Files.exists(skillFile)) {
                    Files.writeString(skillFile, content)
                }
            } catch (_: IOException) {
                // Skip if we can't write the skill
            }
        }
    }

    /** Read a file from classpath resources. */
    private fun readClasspathResource(path: String): String? {
        return javaClass.getResourceAsStream(path)?.use { stream ->
            stream.bufferedReader().readText()
        }
    }

    private fun loadSkills() {
        skills.clear()
        if (!Files.exists(skillsDir)) return

        // Scan for subdirectories containing SKILL.md
        Files.list(skillsDir).use { paths ->
            paths.filter { Files.isDirectory(it) }.forEach { skillDir ->
                val skillFile = skillDir.resolve("SKILL.md")
                if (Files.exists(skillFile) && Files.isRegularFile(skillFile)) {
                    try {
                        val content = Files.readString(skillFile)
                        val matcher = yamlPattern.matcher(content)
                        if (matcher.find()) {
                            val frontmatter = matcher.group(1)
                            val body = matcher.group(2)
                            val meta = parseFrontmatter(frontmatter)
                            val id = skillDir.fileName.toString()
                            val name = meta["name"] ?: id
                            val description = meta["description"] ?: ""
                            val trigger = meta["trigger"] ?: ""
                            skills.add(
                                SkillDefinition(
                                    id = id,
                                    name = name,
                                    description = description,
                                    trigger = trigger,
                                    content = content,
                                    filePath = skillFile
                                )
                            )
                        }
                    } catch (_: IOException) {
                        // Skip files that can't be read
                    }
                }
            }
        }
    }

    private fun parseFrontmatter(frontmatter: String): Map<String, String> {
        return frontmatter.lines()
            .mapNotNull { line ->
                val idx = line.indexOf(":")
                if (idx > 0) {
                    val key = line.substring(0, idx).trim()
                    val value = line.substring(idx + 1).trim()
                    key to value
                } else null
            }.toMap()
    }

    private fun watchSkillsDirectory() {
        val vfs = VirtualFileManager.getInstance()
        vfs.addVirtualFileListener(object : com.intellij.openapi.vfs.VirtualFileListener {
            override fun contentsChanged(event: com.intellij.openapi.vfs.VirtualFileEvent) {
                if (isInSkillsDirectory(event.file.path)) {
                    ApplicationManager.getApplication().invokeLater { loadSkills() }
                }
            }
            override fun fileCreated(event: com.intellij.openapi.vfs.VirtualFileEvent) {
                if (isInSkillsDirectory(event.file.path)) {
                    ApplicationManager.getApplication().invokeLater { loadSkills() }
                }
            }
            override fun fileDeleted(event: com.intellij.openapi.vfs.VirtualFileEvent) {
                if (isInSkillsDirectory(event.file.path)) {
                    ApplicationManager.getApplication().invokeLater { loadSkills() }
                }
            }
        })
    }

    private fun isInSkillsDirectory(path: String): Boolean {
        val normalizedPath = path.replace("\\", "/")
        val skillsDirPath = skillsDir.toString().replace("\\", "/")
        return normalizedPath.contains(skillsDirPath)
    }

    companion object {
        fun getDefaultSkillsDir(project: Project): Path =
            project.basePath?.let { Paths.get(it, ".skills") } ?: Paths.get(".skills")
    }
}
