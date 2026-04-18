package com.rubberduck.live.detector

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.rubberduck.live.intervention.StuckInterventionCoordinator
import com.rubberduck.live.model.DeveloperEvent
import com.rubberduck.live.model.EventType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

/**
 * Heuristic "the developer is stuck" engine — the 5 rules from
 * [ARCHITECTURE.md §5](../../../../../../docs/ARCHITECTURE.md).
 *
 * Each rule owns a small in-memory rolling window and is rate-limited to fire
 * at most once per 60 s. On fire, the detector emits a `STUCK_DETECTED` event
 * **and** notifies [StuckInterventionCoordinator].
 *
 * Thresholds (deliberately repeated here as constants so tweaks don't have to
 * chase multiple files):
 * - `undo_burst`: ≥ 4 UNDO in 30 s, same file.
 * - `repeated_failing_test`: same test name fails ≥ 2 in a row within 5 min.
 * - `long_idle_on_error`: CARET_DWELL ≥ 90 s with `has_error_marker=true`.
 * - `rapid_file_switching`: ≥ 6 FILE_FOCUS across ≥ 4 files in 60 s, avg dwell < 8 s.
 * - `edit_thrash`: ≥ 10 EDIT events in 20 s on the same line.
 */
@Service(Service.Level.APP)
class StuckDetector {

    private val log = Logger.getInstance(StuckDetector::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lastFired = ConcurrentHashMap<String, Long>()

    private val undoWindows = ConcurrentHashMap<String, ArrayDeque<Long>>() // per file
    private val testFailures = ArrayDeque<TestFail>()
    private val focusWindow = ArrayDeque<Focus>()
    private val editWindows = ConcurrentHashMap<String, ArrayDeque<EditHit>>() // per "file:line"

    private data class TestFail(val ts: Long, val testName: String, val file: String?)
    private data class Focus(val ts: Long, val file: String?)
    private data class EditHit(val ts: Long)

    /** Must be called once during startup. */
    fun start() {
        scope.launch {
            EventBus.getInstance().events.collect { ev -> process(ev) }
        }
    }

    @org.jetbrains.annotations.VisibleForTesting
    internal fun process(ev: DeveloperEvent) {
        when (ev.typeEnum) {
            EventType.UNDO -> onUndo(ev)
            EventType.TEST_RUN -> onTestRun(ev)
            EventType.CARET_DWELL -> onCaretDwell(ev)
            EventType.FILE_FOCUS -> onFileFocus(ev)
            EventType.EDIT -> onEdit(ev)
            else -> { /* ignore */ }
        }
    }

    @org.jetbrains.annotations.VisibleForTesting
    internal fun firedRules(): Set<String> = lastFired.keys.toSet()

    // --- undo_burst ----------------------------------------------------------
    private fun onUndo(ev: DeveloperEvent) {
        val file = ev.file ?: return
        val dq = undoWindows.computeIfAbsent(file) { ArrayDeque() }
        val now = ev.ts
        synchronized(dq) {
            dq.addLast(now)
            while (dq.isNotEmpty() && now - dq.peekFirst() > 30_000) dq.pollFirst()
            if (dq.size >= 4) fire("undo_burst", file, dq.size, confidence = 0.9)
        }
    }

    // --- repeated_failing_test -----------------------------------------------
    private fun onTestRun(ev: DeveloperEvent) {
        val outcome = ev.payload["outcome"]?.jsonPrimitive?.contentOrNullSafe() ?: return
        if (outcome != "fail" && outcome != "error") return
        val name = ev.payload["test_name"]?.jsonPrimitive?.contentOrNullSafe() ?: return
        synchronized(testFailures) {
            val now = ev.ts
            testFailures.addLast(TestFail(now, name, ev.file))
            while (testFailures.isNotEmpty() && now - testFailures.peekFirst().ts > 5 * 60_000) {
                testFailures.pollFirst()
            }
            val sameInARow = testFailures.toList().takeLast(10).count { it.testName == name }
            if (sameInARow >= 2) fire("repeated_failing_test", ev.file, sameInARow, confidence = 0.95)
        }
    }

    // --- long_idle_on_error --------------------------------------------------
    private fun onCaretDwell(ev: DeveloperEvent) {
        val dur = (ev.payload["duration_ms"] as? JsonPrimitive)?.longOrNullSafe() ?: return
        val hasErr = (ev.payload["has_error_marker"] as? JsonPrimitive)?.booleanOrNullSafe() ?: false
        if (dur >= 90_000 && hasErr) fire("long_idle_on_error", ev.file, 1, confidence = 0.8)
    }

    // --- rapid_file_switching ------------------------------------------------
    private fun onFileFocus(ev: DeveloperEvent) {
        synchronized(focusWindow) {
            val now = ev.ts
            focusWindow.addLast(Focus(now, ev.file))
            while (focusWindow.isNotEmpty() && now - focusWindow.peekFirst().ts > 60_000) {
                focusWindow.pollFirst()
            }
            if (focusWindow.size >= 6) {
                val distinct = focusWindow.mapNotNull { it.file }.toSet().size
                val avgDwell = if (focusWindow.size >= 2) {
                    val list = focusWindow.toList()
                    val gaps = list.zipWithNext { a, b -> b.ts - a.ts }
                    gaps.average().toLong()
                } else 0L
                if (distinct >= 4 && avgDwell < 8000) {
                    fire("rapid_file_switching", ev.file, focusWindow.size, confidence = 0.7)
                }
            }
        }
    }

    // --- edit_thrash ---------------------------------------------------------
    private fun onEdit(ev: DeveloperEvent) {
        val line = (ev.payload["line"] as? JsonPrimitive)?.longOrNullSafe() ?: return
        val key = "${ev.file ?: "?"}:$line"
        val dq = editWindows.computeIfAbsent(key) { ArrayDeque() }
        synchronized(dq) {
            val now = ev.ts
            dq.addLast(EditHit(now))
            while (dq.isNotEmpty() && now - dq.peekFirst().ts > 20_000) dq.pollFirst()
            if (dq.size >= 10) fire("edit_thrash", ev.file, dq.size, confidence = 0.75)
        }
    }

    private fun fire(rule: String, file: String?, windowEvents: Int, confidence: Double) {
        val now = System.currentTimeMillis()
        val last = lastFired[rule] ?: 0L
        if (now - last < 60_000) return
        lastFired[rule] = now
        log.info("StuckDetector fire: $rule (file=$file, n=$windowEvents)")
        val payload = EventEmitter.json(
            "rule" to rule,
            "confidence" to confidence,
            "window_events" to windowEvents,
        )
        val ev = EventEmitter.emit(EventType.STUCK_DETECTED, file = file, payload = payload)
        ApplicationManager.getApplication().invokeLater {
            StuckInterventionCoordinator.getInstance().onStuckDetected(ev, rule)
        }
    }

    // Helpers that avoid NPEs on primitive conversions ------------------------
    private fun JsonPrimitive.contentOrNullSafe(): String? = runCatching { content }.getOrNull()
    private fun JsonPrimitive.longOrNullSafe(): Long? =
        runCatching { content.toLong() }.getOrNull()
    private fun JsonPrimitive.booleanOrNullSafe(): Boolean? =
        runCatching { content.toBooleanStrict() }.getOrNull()

    companion object {
        fun getInstance(): StuckDetector =
            ApplicationManager.getApplication().getService(StuckDetector::class.java)
    }
}
