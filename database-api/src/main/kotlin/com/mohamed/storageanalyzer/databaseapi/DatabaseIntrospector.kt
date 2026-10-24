package com.mohamed.storageanalyzer.databaseapi

import com.mohamed.storageanalyzer.core.identity.ProviderId

/**
 * Stable contracts for relational/database analytics providers.
 *
 * The core does **not** depend on these types: a database provider only uses
 * them for uniform introspection (schemas, files, page statistics) so that
 * multiple engines can share conventions. Engines add their own
 * storage-engine-specific metadata on top.
 */

/** Role of an auxiliary file that belongs to a database. */
enum class DatabaseFileRole { MAIN, WAL, SHM, JOURNAL, TEMP, UNKNOWN }

/** One physical file that belongs to a database. */
data class DatabaseFileInfo(
    val role: DatabaseFileRole,
    val identity: com.mohamed.storageanalyzer.core.identity.ResourceIdentity,
    val sizeBytes: com.mohamed.storageanalyzer.core.measurement.ByteCount,
)

/** Column metadata as reported by the backend (portable subset). */
data class ColumnInfo(
    val name: String,
    val declaredType: String,
    val isNullable: Boolean,
    val isPrimaryKeyPart: Boolean,
    val defaultValueSql: String? = null,
    /** 1-based position within the primary key (0 when not part of it). */
    val primaryKeyOrdinal: Int = 0,
)

/** Column ordering within an index. */
data class IndexColumn(val name: String, val descending: Boolean = false)

enum class TableKind { BASE_TABLE, VIEW, VIRTUAL_TABLE }

/** A table/collection as the provider sees it internally. */
data class TableDescriptor(
    val schemaName: String? = null,
    val tableName: String,
    val columns: List<ColumnInfo>,
    val withoutRowid: Boolean,
    val rowCount: Long?,
    val kind: TableKind = TableKind.BASE_TABLE,
)

enum class IndexKind { INDEX, UNIQUE_INDEX, PRIMARY_KEY }

/** An index as the provider sees it internally. */
data class IndexDescriptor(
    val indexName: String,
    val tableName: String,
    val columns: List<IndexColumn>,
    val kind: IndexKind,
    val partialPredicateSql: String? = null,
)

/** Page-level statistics, the common denominator of page-granular engines. */
data class DatabasePageStats(
    /** Engine page size in bytes. */
    val pageSize: Long,
    /** Total physical pages under the engine's accounting. */
    val pageCount: Long,
    /** Pages on the freelist (allocated but unused). */
    val freelistPages: Long,
    /** Row-level aliasing information for rowid-alias tables, when known. */
    val autoVacuumMode: String? = null,
    /** Journaling mode of the database as reported. */
    val journalMode: String? = null,
    /** Text encoding (engines that expose it). */
    val encoding: String? = null,
) {
    /** Database page capacity in bytes (the CAPACITY metric): page_count × page_size. */
    val pageCapacityBytes: com.mohamed.storageanalyzer.core.measurement.ByteCount
        get() = com.mohamed.storageanalyzer.core.measurement.ByteCount.of(
            Math.multiplyExact(pageSize, pageCount),
        )

    /** Freelist byte volume. */
    val freelistBytes: com.mohamed.storageanalyzer.core.measurement.ByteCount
        get() = com.mohamed.storageanalyzer.core.measurement.ByteCount.of(pageSize * freelistPages)
}

/**
 * The introspection SPI each concrete engine implements on top of its native
 * APIs. All methods must be read-only, safe under concurrent writes, and must
 * throw nothing except provider-fatal conditions.
 */
interface DatabaseIntrospector {
    val providerId: ProviderId

    /** The physical files of this database system (main, WAL, journal, temp). */
    fun files(): List<DatabaseFileInfo>

    /** Page statistics when the backend is page-organized; `null` otherwise. */
    fun pageStats(): DatabasePageStats?

    /** All tables visible to the analyzer. */
    fun tables(): Sequence<TableDescriptor>

    /** All indexes visible to the analyzer. */
    fun indexes(): Sequence<IndexDescriptor>

    /** Read-only consistency verification (SQLite: `integrity_check`) or `null`. */
    fun consistencyCheck(): List<String> = emptyList()
}
