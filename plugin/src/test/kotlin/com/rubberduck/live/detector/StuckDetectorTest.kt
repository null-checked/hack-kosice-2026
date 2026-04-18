package com.rubberduck.live.detector

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.rubberduck.live.model.DeveloperEvent
import com.rubberduck.live.model.EventType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.util.UUID

/**
 * Smoke tests for the 5 heuristic rules in [StuckDetector].
 *
 * Uses a **fresh** [StuckDetector] instance per test (via the constructor,
 * bypassing the application service singleton) so that each test has a pristine
 * `lastFired` map — the real 60 s global rate-limit would otherwise prevent a
 * rule from firing twice across tests.
 *
 * Assertions observe the detector's internal `firedRules()` accessor
 * ([StuckDetector.firedRules]) rather than subscribing to the shared
 * [EventBus] flow. That keeps the tests synchronous and robust against
 * coroutine-timing flakes.
 */
class StuckDetectorTest : BasePlatformTestCase() {

    private lateinit var detector: StuckDetector

    override fun setUp() {
        super.setUp()
        detector = StuckDetector()
    }

    // -- undo_burst -----------------------------------------------------------

    fun testUndoBurstFiresAfterFourUndosWithin30Seconds() {
        val base = 1_000_000_000_000L
        repeat(4) { i ->
            detector.process(undoAt(base + i * 1_000, file = "Foo.kt"))
        }
        assertContainsRule("undo_burst")
    }

    fun testUndoBurstDoesNotFireWhenSpacedBeyondWindow() {
        val base = 1_000_000_000_000L
        // Four undoes, but the first three fall off a 30 s window before #4.
        detector.process(undoAt(base, file = "Foo.kt"))
        detector.process(undoAt(base + 10_000, file = "Foo.kt"))
        detector.process(undoAt(base + 25_000, file = "Foo.kt"))
        detector.process(undoAt(base + 31_000, file = "Foo.kt"))
        assertDoesNotContainRule("undo_burst")
    }

    fun testUndoBurstScopedPerFile() {
        val base = 1_000_000_000_000L
        // Alternating files — neither file accumulates 4 within the window.
        detector.process(undoAt(base, file = "Foo.kt"))
        detector.process(undoAt(base + 1_000, file = "Bar.kt"))
        detector.process(undoAt(base + 2_000, file = "Foo.kt"))
        detector.process(undoAt(base + 3_000, file = "Bar.kt"))
        assertDoesNotContainRule("undo_burst")
    }

    // -- repeated_failing_test ------------------------------------------------

    fun testRepeatedFailingTestFiresOnSecondFailure() {
        val base = 1_000_000_000_000L
        detector.process(testRunAt(base, testName = "test_fizz", outcome = "fail"))
        detector.process(testRunAt(base + 5_000, testName = "test_fizz", outcome = "fail"))
        assertContainsRule("repeated_failing_test")
    }

    fun testRepeatedFailingTestIgnoresPassingRuns() {
        val base = 1_000_000_000_000L
        detector.process(testRunAt(base, testName = "test_fizz", outcome = "fail"))
        detector.process(testRunAt(base + 1_000, testName = "test_fizz", outcome = "pass"))
        assertDoesNotContainRule("repeated_failing_test")
    }

    // -- long_idle_on_error ---------------------------------------------------

    fun testLongIdleOnErrorFiresAt90sOnMarkedLine() {
        detector.process(
            caretDwellAt(1_000_000_000_000L, durationMs = 90_001, hasErrorMarker = true),
        )
        assertContainsRule("long_idle_on_error")
    }

    fun testLongIdleOnErrorDoesNotFireWithoutErrorMarker() {
        detector.process(
            caretDwellAt(1_000_000_000_000L, durationMs = 120_000, hasErrorMarker = false),
        )
        assertDoesNotContainRule("long_idle_on_error")
    }

    // -- rapid_file_switching -------------------------------------------------

    fun testRapidFileSwitchingFiresOn6FilesIn60Seconds() {
        val base = 1_000_000_000_000L
        listOf("A.kt", "B.kt", "C.kt", "D.kt", "E.kt", "F.kt").forEachIndexed { i, f ->
            detector.process(fileFocusAt(base + i * 3_000, file = f))
        }
        assertContainsRule("rapid_file_switching")
    }

    // -- edit_thrash ----------------------------------------------------------

    fun testEditThrashFiresAfterTenEditsOnSameLine() {
        val base = 1_000_000_000_000L
        repeat(10) { i ->
            detector.process(editAt(base + i * 500, file = "Foo.kt", line = 42))
        }
        assertContainsRule("edit_thrash")
    }

    fun testEditThrashScopedPerLine() {
        val base = 1_000_000_000_000L
        // Alternating lines — neither accumulates 10 within the window.
        repeat(12) { i ->
            val line = if (i % 2 == 0) 10L else 20L
            detector.process(editAt(base + i * 500, file = "Foo.kt", line = line))
        }
        assertDoesNotContainRule("edit_thrash")
    }

    // -- helpers --------------------------------------------------------------

    private fun assertContainsRule(rule: String) {
        val fired = detector.firedRules()
        assertTrue(
            "Expected $rule to fire, but only ${fired.ifEmpty { setOf("<none>") }} fired",
            rule in fired,
        )
    }

    private fun assertDoesNotContainRule(rule: String) {
        val fired = detector.firedRules()
        assertFalse("Expected $rule not to fire, but got $fired", rule in fired)
    }

    private fun undoAt(ts: Long, file: String): DeveloperEvent =
        DeveloperEvent(
            event_id = UUID.randomUUID().toString(),
            session_id = "test",
            ts = ts,
            type = EventType.UNDO.name,
            file = file,
            payload = JsonObject(emptyMap()),
        )

    private fun testRunAt(ts: Long, testName: String, outcome: String): DeveloperEvent =
        DeveloperEvent(
            event_id = UUID.randomUUID().toString(),
            session_id = "test",
            ts = ts,
            type = EventType.TEST_RUN.name,
            file = "test_fizz.py",
            payload = buildJsonObject {
                put("test_name", JsonPrimitive(testName))
                put("outcome", JsonPrimitive(outcome))
                put("duration_ms", JsonPrimitive(12))
            },
        )

    private fun caretDwellAt(ts: Long, durationMs: Long, hasErrorMarker: Boolean): DeveloperEvent =
        DeveloperEvent(
            event_id = UUID.randomUUID().toString(),
            session_id = "test",
            ts = ts,
            type = EventType.CARET_DWELL.name,
            file = "Foo.kt",
            payload = buildJsonObject {
                put("line", JsonPrimitive(7))
                put("duration_ms", JsonPrimitive(durationMs))
                put("has_error_marker", JsonPrimitive(hasErrorMarker))
            },
        )

    private fun fileFocusAt(ts: Long, file: String): DeveloperEvent =
        DeveloperEvent(
            event_id = UUID.randomUUID().toString(),
            session_id = "test",
            ts = ts,
            type = EventType.FILE_FOCUS.name,
            file = file,
            payload = JsonObject(emptyMap()),
        )

    private fun editAt(ts: Long, file: String, line: Long): DeveloperEvent =
        DeveloperEvent(
            event_id = UUID.randomUUID().toString(),
            session_id = "test",
            ts = ts,
            type = EventType.EDIT.name,
            file = file,
            payload = buildJsonObject {
                put("chars_added", JsonPrimitive(1))
                put("chars_removed", JsonPrimitive(0))
                put("line", JsonPrimitive(line))
            },
        )
}
