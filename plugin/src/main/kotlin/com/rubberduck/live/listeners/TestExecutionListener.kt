package com.rubberduck.live.listeners

import com.intellij.execution.testframework.sm.runner.SMTRunnerEventsAdapter
import com.intellij.execution.testframework.sm.runner.SMTRunnerEventsListener
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.openapi.project.Project
import com.rubberduck.live.detector.EventEmitter
import com.rubberduck.live.detector.SessionContext
import com.rubberduck.live.model.EventType

/**
 * Emits `TEST_RUN` events from IntelliJ's SMT test runner.
 *
 * Relies on `SMTRunnerEventsListener.TEST_STATUS` which the platform fires for
 * JUnit, pytest-bundled, Gradle test, etc. Each finished test emits one event
 * with `outcome` = pass|fail|error, the test name, duration, and a short
 * failure signature when available.
 */
class TestExecutionListener(private val project: Project) {

    fun install(parent: com.intellij.openapi.Disposable) {
        val bus = project.messageBus.connect(parent)
        bus.subscribe(
            SMTRunnerEventsListener.TEST_STATUS,
            object : SMTRunnerEventsAdapter() {
                override fun onTestFinished(test: SMTestProxy) = record(test)
                override fun onTestFailed(test: SMTestProxy) = record(test)
                override fun onSuiteFinished(suite: SMTestProxy) { /* skip */ }
            },
        )
    }

    private fun record(test: SMTestProxy) {
        val outcome = when {
            test.isDefect -> if (test.isPassed) "pass" else "fail"
            test.isPassed -> "pass"
            test.isIgnored -> "pass"
            else -> "error"
        }
        val signature = test.errorMessage?.lineSequence()?.firstOrNull()?.take(200)
        val payload = EventEmitter.json(
            "test_name" to test.name,
            "outcome" to outcome,
            "duration_ms" to (test.duration ?: 0L),
            "failure_signature" to signature,
        )
        if (outcome != "pass") {
            SessionContext.getInstance().lastTestFailureSignature = signature
        }
        val file = SessionContext.getInstance().activeFile
        EventEmitter.emit(EventType.TEST_RUN, file = file, payload = payload)
    }
}
