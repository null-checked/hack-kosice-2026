package com.rubberduck.live.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.rubberduck.live.detector.EventBus
import com.rubberduck.live.model.DeveloperEvent
import com.rubberduck.live.model.EventType
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.schedule
import java.util.*

@Service
class AntiPatternRadar(private val project: Project) {
    enum class Severity { LOW, MEDIUM, HIGH }

    private data class StuckEvent(val timestamp: Long)

    private val fileEventMap = ConcurrentHashMap<String, ConcurrentLinkedQueue<StuckEvent>>()
    private val windowMillis = 24 * 60 * 60 * 1000L // 24 hours

    init {
        EventBus.subscribe { event ->
            if (event.type == EventType.STUCK_DETECTED && event.filePath != null) {
                val queue = fileEventMap.computeIfAbsent(event.filePath) { ConcurrentLinkedQueue() }
                queue.add(StuckEvent(System.currentTimeMillis()))
                cleanupOldEvents(queue)
            }
        }
        // Optionally, schedule periodic cleanup
        Timer().schedule(60 * 60 * 1000L, 60 * 60 * 1000L) { // every hour
            cleanupAll()
        }
    }

    private fun cleanupOldEvents(queue: ConcurrentLinkedQueue<StuckEvent>) {
        val cutoff = System.currentTimeMillis() - windowMillis
        while (queue.peek() != null && queue.peek().timestamp < cutoff) {
            queue.poll()
        }
    }

    private fun cleanupAll() {
        val cutoff = System.currentTimeMillis() - windowMillis
        for (queue in fileEventMap.values) {
            while (queue.peek() != null && queue.peek().timestamp < cutoff) {
                queue.poll()
            }
        }
    }

    fun getFileSeverity(virtualFile: VirtualFile): Severity? {
        val path = virtualFile.path
        val queue = fileEventMap[path] ?: return null
        cleanupOldEvents(queue)
        val count = queue.size
        return when {
            count >= 6 -> Severity.HIGH
            count >= 3 -> Severity.MEDIUM
            count >= 1 -> Severity.LOW
            else -> null
        }
    }

    fun getTopFiles(limit: Int = 5): List<Pair<String, Int>> {
        return fileEventMap.entries
            .map { it.key to it.value.size }
            .sortedByDescending { it.second }
            .take(limit)
    }

    fun getSeverityDistribution(): Map<Severity, Int> {
        val result = mutableMapOf(Severity.LOW to 0, Severity.MEDIUM to 0, Severity.HIGH to 0)
        for (queue in fileEventMap.values) {
            val count = queue.size
            when {
                count >= 6 -> result[Severity.HIGH] = result[Severity.HIGH]!! + 1
                count >= 3 -> result[Severity.MEDIUM] = result[Severity.MEDIUM]!! + 1
                count >= 1 -> result[Severity.LOW] = result[Severity.LOW]!! + 1
            }
        }
        return result
    }

    fun clearHistory() {
        fileEventMap.clear()
    }
}