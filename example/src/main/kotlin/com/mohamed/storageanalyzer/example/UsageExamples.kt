package com.mohamed.storageanalyzer.example

import com.mohamed.storageanalyzer.core.StorageAnalyzer
import com.mohamed.storageanalyzer.core.config.AnalysisBudget
import com.mohamed.storageanalyzer.core.config.AnalysisContext
import com.mohamed.storageanalyzer.core.config.AnalysisOptions
import com.mohamed.storageanalyzer.core.entity.EntityInstanceKey
import com.mohamed.storageanalyzer.core.entity.spi.CollectionKey
import com.mohamed.storageanalyzer.core.identity.ProviderId
import com.mohamed.storageanalyzer.core.identity.ResourceKind
import com.mohamed.storageanalyzer.core.measurement.MeasurementAccuracy
import com.mohamed.storageanalyzer.core.measurement.MeasurementMethod
import com.mohamed.storageanalyzer.core.measurement.MeasurementProvenance
import com.mohamed.storageanalyzer.core.measurement.MeasurementState
import com.mohamed.storageanalyzer.core.measurement.MetricType
import com.mohamed.storageanalyzer.core.measurement.StorageUnits
import com.mohamed.storageanalyzer.core.result.Completeness
import com.mohamed.storageanalyzer.filesystem.FilesystemProvider
import com.mohamed.storageanalyzer.history.FileSnapshotStore
import com.mohamed.storageanalyzer.history.GrowthModel
import com.mohamed.storageanalyzer.history.GrowthModelFactory
import com.mohamed.storageanalyzer.history.SnapshotComparison
import com.mohamed.storageanalyzer.reactive.ReactiveAnalyzer
import com.mohamed.storageanalyzer.reactive.RefreshPolicy
import com.mohamed.storageanalyzer.reactive.RerunState
import com.mohamed.storageanalyzer.room.RoomDatabaseAnalyzer
import com.mohamed.storageanalyzer.room.RoomSchemaBundle
import com.mohamed.storageanalyzer.sqlite.SQLiteDatabaseSpec
import com.mohamed.storageanalyzer.sqlite.SQLiteStorageProvider
import com.mohamed.storageanalyzer.sqlite.entityDescriptorForTable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Duration


/**
 * The scenarios documented in docs/USAGE.md, executable here so every doc
 * snippet stays verified (UsageExamplesTest runs them all on every build).
 */
object UsageExamples {

    // ------------------------------------------------------------ fixture

    /** Builds a small app-like tree: logs, notes, an image and a SQLite db. */
    fun buildFixture(root: Path): Path {
        Files.createDirectories(root.resolve("logs"))
        Files.writeString(root.resolve("logs/app.log"), "INFO boot\n".repeat(40))
        Files.createDirectories(root.resolve("notes"))
        Files.writeString(root.resolve("notes/readme.md"), "docs here\n".repeat(30))
        Files.createDirectories(root.resolve("images"))
        Files.write(root.resolve("images/icon.png"), ByteArray(4096) { i -> (i % 251).toByte() })

        val db = root.resolve("data/app.sqlite")
        Files.createDirectories(db.parent)
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite:" + db.toFile().canonicalPath).use { c ->
            c.createStatement().use { s ->
                s.execute("CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT NOT NULL, email TEXT NOT NULL, note TEXT)")
                s.execute("CREATE INDEX idx_users_email ON users(email)")
            }
            val ps = c.prepareStatement("INSERT INTO users(name, email, note) VALUES (?, ?, ?)")
            repeat(50) { i ->
                ps.setString(1, "u$i")
                ps.setString(2, "u$i@example.test")
                ps.setString(3, if (i % 5 == 0) "note-$i-" + "payload".repeat(4) else null)
                ps.executeUpdate()
            }
        }
        return db
    }

    /** Shared wiring used by many scenarios (fs provider FIRST = dedup owner). */
    fun analyzerFor(root: Path): StorageAnalyzer = StorageAnalyzer(
        providers = listOf(
            FilesystemProvider(
                roots = listOf(root.toString()),
                allocationUnitResolver = { 4096L },
            ),
            SQLiteStorageProvider(listOf(SQLiteDatabaseSpec(root.resolve("data/app.sqlite").toFile().canonicalPath))),
        ),
    )

    // -------------------------------------------------------- scenario A

    /** What is eating the disk? totals + category share + biggest files. */
    fun diskUsage(root: Path): String {
        val analyzer = StorageAnalyzer(
            providers = listOf(
                FilesystemProvider(
                    roots = listOf(root.toString()),
                    allocationUnitResolver = { 4096L },
                ),
            ),
        )
        return analyzer.use { a ->
            val report = a.analyzeGlobal()
            val headline = "unique footprint: " + StorageUnits.formatIec(report.totals.uniqueFootprint) +
                " (completeness=" + report.completeness + ", resources=" + report.resourceCount + ")"
            val categories = report.byCategory
                .sortedByDescending { it[MetricType.STORED_CONTENT_SIZE]?.value ?: 0L }
                .joinToString("\n") {
                    "  " + it.label.orEmpty().padEnd(12) +
                        (it[MetricType.STORED_CONTENT_SIZE]?.let { sz -> StorageUnits.formatIec(sz) } ?: "")
                }
            val biggest = report.resourceSummaries
                .mapNotNull { s ->
                    s.footPrintMetrics[MetricType.STORED_CONTENT_SIZE]?.let { sz -> s.identity.stableKey to sz }
                }
                .sortedByDescending { (_, sz) -> sz.value }
                .take(3)
                .joinToString("\n") { (key, sz) -> "  " + sz + " " + key }
            headline + "\nby category:\n" + categories + "\nbiggest:\n" + biggest
        }
    }

    /** Fast path: single metric, one provider — skips volume probing. */
    fun fastStoredSize(root: Path): Long {
        val analyzer = StorageAnalyzer(
            providers = listOf(FilesystemProvider(roots = listOf(root.toString()))),
        )
        return analyzer.use { a ->
            a.analyzeGlobal(AnalysisOptions(metrics = setOf(MetricType.STORED_CONTENT_SIZE)))
                .totals.byMetric[MetricType.STORED_CONTENT_SIZE]!!.value
        }
    }

    // -------------------------------------------------------- scenario B

    /** Inside one SQLite database: page capacity, freelist, table inventory. */
    fun sqliteSummary(dbPath: String): String {
        val analyzer = StorageAnalyzer(providers = listOf(SQLiteStorageProvider(listOf(SQLiteDatabaseSpec(dbPath)))))
        return analyzer.use { a ->
            val report = a.analyzeGlobal()
            val p = a.providers().single() as SQLiteStorageProvider
            val ctx = AnalysisContext.create()
            val tables = p.discoverResources(null, ctx)
                .filter { it.kind == ResourceKind.TABLE }
                .mapNotNull { it.label }
                .toList()
            "completeness=" + report.completeness +
                " pageCapacity=" + (report.totals.byMetric[MetricType.DATABASE_PAGE_CAPACITY]?.value ?: 0L) +
                " freelist=" + (report.totals.byMetric[MetricType.DATABASE_FREELIST_SIZE]?.value ?: 0L) +
                " tables=" + tables
        }
    }

    // -------------------------------------------------------- scenario C

    /** Per-row sizes streamed for one table, sorted by size (caller sorts). */
    fun heaviestRows(dbPath: String): List<Pair<String, Long>> {
        val p = SQLiteStorageProvider(listOf(SQLiteDatabaseSpec(dbPath)))
        val ctx = AnalysisContext.create()
        val users = p.entityDescriptorForTable("app.User", p.systems().single(), "users")!!
        val out = ArrayList<Pair<String, Long>>()
        p.entityResolver()!!.resourcesFor(users, ctx).forEach { r ->
            p.measure(r.identity, setOf(MetricType.DATABASE_RECORD_SIZE), ctx)
                .valueOf(MetricType.DATABASE_RECORD_SIZE)?.let { out += r.label.orEmpty() to it.value }
        }
        p.close()
        return out
    }

    /** Which column eats the table? whole-table per-column aggregates. */
    fun columnBreakdown(dbPath: String): Map<String, Long> {
        val p = SQLiteStorageProvider(listOf(SQLiteDatabaseSpec(dbPath)))
        val ctx = AnalysisContext.create()
        val table = p.discoverResources(null, ctx).first { it.kind == ResourceKind.TABLE }
        val aggregates = p.recordAnalytics()!!.aggregateFieldBreakdown(CollectionKey(table.identity), ctx)
        p.close()
        return aggregates.associate { it.attributeName to it.totalAcrossRecords.value }
    }

    // -------------------------------------------------------- scenario D

    /** Composite view: filesystem + sqlite without double counting the db file. */
    fun combinedNoDoubleCounting(root: Path): String {
        val analyzer = analyzerFor(root)
        val report = analyzer.use { it.analyzeGlobal() }
        return "uniqueFootprint=" + report.totals.uniqueFootprint.value +
            " overlapsCollapsed=" + report.overlapMeasurements.size +
            " providers=" + report.providers.size +
            " unmeasured=" + report.unmeasuredResources.size
    }

    // -------------------------------------------------------- scenario E

    /** Growth timeline: two snapshots around a change + comparison + fit. */
    fun growthTimeline(storeDir: Path): String {
        val store = FileSnapshotStore(storeDir)
        val analyzer = StorageAnalyzer(providers = listOf(FilesystemProvider(roots = listOf(storeDir.parent.toString()))))
        val first = analyzer.use { it.captureSnapshot() }
        store.append(first)
        Files.writeString(storeDir.parent.resolve("logs/app.log"), "INFO grew\n".repeat(20), java.nio.file.StandardOpenOption.APPEND)
        val second = analyzer.use { it.captureSnapshot() }
        store.append(second)

        val cmp = SnapshotComparison.between(first, second)
        val lines = ArrayList<String>()
        lines += "delta=" + (if (cmp.footprintDeltaBytes >= 0) "+" else "") + cmp.footprintDeltaBytes
        cmp.metricDeltas.forEach { (m, d) -> lines += m.id + " " + (if (d.deltaBytes >= 0) "+" else "") + d.deltaBytes }
        when (val model = GrowthModelFactory.fit(store.readAll())) {
            is GrowthModel.Linear -> lines += "slope B/s=" + model.slopeBytesPerSecond + " r2=" + model.r2
            is GrowthModel.Unavailable -> lines += "no-model: " + model.reason
        }
        return lines.joinToString("\n")
    }

    // -------------------------------------------------------- scenario F

    /** Entity-level accounting for one row via the facade. */
    fun entityAccounting(root: Path): String {
        val db = root.resolve("data/app.sqlite").toFile().canonicalPath
        val p = SQLiteStorageProvider(listOf(SQLiteDatabaseSpec(db)))
        val descriptor = p.entityDescriptorForTable("app.User", p.systems().single(), "users")
        val analyzer = StorageAnalyzer(providers = listOf(p))
        val report = analyzer.use {
            it.analyzeEntity(descriptor!!, EntityInstanceKey("42"))
        }
        return "recordSize=" + (report.direct[MetricType.DATABASE_RECORD_SIZE]?.value ?: -1L) +
            " exclusive=" + (report.exclusive[MetricType.DATABASE_RECORD_SIZE]?.value ?: -1L)
    }

    // -------------------------------------------------------- scenario G

    /** Bounded run: returns the completeness label + measured count. */
    fun boundedScan(root: Path): String {
        val analyzer = StorageAnalyzer(
            providers = listOf(FilesystemProvider(roots = listOf(root.toString()))),
        )
        return analyzer.use { a ->
            val report = a.analyzeGlobal(
                AnalysisOptions(
                    metrics = setOf(MetricType.STORED_CONTENT_SIZE),
                    budget = AnalysisBudget(maxResources = 2),
                ),
            )
            "completeness=" + report.completeness + " measured=" + report.resourceCount
        }
    }

    /** Cooperative cancellation from another thread. */
    fun cancelledScan(root: Path): String {
        val analyzer = StorageAnalyzer(
            providers = listOf(FilesystemProvider(roots = listOf(root.toString()))),
        )
        val ctx = AnalysisContext.create()
        ctx.cancel() // user pressed "stop" before the run started
        return try {
            analyzer.use { it.analyzeGlobal(AnalysisOptions.DEFAULT, ctx) }
            "no-cancel-signalled" // unreachable
        } catch (e: com.mohamed.storageanalyzer.core.result.StorageError.Cancelled) {
            "cancelled-honoured"
        }
    }

    // -------------------------------------------------------- scenario K

    /** Builds a Room-style app state: db + exported schema bundle JSON. */
    fun buildRoomFixture(root: Path): Pair<Path, Path> {
        val db = root.resolve("app/room.db")
        Files.createDirectories(db.parent)
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite:" + db.toFile().canonicalPath).use { c ->
            c.createStatement().use { s ->
                s.execute("PRAGMA journal_mode=WAL")
                s.execute("CREATE TABLE users (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, email TEXT NOT NULL)")
                s.execute("CREATE INDEX index_User_email ON users(email)")
                s.execute("CREATE TABLE posts (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, userId INTEGER NOT NULL, body TEXT)")
            }
            c.prepareStatement("INSERT INTO users(name, email) VALUES (?, ?)").use { ps ->
                repeat(2) { i ->
                    ps.setString(1, "u$i")
                    ps.setString(2, "u$i@example.test")
                    ps.executeUpdate()
                }
            }
        }
        val bundle = root.resolve("app/schemas/AppDatabase/1.json")
        Files.createDirectories(bundle.parent)
        Files.writeString(bundle, """
            {
              "formatVersion": 1,
              "database": {
                "version": 1,
                "identityHash": "room-doc-hash-1",
                "entities": [
                  {
                    "tableName": "users",
                    "className": "com.example.User",
                    "createSql": "CREATE TABLE IF NOT EXISTS `users` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `email` TEXT NOT NULL)",
                    "fields": [
                      {"fieldPath": "id", "columnName": "id", "affinity": "INTEGER", "notNull": true},
                      {"fieldPath": "name", "columnName": "name", "affinity": "TEXT", "notNull": true},
                      {"fieldPath": "email", "columnName": "email", "affinity": "TEXT", "notNull": true}
                    ],
                    "primaryKey": ["id"],
                    "indices": [{"name": "index_User_email", "unique": false}]
                  },
                  {
                    "tableName": "posts",
                    "className": "com.example.Post",
                    "createSql": "CREATE TABLE IF NOT EXISTS `posts` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `userId` INTEGER NOT NULL, `body` TEXT)",
                    "fields": [
                      {"fieldPath": "id", "columnName": "id", "affinity": "INTEGER", "notNull": true},
                      {"fieldPath": "userId", "columnName": "userId", "affinity": "INTEGER", "notNull": true},
                      {"fieldPath": "body", "columnName": "body", "affinity": "TEXT"}
                    ],
                    "primaryKey": ["id"]
                  }
                ]
              }
            }
        """.trimIndent())
        return Pair(db, bundle)
    }

    /** Cold one-shot Room overview: entities, sidecars, drift in one string. */
    fun roomOverview(dbPath: String, schemaBundle: Path): String {
        val analyzer = RoomDatabaseAnalyzer(
            spec = SQLiteDatabaseSpec(dbPath),
            schemaBundle = RoomSchemaBundle.parse(schemaBundle),
        )
        return analyzer.use { ra ->
            val a = ra.analyzeOnce()
            val lines = ArrayList<String>()
            lines += "uniqueFootprint=" + a.totals.uniqueFootprint.value
            a.totals.dbFileBytes?.let { lines += "dbFile=" + it.value }
            for (e in a.entities) {
                lines += "entity=" + e.entityTypeId + " rows=" + (e.recordCount ?: -1L) +
                    " tableBytes=" + (e.tableBytes?.value ?: -1L)
            }
            for (d in a.drift) {
                lines += "drift=" + d.code + "(" + d.severity + ")"
            }
            lines.joinToString("\n")
        }
    }

    // -------------------------------------------------------- scenario L

    /** Engine-independent hot monitoring: Manual policy, driven explicitly. */
    fun reactiveManualSummary(root: Path): String = runBlocking {
        val reactive = ReactiveAnalyzer(
            analyzer = analyzerFor(root),
            scope = CoroutineScope(Dispatchers.Default),
            policy = RefreshPolicy.Manual,
            // Capacity metrics track the live volume and can change between two
            // immediate scans; this scenario demonstrates content-digest stability.
            options = AnalysisOptions(metrics = setOf(MetricType.STORED_CONTENT_SIZE)),
        )
        reactive.use { reactor ->
            reactor.refreshNow()
            val firstDigest = (reactor.state.value as RerunState.Completed<*>).digest
            reactor.refreshNow()
            val second = reactor.state.value as RerunState.Completed<*>
            val report = reactor.results.value
            "uniqueFootprint=" + report?.totals?.uniqueFootprint?.value +
                " digestStable=" + (second.digest == firstDigest)
        }
    }

    // -------------------------------------------------------- custom provider

    /** Minimal one-goal custom provider (three remote objects, honest rest). */
    class ToyCloudProvider : com.mohamed.storageanalyzer.core.provider.StorageProvider {
        override val providerId = ProviderId("x_toycloud")

        override fun describe() = "toy cloud (fixed fixture)"

        override fun capabilities() = com.mohamed.storageanalyzer.core.capability.Capabilities(
            supported = setOf(com.mohamed.storageanalyzer.core.capability.StorageCapability.FILE_LENGTH),
            unsupportedReasons = mapOf(
                com.mohamed.storageanalyzer.core.capability.StorageCapability.VOLUME_SPACE_STATS to
                    "quotas are not exposed by this toy SDK",
            ),
        )

        override fun capabilitiesFor(systemId: com.mohamed.storageanalyzer.core.identity.StorageSystemId) = capabilities()

        private val objects = mapOf(
            "a.bin" to 48L,
            "b.bin" to 42L,
            "c.bin" to 42L,
        )

        override fun discoverResources(
            systemId: com.mohamed.storageanalyzer.core.identity.StorageSystemId?,
            ctx: AnalysisContext,
        ) = sequence {
            ctx.ensureActive()
            for ((name, _) in objects) {
                yield(
                    com.mohamed.storageanalyzer.core.resource.StorageResource(
                        identity = com.mohamed.storageanalyzer.core.identity.ResourceIdentity(
                            providerId = providerId,
                            systemId = com.mohamed.storageanalyzer.core.identity.StorageSystemId("bucket/toy"),
                            kind = ResourceKind.FILE,
                            nativeId = name,
                        ),
                        providerId = providerId,
                        systemId = com.mohamed.storageanalyzer.core.identity.StorageSystemId("bucket/toy"),
                        label = name,
                        kind = ResourceKind.FILE,
                        category = com.mohamed.storageanalyzer.core.resource.StorageCategory.OTHER,
                    ),
                )
            }
        }

        override fun measure(
            resource: com.mohamed.storageanalyzer.core.identity.ResourceIdentity,
            metrics: Set<MetricType>,
            ctx: AnalysisContext,
        ): com.mohamed.storageanalyzer.core.result.StorageMeasurement {
            ctx.ensureActive()
            val now = java.time.Instant.now()
            return com.mohamed.storageanalyzer.core.result.StorageMeasurement(
                identity = resource,
                kind = resource.kind,
                category = com.mohamed.storageanalyzer.core.resource.StorageCategory.OTHER,
                measurements = metrics.associateWith { m ->
                    when (m) {
                        MetricType.STORED_CONTENT_SIZE -> com.mohamed.storageanalyzer.core.measurement.SingleMeasurement.measured(
                            m,
                            objects.getValue(resource.nativeId),
                            MeasurementProvenance(
                                method = com.mohamed.storageanalyzer.core.measurement.MeasurementMethod.custom("x_toycloud.head"),
                                accuracy = MeasurementAccuracy.EXACT,
                                measuredAt = now,
                                providerId = providerId.value,
                                backend = "toy-sdk 1.0",
                            ),
                        )
                        else -> com.mohamed.storageanalyzer.core.measurement.SingleMeasurement(
                            metric = m,
                            value = null,
                            state = MeasurementState.UNAVAILABLE,
                            provenance = MeasurementProvenance.absent(
                                providerId = providerId.value,
                                measuredAt = now,
                                method = MeasurementMethod.custom("x_toycloud.head"),
                                reason = "this toy provider only exposes object sizes",
                            ),
                            reason = "this toy provider only exposes object sizes",
                        )
                    }
                },
                completeness = Completeness.COMPLETE,
            )
        }

        override fun defaultMetricTypes() = setOf(MetricType.STORED_CONTENT_SIZE)

        override fun close() {
            // toy: nothing retained
        }
    }

    /** Sum of the toy provider's global unique footprint. */
    fun customProviderFootprint(): Long {
        val analyzer = StorageAnalyzer(providers = listOf(ToyCloudProvider()))
        val report = analyzer.use { it.analyzeGlobal(AnalysisOptions(metrics = setOf(MetricType.STORED_CONTENT_SIZE))) }
        return report.totals.uniqueFootprint.value
    }
}
