package com.mohamed.storageanalyzer.sqlite

import com.mohamed.storageanalyzer.core.config.AnalysisContext
import com.mohamed.storageanalyzer.core.identity.ProviderId

/**
 * One SQLite database to analyze: the main file and (optionally) sidecar
 * conventions. The lifecycle of a connection is per analysis run.
 */
data class SQLiteDatabaseSpec(
    /**
     * Path of a main database file, or `:memory:` for an in-memory database.
     * Non-file URLs (URIs) are passed through verbatim to the JDBC driver.
     */
    val path: String,

    /**
     * Human label for reports; defaults to [path] (subject to redaction when
     * the run asks for it).
     */
    val label: String? = null,

    /**
     * When true (default), connections are opened read-only. The provider
     * never executes state-changing statements either way; the flag
     * just hardens the access path.
     */
    val readOnly: Boolean = true,

    /**
     * Busy timeout in ms (SQLite busy handler). A per-connection setting —
     * it does not alter persistent state.
     */
    val busyTimeoutMs: Int = 5_000,
)

/** Connection descriptor for a single live connection. */
class SQLiteConnection internal constructor(
    val spec: SQLiteDatabaseSpec,
    /** The JDBC connection in read-only/query-only mode. */
    private val delegate: java.sql.Connection,
) : AutoCloseable {

    /** Executes [sql] and pipes rows through [block] using the provided cursor. */
    fun <T> query(sql: String, bind: List<Any?> = emptyList(), block: (SimpleCursor) -> T): T? =
        delegate.prepareStatement(sql).use { stmt ->
            bind.forEachIndexed { idx, value ->
                when (value) {
                    null -> stmt.setNull(idx + 1, java.sql.Types.NULL)
                    is Int -> stmt.setInt(idx + 1, value)
                    is Long -> stmt.setLong(idx + 1, value)
                    is String -> stmt.setString(idx + 1, value)
                    is ByteArray -> stmt.setBytes(idx + 1, value)
                    is Double -> stmt.setDouble(idx + 1, value)
                    is Boolean -> stmt.setInt(idx + 1, if (value) 1 else 0)
                    else -> stmt.setObject(idx + 1, value)
                }
            }
            stmt.executeQuery().use { rs -> block(SimpleCursor(rs)) }
        }

    /** Executes a scalar query or null when no row. */
    fun scalarLong(sql: String, bind: List<Any?> = emptyList()): Long? =
        query(sql, bind) { c -> if (c.next()) c.long(1) else null }

    fun scalarString(sql: String, bind: List<Any?> = emptyList()): String? =
        query(sql, bind) { c -> if (c.next()) c.string(1) else null }

    /** All rows folded into a list; sized statements only (bounded by callers). */
    fun rows(sql: String, bind: List<Any?> = emptyList(), maxRows: Int = Int.MAX_VALUE): List<Map<String, Any?>> =
        query(sql, bind) { c ->
            buildList {
                while (c.next() && size < maxRows) add(c.currentRow())
            }
        } ?: emptyList()

    /**
     * Streams transformed rows lazily. The underlying statement stays open
     * while the sequence is iterated and is closed on completion/disposal —
     * iterate promptly; sequences hold one read statement each.
     */
    fun <T> stream(sql: String, bind: List<Any?> = emptyList(), transform: (SimpleCursor) -> T): Sequence<T> = sequence {
        delegate.prepareStatement(sql).use { stmt ->
            bind.forEachIndexed { idx, value -> bindParam(stmt, idx, value) }
            stmt.executeQuery().use { rs ->
                val cursor = SimpleCursor(rs)
                while (rs.next()) yield(transform(cursor))
            }
        }
    }

    private fun bindParam(stmt: java.sql.PreparedStatement, idx: Int, value: Any?) {
        when (value) {
            null -> stmt.setNull(idx + 1, java.sql.Types.NULL)
            is Int -> stmt.setInt(idx + 1, value)
            is Long -> stmt.setLong(idx + 1, value)
            is String -> stmt.setString(idx + 1, value)
            is ByteArray -> stmt.setBytes(idx + 1, value)
            is Double -> stmt.setDouble(idx + 1, value)
            is Boolean -> stmt.setInt(idx + 1, if (value) 1 else 0)
            else -> stmt.setObject(idx + 1, value)
        }
    }

    override fun close() {
        delegate.close()
    }

    companion object {
        internal val providerId: ProviderId = ProviderId(SQLiteStorageProvider.providerIdValue)

        /**
         * Opens a new safe connection for one spec and registers the
         * record-format helper functions for this session.
         *
         * Read-only intent is applied two ways (defense in depth):
         * 1. driver flags via SQLiteConfig (`SQLITE_OPEN_READONLY`);
         * 2. `PRAGMA query_only = ON` on the session.
         * Neither changes persistent file state; the busy timeout is a
         * session-level parameter, also not persisted. When [spec.readOnly]
         * is false the session technically permits writes — but this library
         * never executes state-changing statements (non-destructive by design).
         */
        fun open(spec: SQLiteDatabaseSpec): SQLiteConnection {
            val effectiveUrl = when (spec.path) {
                ":memory:" -> "jdbc:sqlite::memory:"
                else -> {
                    val file = java.io.File(spec.path)
                    if (!file.exists() && !spec.path.startsWith("jdbc:") && !spec.path.startsWith("file:")) {
                        throw IllegalArgumentException("SQLite database file does not exist: ${spec.path}")
                    }
                    if (spec.path.startsWith("jdbc:")) spec.path else "jdbc:sqlite:" + file.absolutePath
                }
            }
            val cfg = org.sqlite.SQLiteConfig()
            cfg.setReadOnly(spec.readOnly)
            cfg.setBusyTimeout(spec.busyTimeoutMs)
            val ds = org.sqlite.SQLiteDataSource(cfg)
            ds.url = effectiveUrl
            val conn = try {
                ds.connection
            } catch (e: java.sql.SQLException) {
                if (spec.readOnly) {
                    // WAL-mode databases cannot always be opened with strict
                    // SQLITE_OPEN_READONLY (no fresh -shm creation). Retry with a
                    // normal handle; the session stays non-destructive via
                    // PRAGMA query_only = ON and this library never writes.
                    val cfg2 = org.sqlite.SQLiteConfig()
                    cfg2.setBusyTimeout(spec.busyTimeoutMs)
                    val ds2 = org.sqlite.SQLiteDataSource(cfg2)
                    ds2.url = effectiveUrl
                    try {
                        ds2.connection
                    } catch (e2: java.sql.SQLException) {
                        throw IllegalStateException("cannot open SQLite database ${spec.path}: ${e2.message}", e2)
                    }
                } else {
                    throw IllegalStateException("cannot open SQLite database ${spec.path}: ${e.message}", e)
                }
            }
            if (spec.readOnly) {
                conn.createStatement().use { it.execute("PRAGMA query_only = ON") }
            }
            SQLiteUdfSupport.register(conn)
            return SQLiteConnection(spec, conn)
        }
    }

    /** Minimal cursor indirection over ResultSet for internal use. */
    class SimpleCursor(private val rs: java.sql.ResultSet) {
        fun next(): Boolean = rs.next()

        fun string(col: Int): String? = rs.getString(col)
        fun long(col: Int): Long = rs.getLong(col)
        fun double(col: Int): Double = rs.getDouble(col)
        fun bytes(col: Int): ByteArray? = rs.getBytes(col)
        fun objectOf(col: Int): Any? = rs.getObject(col)

        fun columnNames(): List<String> {
            val meta = rs.metaData
            return (1..meta.columnCount).map { meta.getColumnName(it) }
        }

        fun currentRow(): Map<String, Any?> {
            val meta = rs.metaData
            val names = columnNames()
            return buildMap {
                for ((idx, name) in names.withIndex()) {
                    put(name, meta.getColumnType(idx + 1).let { byType(rs, idx + 1, it) })
                }
            }
        }

        private fun byType(rs: java.sql.ResultSet, col: Int, type: Int): Any? = when (type) {
            java.sql.Types.BLOB -> rs.getBytes(col)
            java.sql.Types.VARCHAR, java.sql.Types.CHAR, java.sql.Types.CLOB -> rs.getString(col)
            java.sql.Types.BIGINT, java.sql.Types.INTEGER -> rs.getLong(col)
            java.sql.Types.DOUBLE, java.sql.Types.FLOAT -> rs.getDouble(col)
            java.sql.Types.REAL -> rs.getDouble(col)
            else -> rs.getObject(col)
        }
    }
}

/**
 * Runtime-detected capability flags of one SQLite build (dbstat is
 * optional; some platform builds compile without it).
 */
data class SQLiteCapabilities(
    val databaseFiles: Boolean,
    val walFiles: Boolean,
    val dbstatAvailable: Boolean,
    val freelistCountAvailable: Boolean,
    val integrityCheckAvailable: Boolean,
    val recordSizeEstimates: Boolean = true,
) {
    companion object {
        fun probe(sqlite: SQLiteConnection, ctx: AnalysisContext? = null): SQLiteCapabilities {
            val walMode = sqlite.scalarString("PRAGMA journal_mode") != null
            val freelist = sqlite.scalarLong("PRAGMA freelist_count") != null
            val integrity = runCatching {
                sqlite.scalarString("PRAGMA integrity_check")
            }.isSuccess
            val dbstat = hasDbstat(sqlite)
            return SQLiteCapabilities(
                databaseFiles = true,
                walFiles = walMode,
                dbstatAvailable = dbstat,
                freelistCountAvailable = freelist,
                integrityCheckAvailable = integrity,
            )
        }

        private fun hasDbstat(sqlite: SQLiteConnection): Boolean = runCatching {
            sqlite.query("SELECT 1 FROM dbstat LIMIT 1") { c -> c.next() }
        }.isSuccess
    }
}
