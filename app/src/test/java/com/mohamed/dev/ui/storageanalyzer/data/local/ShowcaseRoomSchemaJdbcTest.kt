package com.mohamed.dev.ui.storageanalyzer.data.local

import com.mohamed.storageanalyzer.core.capability.StorageCapability
import com.mohamed.storageanalyzer.core.config.AnalysisContext
import com.mohamed.storageanalyzer.core.identity.ResourceKind
import com.mohamed.storageanalyzer.core.measurement.MeasurementState
import com.mohamed.storageanalyzer.core.measurement.MetricType
import com.mohamed.storageanalyzer.room.RoomDatabaseAnalyzer
import com.mohamed.storageanalyzer.room.RoomEntitySource
import com.mohamed.storageanalyzer.room.RoomSchemaBundle
import com.mohamed.storageanalyzer.sqlite.SQLiteDatabaseSpec
import com.mohamed.storageanalyzer.sqlite.SQLiteStorageProvider
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The library's `:room` and `:sqlite` modules are JDBC-based and cannot run in
 * the APK. This JVM test proves the showcased contract against a real database
 * created from the app's own exported Room schema: no hand-written DDL drift.
 */
class ShowcaseRoomSchemaJdbcTest {

    private val schemaPath: Path = Path.of(
        "schemas",
        "com.mohamed.dev.ui.storageanalyzer.data.local.ShowcaseDatabase",
        "1.json",
    )

    private fun bundle(): RoomSchemaBundle {
        assertTrue(Files.exists(schemaPath), "exported Room schema missing at $schemaPath")
        return RoomSchemaBundle.parse(schemaPath)
    }

    private fun createDatabaseFromSchema(bundle: RoomSchemaBundle): File {
        val dir = Files.createTempDirectory("showcase-room-schema")
        val file = dir.resolve("showcase.db").toFile()
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite:" + file.canonicalPath).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("PRAGMA journal_mode=WAL")
                statement.execute("PRAGMA user_version=${bundle.version}")
                for (entity in bundle.entities) {
                    val ddl = entity.createSql
                        ?.replace("\${TABLE_NAME}", entity.tableName)
                        ?: error("entity ${entity.tableName} has no createSql")
                    statement.execute(ddl)
                }
                statement.execute(
                    "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)",
                )
                statement.execute(
                    "INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES (42, '${bundle.identityHash}')",
                )
            }
            insertNotes(connection)
            insertScanSessions(connection)
            insertSnapshots(connection)
        }
        return file
    }

    private fun insertNotes(connection: Connection) {
        connection.prepareStatement(
            "INSERT INTO demo_notes (id, title, body, category, updatedAtEpochMs, attachmentName) " +
                "VALUES (?, ?, ?, ?, ?, ?)",
        ).use { statement ->
            for (index in 1..2) {
                statement.setString(1, "note-$index")
                statement.setString(2, "Note $index")
                statement.setString(3, "Body of note $index")
                statement.setString(4, "DOCUMENT")
                statement.setLong(5, 1_760_000_000_000L + index)
                statement.setString(6, if (index == 1) "note-1.md" else null)
                statement.executeUpdate()
            }
        }
    }

    private fun insertScanSessions(connection: Connection) {
        connection.prepareStatement(
            "INSERT INTO scan_sessions (startedAtEpochMs, durationMs, footprintBytes, resourceCount, " +
                "completeness, digest, trigger, providerCount) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        ).use { statement ->
            statement.setLong(1, 1_760_000_000_000L)
            statement.setLong(2, 42)
            statement.setLong(3, 4096)
            statement.setLong(4, 7)
            statement.setString(5, "COMPLETE")
            statement.setString(6, "digest-1")
            statement.setString(7, "test")
            statement.setInt(8, 3)
            statement.executeUpdate()
        }
    }

    private fun insertSnapshots(connection: Connection) {
        connection.prepareStatement(
            "INSERT INTO history_snapshots (capturedAtEpochMs, footprintBytes, resourceCount, " +
                "completenessLabel, payload) VALUES (?, ?, ?, ?, ?)",
        ).use { statement ->
            statement.setLong(1, 1_760_000_000_000L)
            statement.setLong(2, 4096)
            statement.setLong(3, 7)
            statement.setString(4, "COMPLETE")
            statement.setString(5, "storageanalyzer.snapshot/1\n")
            statement.executeUpdate()
        }
    }

    @Test
    fun `the exported schema matches what Room generated for the showcase`() {
        val bundle = bundle()

        assertEquals(1, bundle.version)
        assertNotNull(bundle.identityHash)
        assertEquals(
            listOf("demo_notes", "scan_sessions", "history_snapshots"),
            bundle.entities.map { it.tableName },
        )
    }

    @Test
    fun `Room analyzer reads entities, counts and a clean drift from the exported schema`() {
        val bundle = bundle()
        val database = createDatabaseFromSchema(bundle)

        RoomDatabaseAnalyzer(SQLiteDatabaseSpec(database.canonicalPath), bundle).use { analyzer ->
            val analysis = analyzer.analyzeOnce()

            assertEquals(1, analysis.liveSchemaVersion)
            assertEquals(bundle.identityHash, analysis.identityHashLive)
            assertEquals(bundle.identityHash, analysis.identityHashBundle)
            assertTrue(analysis.drift.isEmpty(), analysis.drift.toString())

            assertEquals(3, analysis.entities.size)
            val notes = assertNotNull(analysis.entity("room.demo_notes"))
            assertEquals(RoomEntitySource.SCHEMA_BUNDLE, notes.source)
            assertEquals(2L, notes.recordCount)
            assertNotNull(notes.tableBytes)
            assertTrue(notes.tableBytes!!.value > 0)

            assertEquals(1L, analysis.entity("room.scan_sessions")?.recordCount)
            assertEquals(1L, analysis.entity("room.history_snapshots")?.recordCount)
            assertTrue(analysis.totals.uniqueFootprint.value > 0)
        }
    }

    @Test
    fun `SQLite provider decomposes the same database into table views`() {
        val database = createDatabaseFromSchema(bundle())
        val provider = SQLiteStorageProvider(listOf(SQLiteDatabaseSpec(database.canonicalPath)))
        try {
            val ctx = AnalysisContext.create()
            val resources = provider.discoverResources(null, ctx).toList()

            assertTrue(resources.any { it.kind == ResourceKind.DATABASE })
            val tables = resources.filter { it.kind == ResourceKind.TABLE }
            assertTrue(
                tables.mapNotNull { it.label }.containsAll(
                    listOf("demo_notes", "scan_sessions", "history_snapshots"),
                ),
                "discovered tables: ${tables.map { it.label }}",
            )
            assertTrue(tables.all { it.decomposes != null })

            val capabilities = provider.capabilitiesFor(provider.systems().single())
            if (capabilities.supports(StorageCapability.DB_DBSTAT)) {
                val notesTable = tables.single { it.label == "demo_notes" }
                val measurement = provider.measure(
                    notesTable.identity,
                    setOf(MetricType.DATABASE_TABLE_STORAGE),
                    ctx,
                )
                val tableStorage = measurement.measurements.getValue(MetricType.DATABASE_TABLE_STORAGE)
                assertEquals(MeasurementState.MEASURED, tableStorage.state)
                assertTrue(tableStorage.value!!.value > 0)
            }
        } finally {
            provider.close()
        }
    }
}
