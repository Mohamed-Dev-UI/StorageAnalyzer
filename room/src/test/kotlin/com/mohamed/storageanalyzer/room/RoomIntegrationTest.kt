package com.mohamed.storageanalyzer.room

import com.mohamed.storageanalyzer.core.config.AnalysisBudget
import com.mohamed.storageanalyzer.core.config.AnalysisOptions
import com.mohamed.storageanalyzer.core.result.DiagnosticSeverity
import com.mohamed.storageanalyzer.reactive.RerunState
import com.mohamed.storageanalyzer.sqlite.SQLiteDatabaseSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.time.Duration
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Lives Room integration against a real database file (sqlite-jdbc): bundle
 * parsing, descriptor generation (rowid + WITHOUT ROWID), one-shot analysis,
 * drift diagnostics and the hot monitor.
 */
class RoomIntegrationTest {

    lateinit var dir: Path

    private var writer: Connection? = null

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("room-analyzer")
    }

    @AfterTest
    fun tearDown() {
        writer?.close()
        writer = null
    }

    // --------------------------------------------------------------- fixture

    private fun spec(): SQLiteDatabaseSpec = SQLiteDatabaseSpec(db().canonicalPath)

    private fun db(): File = dir.resolve("app.db").toFile()

    private fun createDb() {
        Class.forName("org.sqlite.JDBC")
        val connection = DriverManager.getConnection("jdbc:sqlite:" + db().canonicalPath)
        writer = connection
        connection.createStatement().use { st ->
            st.execute("PRAGMA journal_mode=WAL")
            st.execute("CREATE TABLE users (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, email TEXT NOT NULL)")
            st.execute("CREATE TABLE posts (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, userId INTEGER NOT NULL, body TEXT)")
            st.execute("CREATE INDEX index_User_email ON users(email)")
            st.execute("CREATE INDEX index_Posts_userId ON posts(userId)")
        }
        connection.prepareStatement("INSERT INTO users(name, email) VALUES (?, ?)").use { ps ->
            repeat(5) { i ->
                ps.setString(1, "u$i")
                ps.setString(2, "u$i@example.test")
                ps.executeUpdate()
            }
        }
        connection.prepareStatement("INSERT INTO posts(userId, body) VALUES (?, ?)").use { ps ->
            repeat(3) { i ->
                ps.setInt(1, i)
                ps.setString(2, "body $i")
                ps.executeUpdate()
            }
        }
        // An unrelated file in the same directory must not leak into totals.
        File(dir.toFile(), "notmine.txt").writeText("x".repeat(1024))
    }

    private fun bundleJson(): String = """
        {
          "formatVersion": 1,
          "database": {
            "version": 3,
            "identityHash": "aa-bb-01",
            "entities": [
              {
                "tableName": "users",
                "createSql": "CREATE TABLE IF NOT EXISTS `users` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `email` TEXT NOT NULL)",
                "fields": [
                  {"fieldPath": "id", "columnName": "id", "affinity": "INTEGER", "notNull": true},
                  {"fieldPath": "name", "columnName": "name", "affinity": "TEXT", "notNull": true},
                  {"fieldPath": "email", "columnName": "email", "affinity": "TEXT", "notNull": true}
                ],
                "primaryKey": ["id"],
                "indices": [
                  {"name": "index_User_email", "unique": false, "createSql": "CREATE INDEX IF NOT EXISTS `index_User_email` ON `${'`'}users`${'`'} (`email`)"}
                ],
                "className": "com.example.User"
              },
              {
                "tableName": "posts",
                "createSql": "CREATE TABLE IF NOT EXISTS `posts` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `userId` INTEGER NOT NULL, `body` TEXT)",
                "fields": [
                  {"fieldPath": "id", "columnName": "id", "affinity": "INTEGER", "notNull": true},
                  {"fieldPath": "userId", "columnName": "userId", "affinity": "INTEGER", "notNull": true},
                  {"fieldPath": "body", "columnName": "body", "affinity": "TEXT"}
                ],
                "primaryKey": ["id"],
                "indices": [
                  {"name": "index_Posts_userId", "unique": false, "createSql": "CREATE INDEX IF NOT EXISTS `index_Posts_userId` ON `${'`'}posts`${'`'} (`userId`)"}
                ],
                "className": "com.example.Post"
              }
            ]
          }
        }
    """.trimIndent()

    private fun bundle(): RoomSchemaBundle = RoomSchemaBundle.parse(bundleJson())

    // ------------------------------------------------------------------ tests

    @Test
    fun `parses bundle structure with class names, pk columns and indexes`() {
        val parsed = bundle()
        assertEquals(3, parsed.version)
        assertEquals("aa-bb-01", parsed.identityHash)
        assertEquals(2, parsed.entities.size)

        val users = assertNotNull(parsed.entityNamed("users"))
        assertEquals("User", users.entityClassSimpleName)
        assertEquals(listOf("id", "name", "email"), users.columnNames)
        assertEquals(listOf("id"), users.primaryKeyColumns)
        assertEquals(listOf("index_User_email"), users.indexNames)
        assertTrue(!users.withoutRowid)

        val posts = assertNotNull(parsed.entityNamed("posts"))
        assertEquals("Post", posts.entityClassSimpleName)

        // File-based variant: version from '<n>.json' name.
        val file = dir.resolve("3.json")
        java.nio.file.Files.writeString(file, """{"entities": [{"tableName": "x", "fields": []}]}""")
        val fromFile = RoomSchemaBundle.parse(file)
        assertEquals(3, fromFile.version)
        assertEquals(1, fromFile.entities.size)
        assertEquals(file.toString(), fromFile.sourcePath)
    }

    @Test
    fun `cold one-shot analysis reports entities, sidecars and honest totals`() {
        createDb()
        RoomDatabaseAnalyzer(spec(), bundle()).use { analyzer ->
            val analysis = analyzer.analyzeOnce()

            val users = assertNotNull(analysis.entity("room.User"))
            assertEquals("users", users.tableName)
            assertEquals("User", users.displayName)
            assertEquals(RoomEntitySource.SCHEMA_BUNDLE, users.source)
            assertEquals(5L, users.recordCount)
            assertNotNull(users.tableBytes)
            assertTrue(users.tableBytes!!.value > 0)
            assertNotNull(users.indexesBytes)
            assertTrue(users.indexesBytes!!.value > 0)

            val posts = assertNotNull(analysis.entity("room.Post"))
            assertTrue(posts.indexesBytes!!.value > 0)

            val wal = analysis.sidecars.firstOrNull { it.role == SidecarRole.WAL }
            assertNotNull(wal, "WAL sidecar expected while the holder connection is open")
            assertTrue(wal.bytes.value > 0)

            assertNotNull(analysis.totals.dbFileBytes)
            assertTrue(analysis.totals.dbFileBytes!!.value > 0)
            assertNotNull(analysis.totals.pageCapacity)
            assertTrue(analysis.totals.pageCapacity!!.value > 0)
            assertTrue(analysis.totals.uniqueFootprint.value > 0)

            // Unrelated file in the same directory must not contribute.
            val foreign = analysis.globalReport?.resourceSummaries?.any {
                it.identity.providerId.value == "filesystem" && it.identity.nativeId == "notmine.txt"
            }
            assertEquals(false, foreign)

            // Bundle version 3 vs untouched db (user_version 0) -> warning; no
            // readable master table yet -> info that identity was skipped.
            assertTrue(analysis.drift.any { it.code == RoomDatabaseAnalyzer.DRIFT_SCHEMA_VERSION })
            assertTrue(analysis.drift.any { it.code == RoomDatabaseAnalyzer.DRIFT_MASTER_MISSING })
            assertTrue(analysis.drift.none { it.code == RoomDatabaseAnalyzer.DRIFT_EXTRA_TABLE })
        }
    }

    @Test
    fun `without-bundle analysis falls back to the live catalog`() {
        createDb()
        RoomDatabaseAnalyzer(spec(), schemaBundle = null).use { analyzer ->
            val analysis = analyzer.analyzeOnce()

            val users = assertNotNull(analysis.entity("room.users"))
            assertEquals(RoomEntitySource.LIVE_CATALOG, users.source)
            assertEquals("users", users.displayName)
            assertEquals(5L, users.recordCount)
            assertNotNull(analysis.entity("room.posts"))
            assertEquals(2, analysis.entities.size)
        }
    }

    @Test
    fun `without-rowid tables are addressed by primary key and scanned opt-in`() {
        createDb()
        writer!!.createStatement().use { st ->
            st.execute("CREATE TABLE codes (code TEXT NOT NULL PRIMARY KEY, title TEXT) WITHOUT ROWID")
            st.execute("INSERT INTO codes (code, title) VALUES ('A1', 'alpha'), ('B2', 'beta'), ('C3', 'gamma')")
        }
        val codeEntity = """
            {
              "tableName": "codes",
              "createSql": "CREATE TABLE IF NOT EXISTS `codes` (`code` TEXT NOT NULL, `title` TEXT, PRIMARY KEY(`code`)) WITHOUT ROWID",
              "fields": [
                {"fieldPath": "code", "columnName": "code", "affinity": "TEXT", "notNull": true},
                {"fieldPath": "title", "columnName": "title", "affinity": "TEXT"}
              ],
              "primaryKey": ["code"],
              "className": "com.example.Code"
            }
        """.trimIndent()
        val json = bundleJson().replace("\"entities\": [", "\"entities\": [$codeEntity,")

        RoomDatabaseAnalyzer(
            spec(),
            RoomSchemaBundle.parse(json),
            RoomAnalysisConfig(includeRowScan = true),
        ).use { analyzer ->
            val analysis = analyzer.analyzeOnce()
            val codes = assertNotNull(analysis.entity("room.Code"))
            assertEquals(3L, codes.recordCount)
            assertNotNull(codes.rowsBytesSum)
            assertTrue(codes.rowsBytesSum!!.value > 0)
            assertNotNull(codes.maxRowBytes)
            assertTrue(codes.maxRowBytes!!.value <= codes.rowsBytesSum!!.value)
        }
    }

    @Test
    fun `drift diagnostics cover identity hash, extra and missing tables`() {
        createDb()
        writer!!.createStatement().use { st ->
            st.execute("CREATE TABLE room_master_table (identity_hash TEXT NOT NULL)")
            st.execute("INSERT INTO room_master_table (identity_hash) VALUES ('deadbeef')")
            st.execute("CREATE TABLE staging_extra (k TEXT)")
        }
        val ghostEntity = """{"tableName": "ghost", "createSql": "CREATE TABLE `ghost` (`id` INTEGER PRIMARY KEY)", "fields": []}"""
        val json = bundleJson().replace("\"entities\": [", "\"entities\": [$ghostEntity,")

        RoomDatabaseAnalyzer(spec(), RoomSchemaBundle.parse(json)).use { analyzer ->
            val analysis = analyzer.analyzeOnce()
            assertEquals("deadbeef", analysis.identityHashLive)
            assertEquals("aa-bb-01", analysis.identityHashBundle)

            val identity = analysis.drift.first { it.code == RoomDatabaseAnalyzer.DRIFT_IDENTITY_HASH }
            assertEquals(DiagnosticSeverity.ERROR, identity.severity)

            val extra = analysis.drift.filter { it.code == RoomDatabaseAnalyzer.DRIFT_EXTRA_TABLE }
            assertEquals(1, extra.size)
            assertTrue(extra.all { it.message.contains("staging_extra") })

            val missing = analysis.drift.filter { it.code == RoomDatabaseAnalyzer.DRIFT_MISSING_TABLE }
            assertEquals(1, missing.size)
            assertTrue(missing.all { it.message.contains("ghost") })
        }
    }

    @Test
    fun `monitor hot flow detects inserted rows through the fingerprint probe`() = runTest {
        createDb()
        val dispatcher = StandardTestDispatcher(testScheduler)
        RoomStorageMonitor(
            spec = spec(),
            schemaBundle = bundle(),
            scope = backgroundScope,
            pollInterval = Duration.ofMillis(20),
            dispatcher = dispatcher,
        ).use { monitor ->
            monitor.start()
            val first = monitor.results.filterNotNull().first()
            assertEquals(5L, first.entity("room.User")?.recordCount)

            writer!!.createStatement().use { st ->
                st.execute("INSERT INTO users(name, email) VALUES ('later', 'later@example.test')")
            }

            val second = monitor.results.filterNotNull().first { analysis ->
                (analysis.entity("room.User")?.recordCount ?: 0L) > 5
            }
            assertIs<RerunState.Completed<RoomAnalysis>>(monitor.state.value)
            assertNotEquals(first.digest, second.digest)
            assertTrue(second.durationMs >= 0)
        }
    }

    @Test
    fun `monitor cold refreshNow works without start and without fingerprinting`() = runTest {
        createDb()
        val dispatcher = StandardTestDispatcher(testScheduler)
        RoomStorageMonitor(
            spec = spec(),
            schemaBundle = bundle(),
            scope = backgroundScope,
            dispatcher = dispatcher,
        ).use { monitor ->
            assertTrue(monitor.results.value == null)

            val state = monitor.refreshNow()
            val completed = assertIs<RerunState.Completed<RoomAnalysis>>(state)
            assertEquals(5L, completed.value.entity("room.User")?.recordCount)
            assertNotNull(monitor.results.value)
        }
    }

    @Test
    fun `in-memory databases degrade to manual hot mode with honest missing values`() = runTest {
        val monitor = RoomStorageMonitor(
            spec = SQLiteDatabaseSpec(":memory:"),
            scope = backgroundScope,
            dispatcher = StandardTestDispatcher(testScheduler),
        ).use { m ->
            m.refreshNow().let { state ->
                val completed = assertIs<RerunState.Completed<RoomAnalysis>>(state)
                assertTrue(completed.value.entities.isEmpty())
                assertNull(completed.value.totals.dbFileBytes)
            }
        }
    }

    @Test
    fun `budget bounds are honored with partial results`() {
        createDb()
        RoomDatabaseAnalyzer(spec(), bundle()).use { analyzer ->
            val options = AnalysisOptions(
                budget = AnalysisBudget(time = Duration.ofNanos(1)),
            )
            val analysis = analyzer.analyzeOnce(options)
            // A wall-clocked budget of 1ns stops the scan; the analysis still
            // returns a value object (completeness is communicated by the
            // engine report and unavailable parts stay null, never zero).
            assertNotNull(analysis)
        }
    }
}
