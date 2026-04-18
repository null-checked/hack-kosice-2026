package com.rubberduck.live.services

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.rubberduck.live.detector.EventBus
import com.rubberduck.live.model.EventType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.util.Timer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.schedule

/**
 * Per-project "anti-pattern radar": tallies `STUCK_DETECTED` events by the
 * file where they fired, within a rolling 24-hour window. The
 * [AntiPatternIconProvider] reads [getFileSeverity] to decorate files in the
 * project view with a colored overlay when they're frustration hot-spots.
 *
 * Subscribes to [EventBus.events] via coroutine `collect` (the bus is a
 * `SharedFlow`, not a callback-style listener).
 */
@Service(Service.Level.PROJECT)
class AntiPatternRadar(private val project: Project) {
    enum class Severity { LOW, MEDIUM, HIGH }

    private data class StuckEvent(val timestamp: Long)

    private val fileEventMap = ConcurrentHashMap<String, ConcurrentLinkedQueue<StuckEvent>>()
    private val windowMillis = 24 * 60 * 60 * 1000L // 24 hours

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val cleanupTimer = Timer("rubberduck-antipattern-cleanup", true)

    init {
        scope.launch {
            EventBus.getInstance().events.collect { event ->
                if (event.typeEnum == EventType.STUCK_DETECTED) {
                    val path = event.file ?: return@collect
                    val queue = fileEventMap.computeIfAbsent(path) { ConcurrentLinkedQueue() }
                    queue.add(StuckEvent(System.currentTimeMillis()))
                    cleanupOldEvents(queue)
                }
            }
        }
        cleanupTimer.schedule(60 * 60 * 1000L, 60 * 60 * 1000L) { cleanupAll() }
    }

    private fun cleanupOldEvents(queue: ConcurrentLinkedQueue<StuckEvent>) {
        val cutoff = System.currentTimeMillis() - windowMillis
        while (queue.peek()?.let { it.timestamp < cutoff } == true) {
            queue.poll()
        }
    }

    private fun cleanupAll() {
        for (queue in fileEventMap.values) cleanupOldEvents(queue)
    }

    fun getFileSeverity(virtualFile: VirtualFile): Severity? {
        val path = virtualFile.path
        val queue = fileEventMap[path] ?: return null
        cleanupOldEvents(queue)
        return when (val count = queue.size) {
            in 6..Int.MAX_VALUE -> Severity.HIGH
            in 3..5 -> Severity.MEDIUM
            in 1..2 -> Severity.LOW
            else -> null.also { @Suppress("UNUSED_EXPRESSION") count }
        }
    }

    fun getTopFiles(limit: Int = 5): List<Pair<String, Int>> =
        fileEventMap.entries
            .map { it.key to it.value.size }
            .sortedByDescending { it.second }
            .take(limit)

    fun getSeverityDistribution(): Map<Severity, Int> {
        val result = mutableMapOf(Severity.LOW to 0, Severity.MEDIUM to 0, Severity.HIGH to 0)
        for (queue in fileEventMap.values) {
            val count = queue.size
            when {
                count >= 6 -> result[Severity.HIGH] = result.getValue(Severity.HIGH) + 1
                count >= 3 -> result[Severity.MEDIUM] = result.getValue(Severity.MEDIUM) + 1
                count >= 1 -> result[Severity.LOW] = result.getValue(Severity.LOW) + 1
            }
        }
        return result
    }

    fun clearHistory() {
        fileEventMap.clear()
    }

    fun dispose() {
        cleanupTimer.cancel()
        scope.cancel()
    }

    companion object {
        fun getInstance(project: Project): AntiPatternRadar =
            project.getService(AntiPatternRadar::class.java)
    }
}
