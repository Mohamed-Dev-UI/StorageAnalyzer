package com.mohamed.storageanalyzer.example

import com.mohamed.storageanalyzer.core.StorageAnalyzer
import com.mohamed.storageanalyzer.core.measurement.MetricType
import com.mohamed.storageanalyzer.core.measurement.StorageUnits
import com.mohamed.storageanalyzer.core.result.GlobalReport
import com.mohamed.storageanalyzer.core.snapshot.StorageSnapshot
import com.mohamed.storageanalyzer.filesystem.FilesystemProvider
import com.mohamed.storageanalyzer.history.FileSnapshotStore
import com.mohamed.storageanalyzer.history.GrowthModelFactory
import com.mohamed.storageanalyzer.history.SnapshotComparison
import com.mohamed.storageanalyzer.sqlite.SQLiteDatabaseSpec
import com.mohamed.storageanalyzer.sqlite.SQLiteStorageProvider
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager

/**
 * A runnable end-to-end tour of the library on plain JVM:
 *
 * 1. builds a temporary filesystem tree plus a small SQLite database,
 * 2. wires the filesystem + SQLite providers into [StorageAnalyzer] and prints
 *    the deduplicated global report (the `.sqlite` container is measured by
 *    the filesystem provider and collapsed by the SQLite alias),
 * 3. takes two snapshots around a modelled "app growth" event and prints the
 *    honest comparison plus a linear growth projection,
 * 4. demonstrates the Room integration (`:room`, scenario K of docs/USAGE.md):
 *    the exported schema bundle drives per-entity analysis + drift diagnostics,
 * 5. demonstrates the reactive layer (`:reactive`, scenario L): manual policy,
 *    explicit refreshes, and digest-stable emission suppression.
 *
 * Run with: `./gradlew :example:run`
 * (or `./gradlew :example:installDist && ./example/build/install/example/bin/example`).
 */
object StorageAnalyzerDemo {

    @JvmStatic
    fun main(args: Array<String>) {
        val workspace = Files.createTempDirectory("sa-demo")
        println("Storage Analyzer demo — workspace: ${workspace.toFile().canonicalPath}\n")

        // ---------------------------------------------------------------- fixture
        val tree = buildFileTree(workspace)
        buildSqliteFixture(tree.databaseFile)

        // ---------------------------------------------------------------- wiring
        val analyzer = StorageAnalyzer(
            providers = listOf(
                // The filesystem provider comes first: it owns the physical-file
                // identity scheme, so the database container is measured once.
                FilesystemProvider(
                    roots = listOf(tree.root.toString()),
                    allocationUnitResolver = { store -> if (store.name().contains("NTFS")) 4096L else 4096L },
                ),
                SQLiteStorageProvider(listOf(SQLiteDatabaseSpec(tree.databaseFile.toString()))),
            ),
        )

        // ---------------------------------------------------------------- run 1
        val report1 = analyzer.analyzeGlobal()
        printReport("Run 1 (before growth)", report1)

        val history = FileSnapshotStore(workspace.resolve("history"))
        val snapshot1 = analyzer.captureSnapshot()
        history.append(snapshot1)

        // ---------------------------------------------------------------- growth
        modelAppGrowth(tree)

        val report2 = analyzer.analyzeGlobal()
        printReport("Run 2 (after growth)", report2)

        val snapshot2 = analyzer.captureSnapshot()
        history.append(snapshot2)

        // ---------------------------------------------------------------- history
        printHistory(snapshot1, snapshot2, modelHorizon = 30L * 24L * 3600L)

        // ---------------------------------------------------------------- room (scenario K)
        // The exported schema bundle drives entity descriptors over the SQLite
        // provider — no androidx dependency, drift reported as diagnostics.
        val (roomDb, roomBundle) = UsageExamples.buildRoomFixture(workspace.resolve("room-demo"))
        println("== room (scenario K)")
        println(UsageExamples.roomOverview(roomDb.toFile().canonicalPath, roomBundle).prependIndent("   "))

        // ---------------------------------------------------------------- reactive (scenario L)
        // Manual policy, explicit refreshes: identical content → same digest,
        // which is what keeps a live UI from re-rendering unchanged data.
        val reactiveRoot = workspace.resolve("reactive-demo")
        UsageExamples.buildFixture(reactiveRoot)
        println("== reactive (scenario L)")
        println(UsageExamples.reactiveManualSummary(reactiveRoot).prependIndent("   "))

        analyzer.close()
        history.close()
        val inspectNote =
            "demo finished — the workspace is kept on disk so results are observable:\n" +
                tree.root.toFile().canonicalPath + "\n" +
                "(there is no data-cleanup; deleting it is a normal user operation)"
        println("\n" + inspectNote)
    }

    // ------------------------------------------------------------ fixture

    private class DemoTree(
        val root: Path,
        val databaseFile: Path,
    )

    private fun buildFileTree(workspace: Path): DemoTree {
        val journalRows = "INFO app booted\n".repeat(60) // ~ 960 B
        Files.createDirectories(workspace.resolve("logs"))
        Files.writeString(workspace.resolve("logs/app.log"), journalRows)

        val releaseNotes = "# Release notes\n\nThis repository maintains honest storage analytics.\n".repeat(12)
        Files.createDirectories(workspace.resolve("notes"))
        Files.writeString(workspace.resolve("notes/release.md"), releaseNotes)

        // a "photo": arbitrary binary payload, category MEDIA by extension
        val pixels = ByteArray(6000) { index -> (index % 251).toByte() }
        Files.createDirectories(workspace.resolve("images"))
        Files.write(workspace.resolve("images/photo.png"), pixels)

        val databaseFile = workspace.resolve("db/app-data.sqlite")
        Files.createDirectories(databaseFile.parent)
        return DemoTree(workspace, databaseFile)
    }

    private fun buildSqliteFixture(databaseFile: Path) {
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite:" + databaseFile.toFile().canonicalPath).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    "CREATE TABLE users (" +
                        "id INTEGER PRIMARY KEY, name TEXT NOT NULL, email TEXT NOT NULL, country TEXT NOT NULL)",
                )
                statement.execute("CREATE INDEX idx_users_email ON users(email)")
                val insert = connection.prepareStatement("INSERT INTO users(name, email, country) VALUES (?, ?, ?)")
                repeat(200) { i ->
                    insert.setString(1, "user %04d".format(i))
                    insert.setString(2, "user%04d@example.test".format(i))
                    insert.setString(3, if (i % 2 == 0) "DE" else "US")
                    insert.executeUpdate()
                }
                insert.close()
            }
        }
    }

    /** Simulates an app that produced logs and database rows in the meantime. */
    private fun modelAppGrowth(tree: DemoTree) {
        Files.writeString(
            tree.root.resolve("logs/app.log"),
            "INFO background job processed records\n".repeat(120),
            java.nio.file.StandardOpenOption.APPEND,
        )
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite:" + tree.databaseFile.toFile().canonicalPath).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("INSERT INTO users(name, email, country) VALUES ('conf-user', 'conf@example.test', 'DE')")
            }
            val insert = connection.prepareStatement("INSERT INTO users(name, email, country) VALUES (?, ?, ?)")
            repeat(200) { i ->
                insert.setString(1, "grown %04d".format(i))
                insert.setString(2, "grown%04d@example.test".format(i))
                insert.setString(3, "FR")
                insert.executeUpdate()
            }
        }
    }

    // ------------------------------------------------------------ printing

    private fun printReport(title: String, report: GlobalReport) {
        println("== $title")
        println("   completeness : ${report.completeness} of ${report.resourceCount} unique resources")
        println(
            "   unique footprint : " + StorageUnits.formatIec(report.totals.uniqueFootprint) +
                " (policy: " + report.accountingPolicy.policyId + ")",
        )
        report.byProvider.forEach { breakdown ->
            val bytes = breakdown[MetricType.STORED_CONTENT_SIZE]
            println("   by provider    : " + breakdown.key + " -> " + (bytes?.let { StorageUnits.formatIec(it) } ?: "<none>"))
        }
        report.byCategory.forEach { breakdown ->
            val bytes = breakdown[MetricType.STORED_CONTENT_SIZE]
            println("   by category    : " + breakdown.key + " -> " + (bytes?.let { StorageUnits.formatIec(it) } ?: "<none>"))
        }
        println("   overlaps (deduplicated entries) : " + report.overlapMeasurements.size)
        report.unmeasuredResources.take(5).forEach {
            println("   unmeasured      : [" + it.identity.stableKey + "] " + it.reason)
        }
        println("   diagnostics : " + report.diagnostics.size + " entries")
        println()
    }

    private fun printHistory(snapshot1: StorageSnapshot, snapshot2: StorageSnapshot, modelHorizon: Long) {
        println("== history")
        val comparison = SnapshotComparison.between(snapshot1, snapshot2)
        println(
            "   footprint growth : " + (comparison.footprintDeltaBytes.let { d ->
                (if (d >= 0) "+" else "") + d + " bytes"
            }) + " from " + comparison.fromCapturedAt + " to " + comparison.toCapturedAt,
        )
        comparison.metricDeltas.forEach { (metric, delta) ->
            println(
                "   delta " + metric.id.padEnd(24) +
                    " " + (if (delta.deltaBytes >= 0) "+" else "") + delta.deltaBytes + " B",
            )
        }
        comparison.notComparableMetrics.forEach { (metric, reason) ->
            println("   not comparable " + metric.id + " -> " + reason)
        }
        comparison.attribution?.let { attr ->
            println("   created resources : " + attr.created.keys.size + ", removed: " + attr.removed.keys.size + ", changed: " + attr.changed.size)
            attr.topChanges().take(3).forEach { (key, bytes) -> println("     changed " + key + ": " + (if (bytes >= 0) "+" else "") + bytes + " B") }
        } ?: println("   attribution : unavailable (resource details missing)")

        val model = GrowthModelFactory.fit(listOf(snapshot1, snapshot2))
        when (model) {
            is com.mohamed.storageanalyzer.history.GrowthModel.Linear -> {
                println("   growth model    : slope " + model.slopeBytesPerSecond + " bytes/s, r2 " + model.r2)
                val horizon = snapshot2.capturedAt.plusSeconds(modelHorizon)
                println("   projection      : ~" + model.estimateAt(horizon) + " bytes at " + horizon)
            }
            is com.mohamed.storageanalyzer.history.GrowthModel.Unavailable ->
                println("   growth model    : unavailable —" + model.reason)
        }
        println()
    }
}
