package com.mohamed.storageanalyzer.room

import com.mohamed.storageanalyzer.sqlite.SQLiteDatabaseSpec
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException

/**
 * Internal, read-only probe helper over one persistent JDBC connection.
 *
 * The probe answers the questions neither the analytics engine nor the SDK
 * should block on:
 * - `fingerprint()`: a cheap content fingerprint for change detection —
 *   `PRAGMA data_version` (bumps when another connection commits), `user_version`,
 *   page count and the main file length;
 * - `userVersion()` / `masterIdentityHash()` / `tableNames()`: drift inputs;
 * - `rowCount(table)`: `SELECT COUNT(*)` — a row count is a count, never a
 *   byte metric and never mixed into totals.
 *
 * All answers are `null`/empty when the probe cannot inspect the database;
 * callers must treat that as "unknown" and fall back to re-analysis or
 * other evidence — not as a zero.
 *
 * Connection hygiene: opened with `PRAGMA query_only = ON` and a bounded
 * busy timeout; recreated once after failures so a replaced file or a stale
 * handle does not wedge the monitor.
 */
internal class RoomDbProbe(
    private val spec: SQLiteDatabaseSpec,
    private val busyTimeoutMs: Int = 1_000,
) : AutoCloseable {

    private val fileBased: Boolean =
        spec.path != ":memory:" && !spec.path.startsWith("jdbc:") && !spec.path.startsWith("file:")

    @Volatile
    private var connection: Connection? = null

    /** Cheap fingerprint for [com.mohamed.storageanalyzer.reactive.RefreshPolicy.OnChange]; null = unknown. */
    public fun fingerprint(): String? = try {
        val conn = ensureConnection() ?: return null
        val dataVersion = queryLong(conn, "PRAGMA data_version") ?: return null
        val userVersion = queryLong(conn, "PRAGMA user_version") ?: 0L
        val pageCount = queryLong(conn, "PRAGMA page_count") ?: -1L
        val fileLength = if (fileBased) fileLengthOf() else -1L
        "dv=$dataVersion;uv=$userVersion;pc=$pageCount;fl=$fileLength"
    } catch (e: Exception) {
        dropConnectionQuiet()
        null
    }

    /** `PRAGMA user_version` — Room's schema version; null = unknown. */
    public fun userVersion(): Int? = try {
        val conn = ensureConnection() ?: return null
        queryLong(conn, "PRAGMA user_version")?.toInt()
    } catch (e: Exception) {
        dropConnectionQuiet()
        null
    }

    /** `SELECT identity_hash FROM room_master_table` — null when absent/unreadable. */
    public fun masterIdentityHash(): String? = try {
        val conn = ensureConnection() ?: return null
        conn.createStatement().use { st ->
            st.executeQuery("SELECT identity_hash FROM room_master_table LIMIT 1").use { rs ->
                if (rs.next()) rs.getString(1) else null
            }
        }
    } catch (e: SQLException) {
        null // table absent (or unreadable) — honest "unknown", not an error storm
    } catch (e: InterruptedException) {
        dropConnectionQuiet()
        null
    } catch (e: Exception) {
        dropConnectionQuiet()
        null
    }

    /** User-created table names (framework + internal `sqlite_*` tables excluded upstream). */
    public fun tableNames(): List<String> = try {
        val conn = ensureConnection() ?: return emptyList()
        conn.createStatement().use { st ->
            st.executeQuery(
                "SELECT name FROM sqlite_master WHERE type = 'table' " +
                    "AND name NOT LIKE 'sqlite\\_%' ESCAPE '\\'",
            ).use { rs ->
                val out = ArrayList<String>()
                while (rs.next()) rs.getString(1)?.let { out += it }
                out
            }
        }
    } catch (e: Exception) {
        dropConnectionQuiet()
        emptyList()
    }

    /** Row count of one table; null = unknown. */
    public fun rowCount(tableName: String): Long? = try {
        val conn = ensureConnection() ?: return null
        conn.createStatement().use { st ->
            st.executeQuery("SELECT COUNT(*) FROM " + quote(tableName)).use { rs ->
                if (rs.next()) rs.getLong(1).takeIf { !rs.wasNull() } else null
            }
        }
    } catch (e: Exception) {
        dropConnectionQuiet()
        null
    }

    /** SQL-ident quoting (`"` doubling); safe against injection through table names. */
    public fun quote(name: String): String = "\"" + name.replace("\"", "\"\"") + "\""

    override fun close() {
        dropConnectionQuiet()
    }

    private fun ensureConnection(): Connection? {
        connection?.let { c ->
            if (c.isValid(1)) return c
        }
        dropConnectionQuiet()
        if (!fileBased) return null
        return try {
            val conn = DriverManager.getConnection("jdbc:sqlite:" + File(spec.path).canonicalPath)
            conn.createStatement().use { st ->
                st.execute("PRAGMA query_only=ON")
                st.execute("PRAGMA busy_timeout=$busyTimeoutMs")
            }
            connection = conn
            conn
        } catch (e: Exception) {
            dropConnectionQuiet()
            null
        }
    }

    private fun queryLong(conn: Connection, sql: String): Long? = conn.createStatement().use { st ->
        st.executeQuery(sql).use { rs ->
            if (rs.next()) rs.getLong(1).takeIf { !rs.wasNull() } else null
        }
    }

    private fun fileLengthOf(): Long = runCatching {
        val file = File(spec.path)
        if (file.exists()) file.length() else -1L
    }.getOrDefault(-1L)

    private fun dropConnectionQuiet() {
        val existing = connection
        connection = null
        existing?.let { c -> runCatching { c.close() } }
    }
}
