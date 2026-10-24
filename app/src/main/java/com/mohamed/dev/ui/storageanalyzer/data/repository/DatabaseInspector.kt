package com.mohamed.dev.ui.storageanalyzer.data.repository

import com.mohamed.dev.ui.storageanalyzer.data.demo.DemoEnvironment
import com.mohamed.dev.ui.storageanalyzer.data.local.ShowcaseDatabase
import com.mohamed.dev.ui.storageanalyzer.data.model.DatabaseStats
import com.mohamed.dev.ui.storageanalyzer.data.model.TableStat
import com.mohamed.dev.ui.storageanalyzer.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads the app's own SQLite facts: file sizes, page accounting and pragmas.
 *
 * This is intentionally separate from the library's `:room` module: the library
 * inspects *other* databases through JDBC, while this screen is the app's own
 * Room database read through Android's SQLite. Both are shown side by side in
 * the guide so the distinction stays clear.
 */
@Singleton
class DatabaseInspector @Inject constructor(
    private val database: ShowcaseDatabase,
    private val environment: DemoEnvironment,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    suspend fun stats(): DatabaseStats = withContext(io) {
        val db = database.openHelper.readableDatabase
        val pageCount = db.longValue("PRAGMA page_count")
        val pageSize = db.longValue("PRAGMA page_size")
        val freePages = db.longValue("PRAGMA freelist_count")
        val userVersion = db.longValue("PRAGMA user_version")
        val journalMode = db.textValue("PRAGMA journal_mode")

        val tables = mutableListOf<String>()
        db.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' " +
                "AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'android_%' ORDER BY name",
        ).use { cursor ->
            while (cursor.moveToNext()) tables += cursor.getString(0)
        }
        val indexes = mutableListOf<String>()
        db.query(
            "SELECT name FROM sqlite_master WHERE type = 'index' " +
                "AND name NOT LIKE 'sqlite_%' ORDER BY name",
        ).use { cursor ->
            while (cursor.moveToNext()) indexes += cursor.getString(0)
        }

        val tableStats = tables.map { table ->
            val count = runCatching {
                db.query("SELECT COUNT(*) FROM \"${table.replace("\"", "\"\"")}\"").use { cursor ->
                    if (cursor.moveToFirst()) cursor.getLong(0) else 0L
                }
            }.getOrDefault(-1L)
            TableStat(name = table, rowCount = count)
        }

        DatabaseStats(
            databaseBytes = fileLength(ShowcaseDatabase.NAME),
            walBytes = fileLength("${ShowcaseDatabase.NAME}-wal"),
            shmBytes = fileLength("${ShowcaseDatabase.NAME}-shm"),
            pageCount = pageCount,
            pageSize = pageSize,
            freePages = freePages,
            userVersion = userVersion,
            journalMode = journalMode,
            tables = tableStats,
            indexes = indexes,
            measuredAt = Instant.now(),
        )
    }

    /**
     * Runs a truncating WAL checkpoint so the demo can show that sidecar bytes
     * are a real, reclaimable cost rather than a permanent part of the database.
     */
    suspend fun checkpoint(): DatabaseStats = withContext(io) {
        runCatching {
            database.openHelper.writableDatabase
                .query("PRAGMA wal_checkpoint(TRUNCATE)")
                .use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
        }
        stats()
    }

    private fun fileLength(name: String): Long =
        File(environment.databaseDir, name).takeIf { it.isFile }?.length() ?: 0L

    private fun androidx.sqlite.db.SupportSQLiteDatabase.longValue(sql: String): Long =
        query(sql).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L }

    private fun androidx.sqlite.db.SupportSQLiteDatabase.textValue(sql: String): String =
        query(sql).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else "unknown" }
}
