package com.mohamed.dev.ui.storageanalyzer.data.model

import java.time.Instant

/** Row counts read through SQLite pragmas and `sqlite_master`. */
data class TableStat(
    val name: String,
    val rowCount: Long,
)

/**
 * On-device Room database facts. Every field is read from SQLite or from the
 * database file itself; nothing here is estimated or synthesized.
 */
data class DatabaseStats(
    val databaseBytes: Long,
    val walBytes: Long,
    val shmBytes: Long,
    val pageCount: Long,
    val pageSize: Long,
    val freePages: Long,
    val userVersion: Long,
    val journalMode: String,
    val tables: List<TableStat>,
    val indexes: List<String>,
    val measuredAt: Instant,
) {
    val pageCapacityBytes: Long get() = pageCount * pageSize
    val freeBytes: Long get() = freePages * pageSize
    val sidecarBytes: Long get() = walBytes + shmBytes
}
