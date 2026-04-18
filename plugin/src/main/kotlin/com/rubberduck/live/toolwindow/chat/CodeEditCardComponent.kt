package com.rubberduck.live.toolwindow.chat

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBTextArea
import com.rubberduck.live.detector.EventEmitter
import com.rubberduck.live.model.CodeEdit
import com.rubberduck.live.model.EventType
import com.rubberduck.live.settings.RubberDuckSettings
import com.rubberduck.live.util.DiffApplier
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * Single Apply/Reject card for one [CodeEdit] proposed by the assistant.
 *
 * Layout (top to bottom inside a vertical [BoxLayout]):
 *   • File chip — monospaced label with the redacted path.
 *   • Snippet preview — first three non-empty lines of [edit]'s proposed code,
 *     truncated with `…` when longer.
 *   • Button row — "Preview & Apply" / "Reject".
 *
 * On Apply:
 *   1. Refuse if `privacyMode` is on (no code leaves the IDE in privacy mode,
 *      and applying an edit that was already produced by the backend is a no-op
 *      when the card is shown — but the cheap local check keeps the invariant
 *      uniform across the UI surface).
 *   2. Resolve the redacted path back to a [com.intellij.openapi.vfs.VirtualFile]
 *      via [SessionFileResolver]. If that fails, show an inline notice and
 *      disable Apply.
 *   3. Delegate the modal diff + write-command dance to
 *      [com.rubberduck.live.util.DiffApplier.previewAndApply].
 *   4. On success, mark the card as applied and emit `INTERVENTION_ACCEPTED`.
 *
 * On Reject:
 *   • Mark the card rejected and emit `INTERVENTION_DISMISSED`.
 *
 * The [INTERVENTION_SHOWN] event is NOT emitted here — the parent chat turn
 * emits one `SHOWN` per edit before constructing the card, using the same
 * [interventionId] that the card threads through `ACCEPTED`/`DISMISSED`. That
 * way every SHOWN has a matching terminal event and counts stay balanced.
 */
class CodeEditCardComponent(
    private val project: Project,
    private val edit: CodeEdit,
    private val resolver: SessionFileResolver,
    private val interventionId: String,
) : JBPanel<CodeEditCardComponent>(BorderLayout(6, 4)) {

    private val filePathLabel = JBLabel(edit.file_path).apply {
        font = Font(Font.MONOSPACED, Font.PLAIN, 11)
        border = BorderFactory.createEmptyBorder(0, 2, 2, 2)
    }

    private val snippetArea = JBTextArea().apply {
        isEditable = false
        font = Font(Font.MONOSPACED, Font.PLAIN, 11)
        text = snippetPreview(edit.proposed_code)
        lineWrap = false
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(Color(0x4B5563), 1),
            BorderFactory.createEmptyBorder(4, 6, 4, 6),
        )
        background = Color(0x111827)
        foreground = Color(0xE5E7EB)
    }

    private val statusLabel = JBLabel(" ").apply {
        border = BorderFactory.createEmptyBorder(2, 2, 0, 2)
    }

    private val applyButton = JButton("Preview & Apply")
    private val rejectButton = JButton("Reject")

    init {
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(Color(0x374151), 1),
            BorderFactory.createEmptyBorder(6, 8, 6, 8),
        )

        val top = JPanel(BorderLayout())
        top.isOpaque = false
        top.add(filePathLabel, BorderLayout.NORTH)

        val center = JPanel()
        center.isOpaque = false
        center.layout = BoxLayout(center, BoxLayout.Y_AXIS)
        snippetArea.alignmentX = LEFT_ALIGNMENT
        statusLabel.alignmentX = LEFT_ALIGNMENT
        center.add(snippetArea)
        center.add(statusLabel)

        val buttons = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0))
        buttons.isOpaque = false
        buttons.add(applyButton)
        buttons.add(rejectButton)

        add(top, BorderLayout.NORTH)
        add(center, BorderLayout.CENTER)
        add(buttons, BorderLayout.SOUTH)

        applyButton.addActionListener { onApplyClicked() }
        rejectButton.addActionListener { onRejectClicked() }

        // Flow vertically in a BoxLayout parent — don't let the card expand
        // to gobble the whole chat panel height.
        alignmentX = LEFT_ALIGNMENT
        maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height.coerceAtLeast(120))
    }

    // ---- button handlers ---------------------------------------------------

    private fun onApplyClicked() {
        if (RubberDuckSettings.getInstance().state.privacyMode) {
            showInlineNotice("Privacy mode: cannot apply edits", Color(0xF59E0B))
            return
        }

        val vf = resolver.resolve(edit.file_path)
        if (vf == null) {
            showInlineNotice("Could not locate file", Color(0xEF4444))
            applyButton.isEnabled = false
            return
        }

        val document = FileDocumentManager.getInstance().getDocument(vf)
        if (document == null) {
            showInlineNotice("Could not open document", Color(0xEF4444))
            applyButton.isEnabled = false
            return
        }

        DiffApplier.previewAndApply(
            project = project,
            document = document,
            title = "RubberDuck: Chat edit — ${edit.file_path}",
            original = edit.original_code,
            proposed = edit.proposed_code,
            onApplied = {
                SwingUtilities.invokeLater { markApplied() }
            },
            onDismissed = {
                // Leave buttons enabled so the user can still explicitly Reject;
                // closing the diff dialog without Apply is not a rejection by itself.
            },
            windowTitle = "Apply RubberDuck chat edit?",
            commandName = "RubberDuck: Apply chat edit",
        )
    }

    private fun onRejectClicked() {
        markRejected()
    }

    // ---- state transitions -------------------------------------------------

    private fun markApplied() {
        applyButton.isEnabled = false
        rejectButton.isEnabled = false
        snippetArea.border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(Color(0x22C55E), 1),
            BorderFactory.createEmptyBorder(4, 6, 4, 6),
        )
        statusLabel.text = "<html><font color='#22C55E'>Applied</font></html>"
        revalidate()
        repaint()

        ApplicationManager.getApplication().executeOnPooledThread {
            EventEmitter.emit(
                EventType.INTERVENTION_ACCEPTED,
                file = edit.file_path,
                payload = EventEmitter.json(
                    "intervention_id" to interventionId,
                    "kind" to "chat_edit",
                    "source" to "chat_edit",
                ),
            )
        }
    }

    private fun markRejected() {
        applyButton.isEnabled = false
        rejectButton.isEnabled = false
        snippetArea.border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(Color(0x6B7280), 1),
            BorderFactory.createEmptyBorder(4, 6, 4, 6),
        )
        statusLabel.text = "<html><font color='#9CA3AF'>Rejected</font></html>"
        revalidate()
        repaint()

        ApplicationManager.getApplication().executeOnPooledThread {
            EventEmitter.emit(
                EventType.INTERVENTION_DISMISSED,
                file = edit.file_path,
                payload = EventEmitter.json(
                    "intervention_id" to interventionId,
                    "kind" to "chat_edit",
                    "source" to "chat_edit",
                ),
            )
        }
    }

    private fun showInlineNotice(text: String, color: Color) {
        val hex = String.format("#%06X", color.rgb and 0xFFFFFF)
        statusLabel.text = "<html><font color='$hex'>$text</font></html>"
        revalidate()
        repaint()
    }

    // ---- helpers -----------------------------------------------------------

    /**
     * First 3 non-empty lines of [proposed]. Appends `…` if there were more
     * non-empty lines after the truncation point.
     */
    private fun snippetPreview(proposed: String): String {
        val allLines = proposed.split('\n')
        val nonEmpty = allLines.filter { it.isNotBlank() }
        if (nonEmpty.isEmpty()) return "(empty)"
        val head = nonEmpty.take(3)
        val truncated = nonEmpty.size > 3
        return buildString {
            head.forEachIndexed { i, line ->
                if (i > 0) append('\n')
                append(line.trimEnd())
            }
            if (truncated) {
                append('\n')
                append('\u2026')
            }
        }
    }
}
