package com.rubberduck.live.toolwindow.chat

import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.fileTypes.UnknownFileType
import com.intellij.openapi.project.Project
import java.awt.Color
import java.awt.Font

/**
 * Turn a fenced-block body into an HTML fragment of coloured `<span>` runs
 * using the IntelliJ Platform's own syntax highlighters — the same lexers
 * that colour the user's editor. Colours come from the active
 * [EditorColorsScheme] so the chat bubble automatically matches whatever
 * theme the developer is running (Darcula, Light, High Contrast, …).
 *
 * Returns a plain HTML-escaped + `<br/>`-joined fragment when no highlighter
 * is registered for [language] (for example, Python isn't bundled in
 * IntelliJ IDEA Community — the plugin would need to be installed for it
 * to light up). The caller can wrap the returned fragment in a `<pre>` /
 * `<div>` without any fallback branch.
 */
object CodeHighlighter {

    /** HTML-escaped plain render, for callers that hit the "no highlighter" path. */
    fun plain(code: String): String =
        htmlEscape(code).replace("\n", "<br/>")

    /** The editor scheme's default background — use for the surrounding `<pre>`. */
    fun editorBackground(): Color =
        EditorColorsManager.getInstance().globalScheme.defaultBackground

    /** The editor scheme's default foreground — use for untyped tokens / fallback text. */
    fun editorForeground(): Color =
        EditorColorsManager.getInstance().globalScheme.defaultForeground

    fun highlightToHtml(code: String, language: String?, project: Project?): String {
        val fileType = fileTypeFor(language) ?: return plain(code)
        val highlighter = SyntaxHighlighterFactory.getSyntaxHighlighter(
            fileType, project, null,
        ) ?: return plain(code)

        val scheme = EditorColorsManager.getInstance().globalScheme
        val lexer: Lexer = highlighter.highlightingLexer
        lexer.start(code)

        val sb = StringBuilder()
        while (lexer.tokenType != null) {
            val tokenType = lexer.tokenType!!
            val tokenText = code.substring(lexer.tokenStart, lexer.tokenEnd)
            val keys = highlighter.getTokenHighlights(tokenType)
            val attrs = mergedAttrs(keys, scheme)
            val escaped = htmlEscape(tokenText).replace("\n", "<br/>")
            if (attrs == null || (attrs.foregroundColor == null && attrs.fontType == Font.PLAIN)) {
                sb.append(escaped)
            } else {
                sb.append("<span style=\"")
                attrs.foregroundColor?.let {
                    sb.append("color: ").append(hex(it)).append(';')
                }
                if (attrs.fontType and Font.BOLD != 0) sb.append("font-weight: bold;")
                if (attrs.fontType and Font.ITALIC != 0) sb.append("font-style: italic;")
                sb.append("\">").append(escaped).append("</span>")
            }
            lexer.advance()
        }
        return sb.toString()
    }

    /** CSS hex colour (``#rrggbb``) for a [Color] — drops alpha (not supported in HTMLEditorKit CSS). */
    fun hex(c: Color): String = "#%06X".format(c.rgb and 0xFFFFFF)

    private fun mergedAttrs(
        keys: Array<TextAttributesKey>,
        scheme: EditorColorsScheme,
    ): TextAttributes? {
        var result: TextAttributes? = null
        for (key in keys) {
            val a = scheme.getAttributes(key) ?: continue
            result = if (result == null) a else TextAttributes.merge(result, a)
        }
        return result
    }

    /**
     * Map an LLM-supplied language tag to a concrete [FileType].
     * Returns null when the sandbox / user IDE doesn't have a highlighter
     * registered for that language — the caller falls back to plain render.
     */
    private fun fileTypeFor(language: String?): FileType? {
        if (language.isNullOrBlank()) return null
        val ext = extensionFor(language.lowercase()) ?: return null
        val ft = FileTypeManager.getInstance().getFileTypeByExtension(ext)
        return if (ft is UnknownFileType) null else ft
    }

    private fun extensionFor(lang: String): String? = when (lang) {
        "python", "py" -> "py"
        "kotlin", "kt", "kts" -> "kt"
        "java" -> "java"
        "javascript", "js", "node" -> "js"
        "typescript", "ts" -> "ts"
        "tsx" -> "tsx"
        "jsx" -> "jsx"
        "rust", "rs" -> "rs"
        "go", "golang" -> "go"
        "ruby", "rb" -> "rb"
        "php" -> "php"
        "csharp", "cs", "c#" -> "cs"
        "cpp", "c++", "cxx", "cc" -> "cpp"
        "c", "h" -> "c"
        "swift" -> "swift"
        "scala" -> "scala"
        "groovy" -> "groovy"
        "html", "htm" -> "html"
        "xml" -> "xml"
        "css" -> "css"
        "scss", "sass" -> "scss"
        "json" -> "json"
        "yaml", "yml" -> "yaml"
        "toml" -> "toml"
        "sql" -> "sql"
        "sh", "bash", "shell", "zsh" -> "sh"
        "ps1", "powershell" -> "ps1"
        "md", "markdown" -> "md"
        "dockerfile", "docker" -> "dockerfile"
        else -> null
    }
}
