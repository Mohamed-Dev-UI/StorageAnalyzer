package com.mohamed.storageanalyzer.sqlite

import com.mohamed.storageanalyzer.core.identity.ProviderId
import com.mohamed.storageanalyzer.databaseapi.ColumnInfo
import com.mohamed.storageanalyzer.databaseapi.DatabaseFileInfo
import com.mohamed.storageanalyzer.databaseapi.DatabaseFileRole
import com.mohamed.storageanalyzer.databaseapi.DatabaseIntrospector
import com.mohamed.storageanalyzer.databaseapi.DatabasePageStats
import com.mohamed.storageanalyzer.databaseapi.IndexColumn
import com.mohamed.storageanalyzer.databaseapi.IndexDescriptor
import com.mohamed.storageanalyzer.databaseapi.IndexKind
import com.mohamed.storageanalyzer.databaseapi.TableDescriptor
import com.mohamed.storageanalyzer.databaseapi.TableKind
import java.io.File

/**
 * SQLite implementation of the read-only [DatabaseIntrospector] contract.
 *
 * All statements are PRAGMAs / catalog SELECTs; nothing in this object writes
 * to the database, changes pragmas permanently, or forces checkpoints
 * (non-destructive by design). Errors surface as null/empty results with
 * diagnostics attached by the provider, never as fabricated zeros.
 */
internal class SQLiteIntrospection(
    private val spec: SQLiteDatabaseSpec,
    private val sqlite: SQLiteConnection,
    private val capabilities: SQLiteCapabilities,
) : DatabaseIntrospector {

    override val providerId: ProviderId = SQLiteConnection.providerId

    /**
     * Physical files: the main file plus present sidecars. WAL/SHM sizes read
     * directly from the filesystem (EXACT); no checkpoint is triggered.
     */
    override fun files(): List<DatabaseFileInfo> {
        if (spec.path == ":memory:") return emptyList() // no physical file
        if (spec.path.startsWith("jdbc:") || spec.path.startsWith("file:")) return emptyList()
        val main = File(spec.path)
        if (!main.isFile) return emptyList()
        val out = mutableListOf(DatabaseFileInfo(DatabaseFileRole.MAIN, sidecarIdentity("_", DatabaseFileRole.MAIN), sizeOf(main)))
        for (suffix in listOf("-wal", "-shm", "-journal")) {
            val f = File(main.path + suffix)
            if (f.isFile) {
                val role = when (suffix) {
                    "-wal" -> DatabaseFileRole.WAL
                    "-shm" -> DatabaseFileRole.SHM
                    else -> DatabaseFileRole.JOURNAL
                }
                out += DatabaseFileInfo(role, sidecarIdentity(suffix, role), sizeOf(f))
            }
        }
        return out
    }

    private fun sizeOf(f: File): com.mohamed.storageanalyzer.core.measurement.ByteCount =
        com.mohamed.storageanalyzer.core.measurement.ByteCount.of(f.length())

    /** Quote a schema identifier for catalog PRAGMAs. */
    private fun quoted(name: String): String = "\"" + name.replace("\"", "\"\"") + "\""

    /** Page statistics; returns null when the backend refuses the PRAGMAs. */
    override fun pageStats(): DatabasePageStats? {
        val pageSize = sqlite.scalarLong("PRAGMA page_size") ?: return null
        val pageCount = sqlite.scalarLong("PRAGMA page_count") ?: return null
        val freelist = sqlite.scalarLong("PRAGMA freelist_count") ?: 0L
        val journalMode = sqlite.scalarString("PRAGMA journal_mode")
        val encoding = sqlite.scalarString("PRAGMA encoding")
        val autoVacuum =
            when (sqlite.scalarLong("PRAGMA auto_vacuum")) {
                0L -> "none"
                1L -> "full"
                2L -> "incremental"
                else -> null
            }
        return DatabasePageStats(
            pageSize = pageSize,
            pageCount = pageCount,
            freelistPages = freelist,
            autoVacuumMode = autoVacuum,
            journalMode = journalMode,
            encoding = encoding,
        )
    }

    /** Catalog tables (base + virtual + internal). Views have no storage here. */
    private val rowidAliases = mutableMapOf<String, String?>()

    fun rowidAliasOf(tableName: String): String? = rowidAliases[tableName]

    override fun tables(): Sequence<TableDescriptor> = sequence {
        val raw = sqlite.rows(
            "SELECT name, COALESCE(sql, '') AS sql FROM sqlite_master WHERE type = 'table' ORDER BY name",
        )
        for (row in raw) {
            val name = row["name"] as? String ?: continue
            val createSql = (row["sql"] as? String) ?: ""
            val isVirtual = createSql.uppercase().contains("CREATE VIRTUAL TABLE")
            val withoutRowid = createSql.uppercase().contains("WITHOUT ROWID")
            val columns = tableColumns(name)
            val alias = detectRowidAlias(createSql, withoutRowid, isVirtual, columns)
            rowidAliases[name] = alias
            yield(
                TableDescriptor(
                    schemaName = "main",
                    tableName = name,
                    columns = columns,
                    withoutRowid = withoutRowid,
                    rowCount = null, // row counting is a measurement decision, not catalog info
                    kind = if (isVirtual) TableKind.VIRTUAL_TABLE else TableKind.BASE_TABLE,
                ),
            )
        }
    }

    /**
     * Rowid-alias detection: exactly one `INTEGER` primary key on a rowid
     * table, excluding the `INTEGER PRIMARY KEY DESC` form (which creates a
     * separate index instead of aliasing the rowid).
     */
    private fun detectRowidAlias(
        createSql: String,
        withoutRowid: Boolean,
        isVirtual: Boolean,
        columns: List<ColumnInfo>,
    ): String? {
        if (withoutRowid || isVirtual) return null
        if (createSql.uppercase().contains("PRIMARY KEY DESC")) return null
        val pk = columns.filter { it.isPrimaryKeyPart }
        if (pk.size != 1) return null
        return if (pk.first().declaredType.uppercase().trim() == "INTEGER") pk.first().name else null
    }

    private fun tableColumns(tableName: String): List<ColumnInfo> = sqlite
        .rows("PRAGMA table_info(${quoted(tableName)})")
        .mapNotNull { row ->
            val name = row["name"] as? String ?: return@mapNotNull null
            val type = row["type"] as? String ?: ""
            val notNull = (row["notnull"] as? Long ?: 0L) != 0L
            val pkOrdinal = row["pk"] as? Long ?: 0L
            ColumnInfo(
                name = name,
                declaredType = type,
                isNullable = !notNull,
                isPrimaryKeyPart = pkOrdinal > 0L,
                defaultValueSql = row["dflt_value"]?.toString(),
                primaryKeyOrdinal = pkOrdinal.toInt(),
            )
        }

    /** All indexes (incl. implicit `sqlite_autoindex_*`); views excluded. */
    override fun indexes(): Sequence<IndexDescriptor> = sequence {
        val raw = sqlite.rows(
            "SELECT name, tbl_name, COALESCE(sql, '') AS sql FROM sqlite_master WHERE type = 'index' ORDER BY tbl_name, name",
        )
        for (row in raw) {
            val indexName = row["name"] as? String ?: continue
            val tableName = row["tbl_name"] as? String ?: continue
            val createSql = (row["sql"] as? String) ?: ""
            val info = sqlite.rows("PRAGMA index_info(${quoted(indexName)})")
            val columns = info
                .mapNotNull { r ->
                    val n = r["name"] as? String ?: return@mapNotNull null
                    val seqno = (r["seqno"] as? Long) ?: 0L
                    val desc = (r["desc"] as? Long) ?: 0L
                    Triple(seqno, n, desc)
                }
                .sortedBy { it.first }
                .map { IndexColumn(it.second, it.third != 0L) }
            val implicit = indexName.startsWith("sqlite_autoindex")
            val unique = createSql.uppercase().contains("UNIQUE") || implicit
            yield(
                IndexDescriptor(
                    indexName = indexName,
                    tableName = tableName,
                    columns = columns,
                    kind = when {
                        implicit -> IndexKind.PRIMARY_KEY
                        unique -> IndexKind.UNIQUE_INDEX
                        else -> IndexKind.INDEX
                    },
                    partialPredicateSql = if (createSql.uppercase().contains("WHERE")) createSql else null,
                ),
            )
        }
    }

    override fun consistencyCheck(): List<String> {
        if (!capabilities.integrityCheckAvailable) return emptyList()
        return sqlite.rows("PRAGMA integrity_check", maxRows = 100)
            .mapNotNull { row -> row.values.filterIsInstance<String>().firstOrNull() }
    }

    // -------------------------------------------------------------- dbstat area

    /**
     * One full dbstat pass grouped by b-tree name → bytes. `null` when dbstat
     * is unavailable in this build. Single scan; callers share this map.
     */
    fun dbstatSums(): Map<String, Long>? {
        if (!capabilities.dbstatAvailable) return null
        return try {
            val rows = sqlite.rows(
                "SELECT name, SUM(pgsize) AS bytes FROM dbstat GROUP BY name",
            )
            buildMap {
                for (r in rows) {
                    val n = r["name"] as? String ?: continue
                    val b = r["bytes"] as? Long ?: continue
                    put(n, b)
                }
            }
        } catch (e: java.sql.SQLException) {
            null
        }
    }

    /** Bytes of one specific b-tree (exact when dbstat is available). */
    fun btreeBytes(dbstat: Map<String, Long>, name: String): Long? = dbstat[name]

    /**
     * Bytes of the internal metadata btrees (sqlite_schema + sqlite_stat* +
     * sqlite_sequence). `sqlite_autoindex_*` btrees are NOT metadata — they
     * are indexes of user tables and tracked under DATABASE_INDEX_STORAGE.
     */
    fun metadataBytes(dbstat: Map<String, Long>): Long {
        var total = 0L
        for ((name, bytes) in dbstat) {
            if (name == "sqlite_schema" || name == "sqlite_master" ||
                (name.startsWith("sqlite_") && !name.startsWith("sqlite_autoindex"))
            ) {
                total += bytes
            }
        }
        return total
    }

    /** Internal sidecar resource identity for catalog reporting. */
    fun sidecarIdentity(suffix: String, role: DatabaseFileRole): com.mohamed.storageanalyzer.core.identity.ResourceIdentity {
        val mainFile = File(spec.path)
        val parent = mainFile.canonicalFile.parent ?: mainFile.absoluteFile.parent ?: "."
        return com.mohamed.storageanalyzer.core.identity.ResourceIdentity(
            providerId = providerId,
            systemId = com.mohamed.storageanalyzer.core.identity.StorageSystemId(parent),
            kind = com.mohamed.storageanalyzer.core.identity.ResourceKind.FILE,
            namespace = "sidecar-${role.name.lowercase()}",
            nativeId = mainFile.name + suffix,
        )
    }
}
