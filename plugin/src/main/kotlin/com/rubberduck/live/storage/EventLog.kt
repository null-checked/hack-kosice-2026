package com.rubberduck.live.storage

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.rubberduck.live.model.DeveloperEvent
import com.rubberduck.live.net.JsonCodec
import kotlinx.serialization.encodeToString
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

/**
 * SQLite-backed append-only event log at
 * `$IDE_CONFIG_DIR/rubberduck-live/events.db` (see ARCHITECTURE.md §9).
 *
 * On startup: creates the schema if missing and deletes rows older than 30
 * days. Writes are synchronous but called from a background dispatcher; the
 * `sqlite-jdbc` driver is bundled in the plugin classpath.
 */
@Service(Service.Level.APP)
class EventLog {

    private val log = Logger.getInstance(EventLog::class.java)
    @Volatile private var connection: Connection? = null

    init {
        runCatching { initialize() }.onFailure { log.warn("EventLog init failed: ${it.message}", it) }
    }

    private fun initialize() {
        val dir: Path = Path.of(PathManager.getConfigPath(), "rubberduck-live")
        Files.createDirectories(dir)
        val dbPath = dir.resolve("events.db").toAbsolutePath().toString()
        // Force driver load in the plugin classloader.
        Class.forName("org.sqlite.JDBC")
        val conn = DriverManager.getConnection("jdbc:sqlite:$dbPath")
        conn.autoCommit = true
        conn.createStatement().use { st ->
            st.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS events (
                    id TEXT PRIMARY KEY,
                    session_id TEXT NOT NULL,
                    ts INTEGER NOT NULL,
                    type TEXT NOT NULL,
                    file TEXT,
                    payload_json TEXT NOT NULL
                )
                """.trimIndent()
            )
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_events_ts ON events(ts)")
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_events_type ON events(type)")
        }
        // 30-day purge on startup.
        val cutoff = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
        conn.prepareStatement("DELETE FROM events WHERE ts < ?").use { ps ->
            ps.setLong(1, cutoff)
            ps.executeUpdate()
        }
        connection = conn
        log.info("EventLog ready at $dbPath")
    }

    /** Append [event] to the log. Safe to call from any thread. */
    fun append(event: DeveloperEvent) {
        val conn = connection ?: return
        try {
            conn.prepareStatement(
                "INSERT OR REPLACE INTO events (id, session_id, ts, type, file, payload_json) VALUES (?,?,?,?,?,?)"
            ).use { ps ->
                ps.setString(1, event.event_id)
                ps.setString(2, event.session_id)
                ps.setLong(3, event.ts)
                ps.setString(4, event.type)
                ps.setString(5, event.file)
                ps.setString(6, JsonCodec.json.encodeToString(event.payload))
                ps.executeUpdate()
            }
        } catch (t: Throwable) {
            log.warn("EventLog.append failed: ${t.message}")
        }
    }

    /** Close the underlying connection. Called on plugin unload / IDE exit. */
    fun shutdown() {
        runCatching { connection?.close() }
        connection = null
    }

    companion object {
        fun getInstance(): EventLog =
            ApplicationManager.getApplication().getService(EventLog::class.java)
    }
}
