package com.rubberduck.live.util

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import java.awt.Dimension
import javax.swing.JComponent

/**
 * Reusable helper that shows IntelliJ's native diff viewer with **Apply** /
 * **Cancel** buttons and writes [proposed] to [document] on Apply.
 *
 * This is the generic UI/write side of the flow that
 * [com.rubberduck.live.actions.SuggestRefactorAction] used to own inline; it
 * was pulled out so a future chat-edit feature can share the exact same Apply
 * contract without duplicating the dialog plumbing.
 *
 * Domain concerns — emitting `INTERVENTION_ACCEPTED` / `INTERVENTION_DISMISSED`
 * events, privacy checks, user-visible notifications — stay with the caller.
 * `DiffApplier` only builds the diff request, shows the modal, and runs the
 * write command on Apply.
 */
object DiffApplier {

    /** Outcome of [previewAndApply]. `applied == true` iff the user clicked Apply. */
    data class Result(val applied: Boolean)

    /**
     * Show the diff between [original] and [proposed] in a modal dialog.
     * On Apply, replace [document]'s contents with [proposed] inside a write
     * command and invoke [onApplied]. On Cancel (or window close), invoke
     * [onDismissed]. Either callback is optional — callers may rely on the
     * returned [Result] instead.
     *
     * Must be called on the EDT (same contract as the original inline flow in
     * [com.rubberduck.live.actions.SuggestRefactorAction]).
     *
     * @param project       IntelliJ project used for diff content creation and
     *                      write-command scoping.
     * @param document      The document whose contents will be overwritten with
     *                      [proposed] if the user applies.
     * @param title         Header shown above the diff viewer (e.g. file name +
     *                      smell summary).
     * @param original      Current text — left side of the diff.
     * @param proposed      Suggested replacement — right side of the diff.
     *                      Becomes the new [document] text on Apply.
     * @param windowTitle   Dialog window caption. Defaults to the refactor
     *                      flow's wording; chat edits pass their own label.
     * @param commandName   Name of the [WriteCommandAction] — surfaces in the
     *                      IDE's Undo menu.
     */
    fun previewAndApply(
        project: Project,
        document: Document,
        title: String,
        original: String,
        proposed: String,
        onApplied: () -> Unit = {},
        onDismissed: () -> Unit = {},
        windowTitle: String = "Apply RubberDuck refactor?",
        commandName: String = "RubberDuck: Apply refactor",
    ): Result {
        val factory = DiffContentFactory.getInstance()
        val before = factory.create(project, original)
        val after = factory.create(project, proposed)
        val diffRequest = SimpleDiffRequest(title, before, after, "Original", "Suggested")

        val dialog = object : DialogWrapper(project, true) {
            private val diffPanel = DiffManager.getInstance()
                .createRequestPanel(project, disposable, null)
                .also { it.setRequest(diffRequest) }

            init {
                this.title = windowTitle
                setOKButtonText("Apply")
                setCancelButtonText("Cancel")
                init()
            }

            override fun createCenterPanel(): JComponent =
                diffPanel.component.apply { preferredSize = Dimension(960, 560) }
        }

        val applied = dialog.showAndGet()

        if (applied) {
            WriteCommandAction.runWriteCommandAction(
                project,
                commandName,
                null,
                { document.setText(proposed) },
            )
            onApplied()
        } else {
            onDismissed()
        }

        return Result(applied)
    }
}
