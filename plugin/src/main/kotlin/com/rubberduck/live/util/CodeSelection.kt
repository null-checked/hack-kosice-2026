package com.rubberduck.live.util

import com.intellij.openapi.editor.Editor

/**
 * Language-agnostic heuristics for picking the "function under caret" without
 * needing optional PSI plugins (e.g., Python PSI is not bundled with IntelliJ
 * IDEA Community).
 */
object CodeSelection {

    data class Selection(
        val code: String,
        val functionName: String?,
        val startLine: Int,
        val endLine: Int,
        val language: String,
    )

    private val pythonDef = Regex("""^\s*def\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(""")
    private val kotlinFun = Regex("""^\s*(?:(?:public|private|protected|internal|open|suspend|inline)\s+)*fun\s+([A-Za-z_][A-Za-z0-9_]*)\s*[(<]""")
    private val jsFun = Regex("""^\s*(?:export\s+)?(?:async\s+)?function\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(""")

    /** Extract the enclosing function body around [caretOffset] in [text]. */
    fun extractFunction(text: String, caretOffset: Int, language: String): Selection {
        if (text.isEmpty()) return Selection("", null, 0, 0, language)
        val safeOffset = caretOffset.coerceIn(0, text.length)
        val lines = text.split('\n')
        val caretLine = text.substring(0, safeOffset).count { it == '\n' }
        return when (language) {
            "python" -> extractPython(lines, caretLine)
            "kotlin", "java" -> extractBraceBased(lines, caretLine, kotlinFun, language)
            "javascript", "typescript" -> extractBraceBased(lines, caretLine, jsFun, language)
            else -> extractBraceBased(lines, caretLine, kotlinFun, language)
        }
    }

    /** Convenience overload for editor callsites. */
    fun fromEditor(editor: Editor, language: String): Selection {
        val doc = editor.document
        return extractFunction(doc.text, editor.caretModel.offset, language)
    }

    private fun extractPython(lines: List<String>, caretLine: Int): Selection {
        // Walk up until we find a `def` whose indent is ≤ caret-line indent (or 0).
        var headerLine = -1
        var headerIndent = -1
        var i = caretLine.coerceAtMost(lines.lastIndex)
        while (i >= 0) {
            val m = pythonDef.find(lines[i])
            if (m != null) {
                headerLine = i
                headerIndent = lines[i].indentOf()
                break
            }
            i--
        }
        if (headerLine < 0) return Selection(lines.getOrNull(caretLine).orEmpty(), null, caretLine, caretLine, "python")
        // Body ends when indent drops to ≤ headerIndent (and line is non-blank).
        var end = headerLine
        var j = headerLine + 1
        while (j < lines.size) {
            val line = lines[j]
            if (line.isBlank()) { j++; continue }
            val indent = line.indentOf()
            if (indent <= headerIndent) break
            end = j
            j++
        }
        val name = pythonDef.find(lines[headerLine])?.groupValues?.get(1)
        val code = lines.subList(headerLine, end + 1).joinToString("\n")
        return Selection(code, name, headerLine, end, "python")
    }

    private fun extractBraceBased(
        lines: List<String>,
        caretLine: Int,
        headerRegex: Regex,
        language: String,
    ): Selection {
        // Find nearest function header at/above caret.
        var headerLine = -1
        var i = caretLine.coerceAtMost(lines.lastIndex)
        while (i >= 0) {
            if (headerRegex.containsMatchIn(lines[i])) { headerLine = i; break }
            i--
        }
        if (headerLine < 0) {
            return Selection(lines.getOrNull(caretLine).orEmpty(), null, caretLine, caretLine, language)
        }
        // Find opening brace on or after header; brace-match from there.
        var openLine = headerLine
        var openCol = -1
        searchBrace@ for (k in headerLine until lines.size) {
            val col = lines[k].indexOf('{')
            if (col >= 0) { openLine = k; openCol = col; break@searchBrace }
        }
        if (openCol < 0) {
            // Single-expression Kotlin function (`fun foo() = …`); stop at first blank line.
            var end = headerLine
            var j = headerLine + 1
            while (j < lines.size && lines[j].isNotBlank()) { end = j; j++ }
            val name = headerRegex.find(lines[headerLine])?.groupValues?.get(1)
            return Selection(lines.subList(headerLine, end + 1).joinToString("\n"), name, headerLine, end, language)
        }
        var depth = 0
        var endLine = openLine
        outer@ for (k in openLine until lines.size) {
            val startCol = if (k == openLine) openCol else 0
            val line = lines[k]
            for (c in startCol until line.length) {
                when (line[c]) {
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) { endLine = k; break@outer }
                    }
                }
            }
        }
        val name = headerRegex.find(lines[headerLine])?.groupValues?.get(1)
        val code = lines.subList(headerLine, endLine + 1).joinToString("\n")
        return Selection(code, name, headerLine, endLine, language)
    }

    private fun String.indentOf(): Int {
        var n = 0
        for (c in this) {
            if (c == ' ') n++ else if (c == '\t') n += 4 else break
        }
        return n
    }
}
