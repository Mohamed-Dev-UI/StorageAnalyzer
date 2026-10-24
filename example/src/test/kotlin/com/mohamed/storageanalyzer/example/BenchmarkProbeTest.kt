package com.mohamed.storageanalyzer.example

import com.mohamed.storageanalyzer.core.config.AnalysisContext
import com.mohamed.storageanalyzer.core.entity.EntityInstanceKey
import com.mohamed.storageanalyzer.core.measurement.ByteCount
import com.mohamed.storageanalyzer.core.measurement.MetricType
import com.mohamed.storageanalyzer.core.snapshot.StorageSnapshot
import com.mohamed.storageanalyzer.filesystem.FilesystemProvider
import com.mohamed.storageanalyzer.history.GrowthModel
import com.mohamed.storageanalyzer.history.GrowthModelFactory
import com.mohamed.storageanalyzer.history.SnapshotComparison
import com.mohamed.storageanalyzer.sqlite.SQLiteDatabaseSpec
import com.mohamed.storageanalyzer.sqlite.SQLiteStorageProvider
import com.mohamed.storageanalyzer.sqlite.entityDescriptorForTable
import java.sql.DriverManager
import java.nio.file.Files
import java.time.Instant
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * Manual benchmark probe for docs/PERFORMANCE.md.
 *
 * Runs only when the JVM system property `withBenchmarks` is `true`
 * (`./gradlew :example:test -PwithBenchmarks=true`); otherwise the
 * assumptions skip it, keeping the default suite bounded. Absolute numbers
 * are environment-dependent — the release owner re-runs this probe on
 * hardware they document alongside the numbers.
 */
@RunWith(JUnit4::class)
class BenchmarkProbeTest {

    private fun benchEnabled() {
        val prop = System.getProperty("withBenchmarks")
        Assume.assumeTrue(
            "run benchmarks with ./gradlew :example:test -PwithBenchmarks=true (got prop=<$prop>)",
            prop == "true",
        )
    }

    private fun bench(name: String, block: () -> Unit) {
        benchEnabled()
        block() // warm-up pass (excluded from reporting)
        val durations = (1..5).map {
            val start = System.nanoTime()
            block()
            (System.nanoTime() - start) / 1_000_000.0
        }
        val sorted = durations.sorted()
        println(
            "[bench] $name" +
                " medianMs=" + "%.1f".format(sorted[sorted.size / 2]) +
                " minMs=" + "%.1f".format(sorted.first()) +
                " maxMs=" + "%.1f".format(sorted.last()),
        )
    }

    // ---------------------------------------------------------- fixtures

    private fun buildFiletree(root: java.nio.file.Path): Int {
        Files.createDirectories(root.resolve("nested/deeper"))
        repeat(400) { Files.write(root.resolve("f%03d.dat".format(it)), ByteArray(512) { c -> (c % 97).toByte() }) }
        repeat(300) { Files.write(root.resolve("nested/g%03d.log".format(it)), ByteArray(256)) }
        repeat(100) { Files.write(root.resolve("nested/deeper/h%03d.md".format(it)), ByteArray(64)) }
        return 800
    }

    private fun buildSqlite(db: java.nio.file.Path) {
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite:" + db.toFile().canonicalPath).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT NOT NULL, email TEXT NOT NULL, note TEXT)")
                statement.execute("CREATE INDEX idx_users_email ON users(email)")
                statement.execute("CREATE TABLE events (id INTEGER PRIMARY KEY, kind TEXT NOT NULL, payload TEXT)")
                statement.execute("CREATE INDEX idx_events_kind ON events(kind)")
            }
            val users = connection.prepareStatement("INSERT INTO users(name, email, note) VALUES (?, ?, ?)")
            repeat(1000) { i ->
                users.setString(1, "user %05d".format(i))
                users.setString(2, "user%05d@example.test".format(i))
                users.setString(3, if (i % 3 == 0) "has note content" else null)
                users.executeUpdate()
            }
            users.close()
            val events = connection.prepareStatement("INSERT INTO events(kind, payload) VALUES (?, ?)")
            repeat(4000) { i ->
                events.setString(1, "kind%02d".format(i % 12))
                events.setString(2, "payload-$i-" + "x".repeat((i % 40) + 8))
                events.executeUpdate()
            }
            events.close()
        }
    }

    // ---------------------------------------------------------- scenarios

    @Test
    fun filesystemScan() {
        benchEnabled()
        val root = Files.createTempDirectory("sa-bench-fs")
        val fileCount = buildFiletree(root)
        val p = FilesystemProvider(listOf(root.toString()), allocationUnitResolver = { 4096L })
        val ctx = AnalysisContext.create()
        bench("filesystem.discover_$fileCount") {
            val n = p.discoverResources(null, ctx).count()
            check(n == fileCount + 3) { "expected 803 directories+files, got $n" } // +3 dirs incl. root
        }
        bench("filesystem.full_scan_$fileCount") {
            val resources = p.discoverResources(null, ctx).toList()
            for (r in resources) p.measure(r.identity, emptySet(), ctx)
        }
        p.close()
        root.toFile().walkBottomUp().forEach { it.delete() }
    }

    @Test
    fun sqliteSchemaAndRecords() {
        benchEnabled()
        val db = Files.createTempDirectory("sa-bench-sql").resolve("bench.sqlite")
        buildSqlite(db)
        val p = SQLiteStorageProvider(listOf(SQLiteDatabaseSpec(db.toFile().canonicalPath)))
        val ctx = AnalysisContext.create()

        bench("sqlite.schema_discover_and_measure") {
            val resources = p.discoverResources(null, ctx).toList()
            check(resources.isNotEmpty())
            for (r in resources) p.measure(r.identity, emptySet(), ctx)
        }

        val systemId = p.systems().single()
        val users = p.entityDescriptorForTable(
            entityTypeId = "bench.users",
            systemId = systemId,
            tableName = "users",
        )
        if (users != null) {
            val resolver = p.entityResolver()!!
            bench("sqlite.record_level_1000_rows") {
                var measured = 0
                for (r in resolver.resourcesFor(users, ctx)) {
                    val m = p.measure(r.identity, setOf(MetricType.DATABASE_RECORD_SIZE), ctx)
                    check(m.valueOf(MetricType.DATABASE_RECORD_SIZE) != null)
                    measured++
                    if (measured >= 1000) break
                }
                check(measured == 1000)
            }
        } else {
            println("[bench] sqlite.record_level_1000_rows skipped (descriptor resolution unavailable)")
        }

        p.close()
        db.toFile().delete()
    }

    @Test
    fun historyCompareAndForecast() {
        benchEnabled()
        // 5,000-resource detail maps per snapshot — compare + attribution cost
        val details = (1..5000).associate { "C:/some/very/long/path/file$it.bin" to (it % 700 + 1).toLong() }
        val s1 = snap(1000L, details)
        val s2 = snap(1600L, details.entries.associate { (k, v) -> k to v + 3 })
        bench("history.compare_5000_details") {
            val cmp = SnapshotComparison.between(s1, s2)
            check(cmp.attribution!!.changed.size == 5000)
        }
        val base = 1_000_000_000L
        val series = (0..59).map { snap(1000L + it * 77L, emptyMap(), at = Instant.ofEpochSecond(base + it * 3600L)) }
        bench("history.forecast_60_points") {
            val model = GrowthModelFactory.fit(series)
            check(model is GrowthModel.Linear)
        }
    }

    // ---------------------------------------------------------- helpers

    private fun snap(
        footprint: Long,
        resources: Map<String, Long>,
        at: Instant = Instant.now(),
    ): StorageSnapshot = StorageSnapshot(
        capturedAt = at,
        footprintMetric = MetricType.STORED_CONTENT_SIZE,
        footprintTotal = ByteCount(footprint),
        byMetric = emptyMap(),
        byProvider = emptyMap(),
        byCategory = emptyMap(),
        byResource = resources.mapValues { ByteCount(it.value) },
        resourceCount = resources.size.toLong(),
        completenessLabel = "COMPLETE",
    )
}
