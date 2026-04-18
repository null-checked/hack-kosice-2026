package com.rubberduck.live.toolwindow.chat

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.codeStyle.NameUtil
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.rubberduck.live.util.PathRedactor
import java.awt.Point
import javax.swing.JTextField
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * Attaches `@`-mention autocomplete behaviour to a [JTextField].
 *
 * Typing `@` (and continuing afterwards) pops up a chooser of project files,
 * fuzzy-matched against whatever the user typed after the `@`. Picking an item
 * replaces the `@prefix` fragment with `@<redacted-path>` and records the
 * mapping with [SessionFileResolver] so the backend round-trip can later
 * resolve the same redacted string back to the original [VirtualFile].
 *
 * Lifecycle:
 *   • [install] — wires up a single [DocumentListener]. Idempotent: repeat
 *     calls are no-ops.
 *   • [dispose] — removes the listener so the controller doesn't keep the
 *     text field alive once the tool window is gone.
 *
 * Design notes:
 *   • Candidate list is rebuilt on every prefix change. For typical projects
 *     (a few thousand files) this is fast; we can add caching later if it ever
 *     shows up on a flame graph.
 *   • Binary and very large files (>1 MB) are filtered out — mentioning them
 *     would either break the LLM context window or waste tokens on irrelevant
 *     bytes.
 *   • We derive candidates on the EDT wrapped in [ReadAction] because the
 *     index APIs need read access; the popup itself opens on the EDT as usual.
 */
class AtMentionController(
    private val project: Project,
    private val inputField: JTextField,
    private val resolver: SessionFileResolver,
) {

    private var listener: DocumentListener? = null
    private var currentPopup: JBPopup? = null

    /** Hook up the document listener. No-op if already installed. */
    fun install() {
        if (listener != null) return
        val l = object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = onTextChanged()
            override fun removeUpdate(e: DocumentEvent) = onTextChanged()
            override fun changedUpdate(e: DocumentEvent) { /* attributes only */ }
        }
        inputField.document.addDocumentListener(l)
        listener = l
    }

    /** Remove the listener and cancel any open popup. */
    fun dispose() {
        listener?.let { inputField.document.removeDocumentListener(it) }
        listener = null
        currentPopup?.cancel()
        currentPopup = null
    }

    // -----------------------------------------------------------------------

    private fun onTextChanged() {
        // Ask Swing to reconsider after the document event settles — touching
        // the caret mid-DocumentEvent is legal but reading it afterward is
        // cheaper and avoids re-entrancy surprises.
        SwingUtilities.invokeLater {
            val text = inputField.text.orEmpty()
            val caret = inputField.caretPosition.coerceIn(0, text.length)
            val prefix = currentAtPrefix(text, caret)
            if (prefix == null) {
                currentPopup?.cancel()
                currentPopup = null
                return@invokeLater
            }
            showCandidates(prefix, caret)
        }
    }

    private fun showCandidates(prefix: AtPrefix, caret: Int) {
        // Build the candidate list off-EDT (needs a ReadAction), then pop up
        // on the EDT.
        ApplicationManager.getApplication().executeOnPooledThread {
            val candidates = ReadAction.compute<List<Candidate>, RuntimeException> {
                collectCandidates(prefix.query)
            }
            ApplicationManager.getApplication().invokeLater {
                if (candidates.isEmpty()) {
                    currentPopup?.cancel()
                    currentPopup = null
                    return@invokeLater
                }
                openPopup(candidates, prefix, caret)
            }
        }
    }

    private fun openPopup(candidates: List<Candidate>, prefix: AtPrefix, caret: Int) {
        currentPopup?.cancel()
        val display = candidates.map { it.redactedPath }
        val pathToCandidate = candidates.associateBy { it.redactedPath }

        val popup = JBPopupFactory.getInstance()
            .createPopupChooserBuilder(display)
            .setTitle("Mention a file")
            .setResizable(true)
            .setMovable(false)
            .setRequestFocus(false)
            .setItemChosenCallback { chosen ->
                val cand = pathToCandidate[chosen] ?: return@setItemChosenCallback
                applySelection(prefix, cand)
            }
            .createPopup()

        // Anchor below the caret within the input field. Fall back to below
        // the input's origin if geometry lookup fails.
        val anchor = runCatching {
            val rect = inputField.modelToView2D(caret.coerceAtMost(inputField.text.length))
            if (rect != null) {
                Point(rect.x.toInt(), (rect.y + rect.height).toInt())
            } else {
                Point(0, inputField.height)
            }
        }.getOrDefault(Point(0, inputField.height))

        popup.show(com.intellij.ui.awt.RelativePoint(inputField, anchor))
        currentPopup = popup
    }

    private fun applySelection(prefix: AtPrefix, candidate: Candidate) {
        resolver.rememberMention(candidate.redactedPath, candidate.vf)
        val text = inputField.text.orEmpty()
        val replacement = "@${candidate.redactedPath}"
        val before = text.substring(0, prefix.atPos)
        val after = text.substring(prefix.endPos.coerceAtMost(text.length))
        val newText = before + replacement + after
        inputField.text = newText
        val newCaret = (before.length + replacement.length).coerceAtMost(newText.length)
        inputField.caretPosition = newCaret
    }

    // -----------------------------------------------------------------------
    // Candidate collection

    private data class Candidate(val redactedPath: String, val vf: VirtualFile, val score: Int)

    private fun collectCandidates(query: String): List<Candidate> {
        val scope = GlobalSearchScope.projectScope(project)
        val fileIndex = ProjectFileIndex.getInstance(project)
        val matcher = NameUtil.buildMatcher("*$query").build()

        val results = ArrayList<Candidate>()
        val names = FilenameIndex.getAllFilenames(project)
        for (name in names) {
            val files = FilenameIndex.getVirtualFilesByName(name, scope)
            for (vf in files) {
                if (!vf.isValid || vf.isDirectory) continue
                if (!fileIndex.isInContent(vf)) continue
                if (vf.fileType.isBinary) continue
                if (vf.length > 1_000_000) continue
                val redacted = PathRedactor.redact(project, vf.path) ?: continue
                val score = if (query.isBlank()) {
                    0
                } else {
                    val pathScore = matcher.matchingDegree(redacted)
                    val nameScore = matcher.matchingDegree(name)
                    val best = maxOf(pathScore, nameScore)
                    if (best <= 0 && !redacted.contains(query, ignoreCase = true)) continue
                    best
                }
                results.add(Candidate(redacted, vf, score))
            }
        }
        // Higher score first, then shorter paths (more specific matches), then alphabetical.
        results.sortWith(
            compareByDescending<Candidate> { it.score }
                .thenBy { it.redactedPath.length }
                .thenBy { it.redactedPath },
        )
        return results.take(MAX_CANDIDATES)
    }

    companion object {
        private const val MAX_CANDIDATES = 30

        /** Regex used by [parseMentions]. Captures `@path/to/file.ext` tokens. */
        private val MENTION_REGEX = Regex("@([A-Za-z0-9_./\\\\-]+\\.[A-Za-z0-9]+)")

        /**
         * Parse `@path/to/file.ext` tokens out of [text]. Returns the captured
         * redacted paths in order of appearance, deduplicated.
         */
        fun parseMentions(text: String): List<String> {
            val seen = LinkedHashSet<String>()
            for (m in MENTION_REGEX.findAll(text)) {
                seen.add(m.groupValues[1])
            }
            return seen.toList()
        }
    }

    /** Position of the active `@` token in the input — `@` position and end. */
    private data class AtPrefix(val atPos: Int, val endPos: Int, val query: String)

    /**
     * Find the most recent `@` before [caret] that could still be acting as a
     * mention trigger. Returns `null` if the `@` has been closed by a newline
     * or is not present.
     */
    private fun currentAtPrefix(text: String, caret: Int): AtPrefix? {
        var i = caret - 1
        while (i >= 0) {
            val c = text[i]
            if (c == '@') {
                // The char immediately before `@` should be a word boundary
                // so we don't fire on e.g. `foo@bar`.
                if (i > 0) {
                    val prev = text[i - 1]
                    if (prev.isLetterOrDigit() || prev == '_') return null
                }
                val query = text.substring(i + 1, caret)
                // Disqualify if the query already contains whitespace — the
                // mention is considered closed.
                if (query.any { it.isWhitespace() }) return null
                return AtPrefix(i, caret, query)
            }
            if (c.isWhitespace()) return null
            i--
        }
        return null
    }
}
