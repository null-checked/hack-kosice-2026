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
 */
internal fun htmlEscape(s: String): String = s
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\n", "<br/>")

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
            append(icon).append(' ').append(htmlEscape(text))
            append("</div></body></html>")
        }
        val pane = bubblePane(html)
        add(pane, BorderLayout.CENTER)

        alignmentX = LEFT_ALIGNMENT
        border = BorderFactory.createEmptyBorder(2, 0, 2, 0)
        isOpaque = false
        // Let the bubble grow vertically but not gobble the chat pane.
        maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
    }
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
            append(icon).append(' ').append(htmlEscape(text))
            append("</div></body></html>")
        }
        val pane = bubblePane(html).apply {
            alignmentX = LEFT_ALIGNMENT
            // Preferred size hint — HTMLEditorKit measures its own text but we
            // cap width so long replies wrap instead of running off screen.
            maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
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

        maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
    }

    @Suppress("unused")
    private val accent: Color = Color.decode(ASSIST_ACCENT)  // retained for future theming hooks
}
