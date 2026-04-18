package com.rubberduck.live.toolwindow.chat

import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBPanel
import com.rubberduck.live.model.CodeEdit
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.util.UUID
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JTextPane
import javax.swing.text.html.HTMLEditorKit

/**
 * Swing renderings for a single chat turn. Two flavours:
 *   • [UserMessageComponent] — the user's own message, rendered as a simple
 *     bubble with a cyan accent border (matches the existing transcript
 *     aesthetic in [com.rubberduck.live.toolwindow.RubberDuckToolWindowFactory]).
 *   • [AssistantMessageComponent] — the assistant's reply plus zero-or-more
 *     [com.rubberduck.live.toolwindow.chat.CodeEditCardComponent]s, one per edit.
 *
 * Both components are `BoxLayout.Y_AXIS`-friendly: they set `alignmentX = 0`
 * and a tall `maximumSize` so they stack cleanly without gobbling horizontal
 * space. The parent provides the scroll pane (Task 4b).
 */

private const val USER_ACCENT = "#22D3EE"      // cyan-400 — matches the factory's user bubble
private const val ASSIST_ACCENT = "#8B5CF6"    // violet-500 — matches the factory's assistant bubble
private const val USER_BG = "#0B0F17"
private const val ASSIST_BG = "#1F2937"
private val CARD_HEIGHT_HINT = 80

/**
 * HTML-escape a user-supplied string so it can be safely inlined into the
 * transcript `JTextPane`'s HTML. Single source of truth for the chat transcript
 * escape policy; the tool-window factory no longer carries its own copy.
 *
 * Preserves newlines literally — `renderMarkdown` decides whether a `\n`
 * becomes a `<br/>` (for paragraph text) or stays raw (inside `<pre>` where
 * HTMLEditorKit honours `white-space: pre-wrap`).
 */
internal fun htmlEscape(s: String): String = s
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")

/**
 * Convert a Markdown-lite string to HTML for the chat transcript.
 *
 * Block constructs:
 *   - **Fenced code blocks** — triple-backtick with optional language tag.
 *     Rendered as `<pre>` with `\n` collapsed to `<br/>` so HTMLEditorKit
 *     keeps the whole block as a single paragraph (with `\n`, it splits
 *     into per-line paragraphs and the outer `border` only draws around
 *     the first one — the "boxed first row" artifact).
 *   - **Headings** — `#` through `####` at line start. Rendered as a
 *     bold `<div>` at decreasing font size.
 *   - **Bullet lists** — lines starting with `-`, `*`, or `+` followed by
 *     whitespace. Rendered as a stack of indented `<div>`s with a bullet
 *     glyph (bypasses HTMLEditorKit's flaky `<ul>` defaults).
 *   - **Numbered lists** — `1.`, `2.`, … at line start. Same treatment,
 *     preserving the author's numbering.
 *   - **Paragraphs** — anything else; consecutive lines get `<br/>`
 *     between them, blank lines produce a gap.
 *
 * Inline constructs (inside paragraphs, headings, list items):
 *   - **Inline code** — `` `text` `` → `<code>` chip. Escapes inside.
 *   - **Bold** — `**text**` or `__text__` → `<b>`.
 *   - **Italic** — `*text*` or `_text_` → `<i>`.
 *
 * Everything outside a code span is HTML-escaped. Unmatched backticks are
 * rendered as literal `` ` ``. Inline code takes precedence over bold /
 * italic, so `` `**not bold**` `` stays as code.
 */
internal fun renderMarkdown(text: String): String {
    val sb = StringBuilder()
    val lines = text.lines()
    val fenceOpen = Regex("^\\s*```\\s*([A-Za-z0-9_+\\-]*)\\s*$")
    val fenceClose = Regex("^\\s*```\\s*$")
    val heading = Regex("^(#{1,4})\\s+(.+)$")
    val numbered = Regex("^\\s*(\\d+)\\.\\s+(.+)$")

    var i = 0
    while (i < lines.size) {
        val line = lines[i]

        if (fenceOpen.matches(line)) {
            val body = StringBuilder()
            i++
            while (i < lines.size && !fenceClose.matches(lines[i])) {
                if (body.isNotEmpty()) body.append('\n')
                body.append(lines[i])
                i++
            }
            if (i < lines.size) i++  // consume closing fence
            sb.append(renderCodeBlock(body.toString()))
            continue
        }

        val h = heading.matchEntire(line)
        if (h != null) {
            val level = h.groupValues[1].length
            val fontPx = when (level) { 1 -> 15; 2 -> 13; 3 -> 12; else -> 11 }
            sb.append("<div style=\"font-size: ${fontPx}px; font-weight: bold; ")
                .append("margin: 6px 0 2px 0; color: #F9FAFB;\">")
                .append(renderInline(h.groupValues[2]))
                .append("</div>")
            i++
            continue
        }

        if (isBulletLine(line)) {
            while (i < lines.size && isBulletLine(lines[i])) {
                val content = lines[i].trimStart().drop(1).trimStart()
                sb.append("<div style=\"margin: 0 0 0 12px;\">&bull; ")
                    .append(renderInline(content))
                    .append("</div>")
                i++
            }
            continue
        }

        val numMatch = numbered.matchEntire(line)
        if (numMatch != null) {
            while (i < lines.size) {
                val m = numbered.matchEntire(lines[i]) ?: break
                sb.append("<div style=\"margin: 0 0 0 12px;\">")
                    .append(m.groupValues[1]).append(". ")
                    .append(renderInline(m.groupValues[2]))
                    .append("</div>")
                i++
            }
            continue
        }

        if (line.isBlank()) {
            sb.append("<br/>")
            i++
            continue
        }

        sb.append(renderInline(line))
        val next = if (i + 1 < lines.size) lines[i + 1] else null
        if (next != null && next.isNotBlank() && !isBlockStart(next)) {
            sb.append("<br/>")
        }
        i++
    }
    return sb.toString()
}

/** True for lines that start a new block construct, ending the current paragraph. */
private fun isBlockStart(line: String): Boolean {
    if (line.startsWith("```") || line.startsWith("   ```")) return true
    if (Regex("^#{1,4}\\s").containsMatchIn(line)) return true
    if (isBulletLine(line)) return true
    if (Regex("^\\s*\\d+\\.\\s").containsMatchIn(line)) return true
    return false
}

/** `- `, `* `, or `+ ` at line start — but not `**` (bold) or `*text*` (italic). */
private fun isBulletLine(line: String): Boolean {
    val trimmed = line.trimStart()
    if (trimmed.length < 2) return false
    val first = trimmed[0]
    if (first !in charArrayOf('-', '+', '*')) return false
    if (first == '*' && trimmed.length > 1 && trimmed[1] == '*') return false  // **bold**
    return trimmed[1].isWhitespace()
}

/**
 * Render a fenced-code body as a `<pre>` block. Newlines are converted to
 * `<br/>` so the whole block stays one HTMLEditorKit paragraph — otherwise
 * the `border` is drawn only around the first line.
 */
private fun renderCodeBlock(body: String): String {
    val escaped = htmlEscape(body).replace("\n", "<br/>")
    return "<pre style=\"" +
        "background-color: #111827; color: #E5E7EB; " +
        "padding: 8px 10px; margin: 4px 0; " +
        "font-family: monospace; font-size: 10px; " +
        "border: 1px solid #374151; " +
        "white-space: pre-wrap;" +
        "\">" + escaped + "</pre>"
}

/**
 * Render inline formatting within a single line. Splits into code-span vs
 * non-code segments: the latter get HTML-escaped and then bold / italic
 * replacement. Inline code beats emphasis (a `*` inside a backtick pair is
 * treated as literal).
 */
private fun renderInline(line: String): String {
    if (line.isEmpty()) return ""
    val sb = StringBuilder()
    var i = 0
    while (i < line.length) {
        if (line[i] == '`') {
            val end = line.indexOf('`', i + 1)
            if (end > i) {
                sb.append("<code style=\"")
                    .append("background-color: #2D3748; color: #E5E7EB; ")
                    .append("padding: 1px 4px; font-family: monospace;")
                    .append("\">")
                    .append(htmlEscape(line.substring(i + 1, end)))
                    .append("</code>")
                i = end + 1
            } else {
                sb.append("&#96;")  // literal backtick
                i++
            }
        } else {
            var j = i
            while (j < line.length && line[j] != '`') j++
            sb.append(applyEmphasis(htmlEscape(line.substring(i, j))))
            i = j
        }
    }
    return sb.toString()
}

/**
 * Apply bold / italic replacements to an already-HTML-escaped fragment.
 * Order matters: `**…**` and `__…__` are handled first so the single-char
 * italic regex doesn't steal their markers. Italic uses word-boundary
 * lookbehind/ahead to avoid eating markers that abut letters (e.g.
 * `snake_case_name` should NOT become `snake<i>case</i>name`).
 */
private fun applyEmphasis(escaped: String): String {
    return escaped
        .replace(Regex("\\*\\*([^*\\n]+?)\\*\\*"), "<b>$1</b>")
        .replace(Regex("__([^_\\n]+?)__"), "<b>$1</b>")
        .replace(Regex("(?<![*\\w])\\*([^*\\n]+?)\\*(?![*\\w])"), "<i>$1</i>")
        .replace(Regex("(?<![_\\w])_([^_\\n]+?)_(?![_\\w])"), "<i>$1</i>")
}

/**
 * Build a tiny one-cell HTML pane rendering [html] with the factory's bubble
 * style (coloured left border, dark background). Used by both user and
 * assistant turns so the two look visually cohesive with the existing
 * transcript.
 */
private fun bubblePane(html: String): JTextPane {
    val pane = JTextPane()
    pane.isEditable = false
    pane.contentType = "text/html"
    pane.editorKit = HTMLEditorKit()
    pane.text = html
    pane.border = BorderFactory.createEmptyBorder()
    return pane
}

/** One bubble rendering the user's text. */
class UserMessageComponent(text: String) : JBPanel<UserMessageComponent>(BorderLayout()) {
    init {
        val icon = "&#128100;"   // 👤
        val html = buildString {
            append("<html><body style='font-family: sans-serif; font-size: 11px;'>")
            append("<div style='margin: 4px 0; padding: 6px 8px; ")
            append("background: ").append(USER_BG).append("; ")
            append("border-left: 3px solid ").append(USER_ACCENT).append(";'>")
            append(icon).append(' ').append(renderMarkdown(text))
            append("</div></body></html>")
        }
        val pane = bubblePane(html)
        add(pane, BorderLayout.CENTER)

        alignmentX = LEFT_ALIGNMENT
        border = BorderFactory.createEmptyBorder(2, 0, 2, 0)
        isOpaque = false
    }

    /**
     * Stretch horizontally to the transcript's viewport width, but never
     * absorb extra vertical space. Without this cap, the last bubble in a
     * short conversation would balloon to fill the remaining scroll-pane
     * height because `BoxLayout.Y_AXIS` hands leftover space to whichever
     * child has `maximumSize.height = Int.MAX_VALUE`.
     */
    override fun getMaximumSize(): Dimension =
        Dimension(Int.MAX_VALUE, preferredSize.height)
}

/**
 * The assistant's reply, optionally with one or more [CodeEditCardComponent]s
 * stacked underneath.
 *
 * The parent is expected to wrap this in a scroll pane (Task 4b). No internal
 * scrolling here.
 *
 * [interventionIds] must be the same length as [edits]: each id is the stable
 * correlator that threads the per-edit `INTERVENTION_SHOWN`,
 * `INTERVENTION_ACCEPTED`, and `INTERVENTION_DISMISSED` events together. The
 * parent (`RubberDuckToolWindowFactory`) emits `SHOWN` using these ids before
 * constructing this component. When the list is shorter than [edits] (or
 * empty), missing ids are filled with fresh UUIDs so the component still
 * compiles and runs — but then `SHOWN`/`ACCEPTED`/`DISMISSED` won't correlate.
 */
class AssistantMessageComponent(
    text: String,
    edits: List<CodeEdit>,
    private val project: Project,
    private val resolver: SessionFileResolver,
    interventionIds: List<String> = emptyList(),
) : JBPanel<AssistantMessageComponent>() {

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        alignmentX = LEFT_ALIGNMENT
        isOpaque = false
        border = BorderFactory.createEmptyBorder(2, 0, 2, 0)

        val icon = "&#129428;"   // 🦆
        val html = buildString {
            append("<html><body style='font-family: sans-serif; font-size: 11px;'>")
            append("<div style='margin: 4px 0; padding: 6px 8px; ")
            append("background: ").append(ASSIST_BG).append("; ")
            append("border-left: 3px solid ").append(ASSIST_ACCENT).append(";'>")
            append(icon).append(' ').append(renderMarkdown(text))
            append("</div></body></html>")
        }
        val pane = bubblePane(html).apply {
            alignmentX = LEFT_ALIGNMENT
        }
        add(pane)

        for ((i, edit) in edits.withIndex()) {
            val iid = interventionIds.getOrNull(i) ?: UUID.randomUUID().toString()
            val card = CodeEditCardComponent(project, edit, resolver, iid).apply {
                alignmentX = LEFT_ALIGNMENT
                border = BorderFactory.createCompoundBorder(
                    BorderFactory.createEmptyBorder(4, 0, 0, 0),
                    border,
                )
                maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height.coerceAtLeast(CARD_HEIGHT_HINT))
            }
            add(card)
        }
    }

    /** See [UserMessageComponent.getMaximumSize] — same rationale. */
    override fun getMaximumSize(): Dimension =
        Dimension(Int.MAX_VALUE, preferredSize.height)

    @Suppress("unused")
    private val accent: Color = Color.decode(ASSIST_ACCENT)  // retained for future theming hooks
}
