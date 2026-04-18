package com.rubberduck.live.skills

import java.nio.file.Path

data class SkillDefinition(
    val id: String,           // filename without .md
    val name: String,         // from frontmatter
    val description: String,  // from frontmatter
    val trigger: String,      // from frontmatter - what user says to trigger it
    val content: String,      // full markdown content
    val filePath: Path        // source file path
)
