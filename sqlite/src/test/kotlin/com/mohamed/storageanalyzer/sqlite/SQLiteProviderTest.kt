package com.mohamed.storageanalyzer.sqlite

import com.mohamed.storageanalyzer.core.config.AnalysisContext
import com.mohamed.storageanalyzer.core.identity.PathIdentity
import com.mohamed.storageanalyzer.core.identity.ResourceKind
import com.mohamed.storageanalyzer.core.measurement.MeasurementAccuracy
import com.mohamed.storageanalyzer.core.measurement.MeasurementState
import com.mohamed.storageanalyzer.core.result.Completeness
import com.mohamed.storageanalyzer.core.result.DiagnosticSeverity
import com.mohamed.storageanalyzer.testing.RecordingContext
import com.mohamed.storageanalyzer.testing.TempDir
import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Provider tests against real SQLite databases (bundled xerial driver):
 * page accounting, record-format math with hand-computed byte counts,
 * sidecar discovery, freelist semantics, integrity/corruption diagnostics,
 * cancellation and identity round-trips.
 */
class SQLiteProviderTest {

    private fun createDb(dir: File, name: String, ddl: String): File {
        val f = dir.resolve(name)
        val c = DriverManager.getConnection("jdbc:sqlite:" + f.absolutePath)
        // xerial's Statement objects must not be reused for separate executions,
        // and comment-only statements (e.g. `-- prefix`) would prepare a null
        // statement ("The prepared statement has been finalized", issue #731).
        ddl.split(";")
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("--") }
            .forEach {
                c.createStatement().use { s -> s.execute(it) }
            }
        c.close()
        return f
    }

    private fun rawDbstatSum(file: File, name: String): Long? {
        val c = DriverManager.getConnection("jdbc:sqlite:" + file.absolutePath)
        return try {
            val rs = c.createStatement().executeQuery("SELECT SUM(pgsize) FROM dbstat WHERE name = '$name'")
            rs.getLong(1)
        } finally {
            c.close()
        }
    }

    // ------------------------------------------------------------------ basics

    @Test
    fun `empty in-memory database discovers only the database resource and reports capacity`() {
        val p = SQLiteStorageProvider(listOf(SQLiteDatabaseSpec(":memory:")))
        val ctx = AnalysisContext.create()
        val resources = p.discoverResources(null, ctx).toList()
        assertEquals(1, resources.size)
        val db = resources.single()
        assertEquals(ResourceKind.DATABASE, db.identity.kind)
        assertEquals("memory", db.identity.namespace)

        val m = p.measure(db.identity, emptySet(), ctx)
        val stored = m.valueOf(com.mohamed.storageanalyzer.core.measurement.MetricType.STORED_CONTENT_SIZE)!!
        // an empty :memory: database has no pages allocated yet (page_count = 0)
        assertEquals(0L, stored.value)
        val capacity = m.valueOf(com.mohamed.storageanalyzer.core.measurement.MetricType.DATABASE_PAGE_CAPACITY)!!
        assertEquals(stored.value, capacity.value)
        val freelist = m.valueOf(com.mohamed.storageanalyzer.core.measurement.MetricType.DATABASE_FREELIST_SIZE)!!
        assertEquals(0L, freelist.value)
        assertEquals(Completeness.COMPLETE, m.completeness)
        p.close()
    }

    @Test
    fun `table and index b-tree sums are exact against dbstat`() {
        val tmp = TempDir("sa-sqlite")
        tmp.use {
            val db = createDb(
                tmp.root.toFile(),
                "data.sqlite",
                "CREATE TABLE orders (id INTEGER PRIMARY KEY, note TEXT NOT NULL, entries BLOB);" +
                    "CREATE INDEX idx_note ON orders(note);" +
                    "INSERT INTO orders(note, entries) VALUES ('alpha', X'0102030405'), ('beta', X'060708');",
            )
            val p = SQLiteStorageProvider(listOf(SQLiteDatabaseSpec(db.path)))
            val ctx = AnalysisContext.create()
            val caps = p.capabilitiesFor(p.systems().single())
            if (!caps.supports(com.mohamed.storageanalyzer.core.capability.StorageCapability.DB_DBSTAT)) {
                // paranoid guard: this driver build ships dbstat; if it didn't, exact data is not comparable
                return
            }
            val tables = p.discoverResources(null, ctx).filter { it.kind == ResourceKind.TABLE }.toList()
            assertEquals(1, tables.size)
            val orders = tables.single()
            assertEquals("orders", orders.label)
            // by design: table decomposes the database (no double counting in global totals)
            assertTrue(orders.decomposes != null)

            val m = p.measure(orders.identity, emptySet(), ctx)
            val expected = rawDbstatSum(db, "orders")!!
            val tableStorage = m.valueOf(com.mohamed.storageanalyzer.core.measurement.MetricType.DATABASE_TABLE_STORAGE)!!
            assertEquals(expected, tableStorage.value)
            val prov = m.measurements.keys.let { m.measurements[com.mohamed.storageanalyzer.core.measurement.MetricType.DATABASE_TABLE_STORAGE]!! }
            assertEquals(MeasurementAccuracy.EXACT, prov.provenance.accuracy)

            val idx = p.discoverResources(null, ctx).filter { it.kind == ResourceKind.INDEX }.toList()
            assertEquals(1, idx.size)
            val mIdx = p.measure(idx.single().identity, emptySet(), ctx)
            assertEquals(rawDbstatSum(db, "idx_note")!!, mIdx.valueOf(com.mohamed.storageanalyzer.core.measurement.MetricType.DATABASE_INDEX_STORAGE)!!.value)
            p.close()
        }
    }

    // ------------------------------------------------- record-format math

    /** Hand-computed convention sizes for the `vals` fixture, per rowid. */
    private fun insertMeasureFixture(file: File) {
        val c = DriverManager.getConnection("jdbc:sqlite:" + file.absolutePath)
        for (sql in listOf(
            "CREATE TABLE vals (id INTEGER PRIMARY KEY, name TEXT, data BLOB, score REAL, note TEXT)",
            "INSERT INTO vals VALUES (1, 'abc', X'0102030405', 1.5, NULL)",
            "INSERT INTO vals VALUES (2, 'xy', NULL, 2.5, 'z')",
            "INSERT INTO vals VALUES (1000, NULL, X'0a0b0c', NULL, NULL)",
        )) {
            c.createStatement().use { s -> s.execute(sql) }
        }
        c.close()
    }

    @Test
    fun `per-record encoded sizes match hand computation and conservation`() {
        val tmp = TempDir("sa-sqlite")
        tmp.use {
            val db = createDb(tmp.root.toFile(), "vals.sqlite", "-- placeholder")
            insertMeasureFixture(db)
            val p = SQLiteStorageProvider(listOf(SQLiteDatabaseSpec(db.path)))
            val ctx = AnalysisContext.create()
            val dbRes = p.discoverResources(null, ctx).first { it.kind == ResourceKind.DATABASE }
            val dbIdentity = dbRes.identity
            val stability = p.discoverResources(null, ctx).first().identity.stability
            val ns = SQLiteResourceIds.namespaceOf(p.specOf(dbRes.identity.systemId)!!)
            val sysId = dbRes.identity.systemId

            val r1 = p.measure(SQLiteResourceIds.recordByRowid(sysId, ns, "vals", 1L, stability), emptySet(), ctx)
            // row 1: alias(0) + text(3+1) + blob(5+1) + real(8+1) + NULL(0);
            // header varint(1) + rowid varint(1) + payload-size varint(1) overhead = 3
            assertEquals(22L, r1.valueOf(com.mohamed.storageanalyzer.core.measurement.MetricType.DATABASE_RECORD_SIZE)!!.value)
            assertEquals(
                MeasurementAccuracy.CONVENTION,
                r1.measurements.getValue(com.mohamed.storageanalyzer.core.measurement.MetricType.DATABASE_RECORD_SIZE).provenance.accuracy,
            )
            val b1 = r1.recordBreakdown!!
            // field conservation: fields + overhead == total
            val fieldsSum = b1.fields.sumOf { it.value?.value ?: 0L }
            assertEquals(19L, fieldsSum)
            val overhead1 = b1.overhead!!
            assertEquals(3L, overhead1.value)
            assertEquals(r1.valueOf(com.mohamed.storageanalyzer.core.measurement.MetricType.DATABASE_RECORD_SIZE)!!.value, fieldsSum + overhead1.value)
            // alias column contributes zero and is labeled
            assertEquals(0L, b1.fields.first { it.attributeName == "id" }.value!!.value)

            val r3 = p.measure(SQLiteResourceIds.recordByRowid(sysId, ns, "vals", 1000L, stability), emptySet(), ctx)
            // row 1000: blob(3+1) only; serials=1, payloads=3, header=2, record=5;
            // rowid 1000 -> 2 bytes varint; payload-size varint 1 → total 8
            assertEquals(8L, r3.valueOf(com.mohamed.storageanalyzer.core.measurement.MetricType.DATABASE_RECORD_SIZE)!!.value)

            // aggregate: 22 + 17 + 8 = 47 (see below for row 2)
            val table = p.discoverResources(null, ctx).first { it.kind == ResourceKind.TABLE }
            val aggregates = p.aggregateFieldBreakdown(
                com.mohamed.storageanalyzer.core.entity.spi.CollectionKey(table.identity),
                ctx,
            )
            assertTrue(aggregates.isNotEmpty())

            val tableMeasure = p.measure(table.identity, setOf(com.mohamed.storageanalyzer.core.measurement.MetricType.DATABASE_RECORD_SIZE), ctx)
            val total = tableMeasure.valueOf(com.mohamed.storageanalyzer.core.measurement.MetricType.DATABASE_RECORD_SIZE)!!
            // Sum of per-row convention sizes for the fixture (22 + 17 + 8).
            assertEquals(47L, total.value)
            p.close()
        }
    }

    @Test
    fun `row 2 of the fixture is 17 bytes`() {
        val tmp = TempDir("sa-sqlite")
        tmp.use {
            val db = createDb(tmp.root.toFile(), "vals2.sqlite", "--")
            insertMeasureFixture(db)
            val p = SQLiteStorageProvider(listOf(SQLiteDatabaseSpec(db.path)))
            val ctx = AnalysisContext.create()
            val dbRes = p.discoverResources(null, ctx).first { it.kind == ResourceKind.DATABASE }
            val ns = SQLiteResourceIds.namespaceOf(p.specOf(dbRes.identity.systemId)!!)
            val stability = dbRes.identity.stability
            val m = p.measure(SQLiteResourceIds.recordByRowid(dbRes.identity.systemId, ns, "vals", 2L, stability), emptySet(), ctx)
            // row 2: text 'xy' (2+1) + real (8+1) + text 'z' (1+1); serials=3, payloads=11,
            // header=4, record=15, rowid varint 1, payload-size varint 1 → 17
            assertEquals(17L, m.valueOf(com.mohamed.storageanalyzer.core.measurement.MetricType.DATABASE_RECORD_SIZE)!!.value)
            p.close()
        }
    }

    @Test
    fun `without rowid tables use pk addressing and record-only encoding`() {
        val tmp = TempDir("sa-sqlite")
        tmp.use {
            val db = createDb(
                tmp.root.toFile(),
                "nr.sqlite",
                "CREATE TABLE nr (k TEXT PRIMARY KEY, v TEXT, f REAL) WITHOUT ROWID;" +
                    "INSERT INTO nr VALUES ('k1', 'aa', 1.0);",
            )
            val p = SQLiteStorageProvider(listOf(SQLiteDatabaseSpec(db.path)))
            val ctx = AnalysisContext.create()
            val dbRes = p.discoverResources(null, ctx).first { it.kind == ResourceKind.DATABASE }
            val ns = SQLiteResourceIds.namespaceOf(p.specOf(dbRes.identity.systemId)!!)
            val stability = dbRes.identity.stability
            val identity = SQLiteResourceIds.recordByPrimaryKey(
                dbRes.identity.systemId, ns, "nr", listOf("k1"), stability,
            )
            val m = p.measure(identity, emptySet(), ctx)
            // serials: 'k1'(1) + 'aa'(1) + real(1) = 3; payloads 2+2+8=12; header=4;
            // record = 16, no rowid/payload varints; overhead = varint(header)=1
            assertEquals(16L, m.valueOf(com.mohamed.storageanalyzer.core.measurement.MetricType.DATABASE_RECORD_SIZE)!!.value)
            val b = m.recordBreakdown!!
            val fieldsSum = b.fields.sumOf { it.value?.value ?: 0L }
            val overhead = b.overhead!!
            assertEquals(1L, overhead.value)
            assertEquals(fieldsSum + overhead.value, m.valueOf(com.mohamed.storageanalyzer.core.measurement.MetricType.DATABASE_RECORD_SIZE)!!.value)
            p.close()
        }
    }

    @Test
    fun `integer primary key desc tables do not alias the rowid`() {
        val tmp = TempDir("sa-sqlite")
        tmp.use {
            val db = createDb(
                tmp.root.toFile(),
                "desc.sqlite",
                "CREATE TABLE dt (id INTEGER PRIMARY KEY DESC, payload TEXT);" +
                    "INSERT INTO dt VALUES (1, 'ab');",
            )
            val p = SQLiteStorageProvider(listOf(SQLiteDatabaseSpec(db.path)))
            val ctx = AnalysisContext.create()
            val table = p.discoverResources(null, ctx).first { it.kind == ResourceKind.TABLE }
            val desc = p.measure(table.identity, setOf(com.mohamed.storageanalyzer.core.measurement.MetricType.DATABASE_RECORD_SIZE), ctx)
            // no alias: id counts as an integer value (1+1=2), 'ab' (2+1=3); serials=2,
            // payloads=3, header=3, record=6, rowid varint 1, payload-size varint 1 → 8
            assertEquals(8L, desc.valueOf(com.mohamed.storageanalyzer.core.measurement.MetricType.DATABASE_RECORD_SIZE)!!.value)
            p.close()
        }
    }

    // ------------------------------------------------- freelist & reconciliation

    @Test
    fun `deletions surface freelist bytes and a reconciliation diagnostic`() {
        val tmp = TempDir("sa-sqlite")
        tmp.use {
            val ddl = StringBuilder("CREATE TABLE d (p TEXT);")
            repeat(80) { i ->
                ddl.append("INSERT INTO d VALUES ('")
                ddl.append("x".repeat(9000) + i.toString())
                ddl.append("');")
            }
            ddl.append("DELETE FROM d WHERE p LIKE '%1%'; DELETE FROM d WHERE p LIKE '%3%';")
            val db = createDb(tmp.root.toFile(), "fl.sqlite", ddl.toString())
            val p = SQLiteStorageProvider(listOf(SQLiteDatabaseSpec(db.path)))
            val ctx = AnalysisContext.create()
            val dbRes = p.discoverResources(null, ctx).first { it.kind == ResourceKind.DATABASE }
            val m = p.measure(dbRes.identity, emptySet(), ctx)
            val freelist = m.valueOf(com.mohamed.storageanalyzer.core.measurement.MetricType.DATABASE_FREELIST_SIZE)!!
            assertTrue(freelist.value > 0, "freelist after mass delete should exceed 0, was ${freelist.value}")
            val rec = m.diagnostics.filter { it.code == "sqlite.page_reconciliation" }
            assertEquals(1, rec.size)
            assertEquals(DiagnosticSeverity.INFO, rec.single().severity)
            p.close()
        }
    }

    // ------------------------------------------------- sidecars & dedup aliases

    @Test
    fun `wal sidecars are separate resources with filesystem dedup aliases`() {
        val tmp = TempDir("sa-sqlite")
        tmp.use {
            val db = createDb(
                tmp.root.toFile(),
                "wal.sqlite",
                "PRAGMA journal_mode=WAL; CREATE TABLE w (v TEXT);",
            )
            // WAL files only survive while a second connection stays open;
            // closing the last connection checkpoints and removes them.
            val holder = DriverManager.getConnection("jdbc:sqlite:" + db.absolutePath)
            try {
                holder.createStatement().use { s ->
                    s.execute("PRAGMA journal_mode=WAL")
                    s.execute("INSERT INTO w VALUES ('" + "x".repeat(200000) + "')")
                    s.execute("INSERT INTO w VALUES ('" + "y".repeat(200000) + "')")
                }
                val walFile = File(db.parent, db.name + "-wal")
                if (!walFile.isFile) return  // engine decided not to keep a WAL; nothing to assert
                val p = SQLiteStorageProvider(listOf(SQLiteDatabaseSpec(db.path)))
                val ctx = AnalysisContext.create()
                val sidecars = p.discoverResources(null, ctx).filter { it.kind == ResourceKind.FILE }.toList()
                assertTrue(sidecars.any { it.identity.namespace == "sidecar-wal" })
                val wal = sidecars.first { it.identity.namespace == "sidecar-wal" }
                val expectedAlias = PathIdentity.forPath(walFile.path)
                assertTrue(expectedAlias in wal.alsoDiscoverableBy)
                val wm = p.measure(wal.identity, emptySet(), ctx)
                assertEquals(
                    walFile.length(),
                    wm.valueOf(com.mohamed.storageanalyzer.core.measurement.MetricType.STORED_CONTENT_SIZE)!!.value,
                )
                val dbRes = p.discoverResources(null, ctx).first { it.kind == ResourceKind.DATABASE }
                val dm = p.measure(dbRes.identity, emptySet(), ctx)
                val stored = dm.measurements.values.first {
                    it.metric == com.mohamed.storageanalyzer.core.measurement.MetricType.STORED_CONTENT_SIZE
                }
                assertTrue("sqlite.excludes.wal-sidecars" in stored.provenance.limitations)
                p.close()
            } finally {
                holder.close()
            }
        }
    }

    // ------------------------------------------------- diagnostics & edge paths

    @Test
    fun `corrupt database files produce open-failure diagnostics`() {
        val tmp = TempDir("sa-sqlite")
        tmp.use {
            val f = tmp.root.resolve("corrupt.sqlite")
            Files.write(f, ByteArray(4096) { (it % 250 + 1).toByte() })
            val p = SQLiteStorageProvider(listOf(SQLiteDatabaseSpec(f.toAbsolutePath().toString())))
            val ctx = AnalysisContext.create()
            val res = p.discoverResources(null, ctx).toList()
            // the db resource is still reported; measurement says what happened
            assertEquals(1, res.size)
            val health = p.healthCheck(ctx)
            // The engine exposes the corruption either at connect/probe time
            // (open_failed) or at the integrity pass — both must be ERROR-level.
            assertTrue(health.any { it.severity == DiagnosticSeverity.ERROR })
            val m = p.measure(res.single().identity, emptySet(), ctx)
            // catalog unreadable → not COMPLETE; page stats unavailable; still never silent zeros
            assertEquals(Completeness.UNKNOWN, m.completeness)
            val capacityState = m.measurements.getValue(
                com.mohamed.storageanalyzer.core.measurement.MetricType.DATABASE_PAGE_CAPACITY,
            ).state
            assertEquals(MeasurementState.UNAVAILABLE, capacityState)
            p.close()
        }
    }

    @Test
    fun `missing database file yields a missing-file diagnostic and nothing else`() {
        val p = SQLiteStorageProvider(listOf(SQLiteDatabaseSpec("Z:/definitely/not/here.sqlite")))
        val ctx = AnalysisContext.create()
        val health = p.healthCheck(ctx)
        assertTrue(health.any { it.code == "sqlite.database_file_missing" && it.severity == DiagnosticSeverity.ERROR })
        val res = p.discoverResources(null, ctx).toList()
        assertEquals(1, res.size) // the DATABASE spec is still exposed
        p.close()
    }

    @Test
    fun `cancelled contexts abort discovery and measurement`() {
        val tmp = TempDir("sa-sqlite")
        tmp.use {
            val db = createDb(tmp.root.toFile(), "c.sqlite", "CREATE TABLE t (a); INSERT INTO t VALUES (1);")
            val p = SQLiteStorageProvider(listOf(SQLiteDatabaseSpec(db.path)))
            val ctx = RecordingContext()
            ctx.cancelled = true
            var threw = false
            try {
                p.discoverResources(null, ctx).toList()
            } catch (e: com.mohamed.storageanalyzer.core.result.StorageError.Cancelled) {
                threw = true
            }
            assertTrue(threw)
            threw = false
            try {
                p.measure(
                    com.mohamed.storageanalyzer.testing.testIdentity("sqlite", "memory#0"),
                    emptySet(),
                    ctx,
                )
            } catch (e: com.mohamed.storageanalyzer.core.result.StorageError.Cancelled) {
                threw = true
            }
            assertTrue(threw)
            p.close()
        }
    }

    @Test
    fun `identity encoding round-trips separator-heavy names`() {
        val weird = "weird:with:colon%"
        val encoded = SQLiteResourceIds.encodeComponent(weird)
        assertEquals(weird, SQLiteResourceIds.decodeComponent(encoded))


        val tmp = TempDir("sa-sqlite")
        tmp.use {
            // Windows cannot carry ':' in file names; the FILE name stays plain.
            // The ':' lives in the TABLE name only (that's what's encoded).
            val db = createDb(
                tmp.root.toFile(),
                "weirdcol.sqlite",
                "CREATE TABLE \"weird:tbl\" (id INTEGER PRIMARY KEY, v TEXT);" +
                    "INSERT INTO \"weird:tbl\" VALUES (7, 'x');",
            )
            val p = SQLiteStorageProvider(listOf(SQLiteDatabaseSpec(db.path)))
            val ctx = AnalysisContext.create()
            val tables = p.discoverResources(null, ctx).filter { it.kind == ResourceKind.TABLE }.toList()
            assertEquals(1, tables.size)
            val tbl = tables.single()
            assertEquals("weird:tbl", tbl.label)
            val ns = SQLiteResourceIds.namespaceOf(p.specOf(tbl.identity.systemId)!!)
            val stability = tbl.identity.stability
            val rec = SQLiteResourceIds.recordByRowid(tbl.identity.systemId, ns, "weird:tbl", 7L, stability)
            assertEquals(
                "weird:tbl",
                SQLiteResourceIds.parseRecordNative(rec.nativeId)!!.first,
            )
            val m = p.measure(rec, emptySet(), ctx)
            // alias id=0, 'x' text (1+1=2); serials 1, payloads 1, header 2, record 3;
            // rowid varint 1, payload-size varint 1 → 5
            assertEquals(5L, m.valueOf(com.mohamed.storageanalyzer.core.measurement.MetricType.DATABASE_RECORD_SIZE)!!.value)
            p.close()
        }
    }

    @Test
    fun `entity resolver streams rowid records for table descriptors`() {
        val tmp = TempDir("sa-sqlite")
        tmp.use {
            val db = createDb(
                tmp.root.toFile(),
                "ent.sqlite",
                "CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT);" +
                    "INSERT INTO users VALUES (1,'a'),(2,'b'),(3,'c');",
            )
            val p = SQLiteStorageProvider(listOf(SQLiteDatabaseSpec(db.path)))
            val ctx = AnalysisContext.create()
            val sysId = p.systems().single()
            val descriptor = p.entityDescriptorForTable("app.User", sysId, "users")!!
            val records = p.entityResolver()!!.resourcesFor(descriptor, ctx).toList()
            assertEquals(3, records.size)
            assertTrue(records.all { it.kind == ResourceKind.RECORD && it.providerId == p.providerId })
            assertTrue(records.all { it.identity.nativeId.startsWith("r:") })
            assertEquals(
                1L,
                SQLiteResourceIds.parseRecordNative(records[0].identity.nativeId)!!.second.let {
                    (it as SQLiteResourceIds.RowSelector.ByRowid).rowid
                },
            )
            p.close()
        }
    }

    @Test
    fun `aggregate breakdown matches per-column hand totals`() {
        val tmp = TempDir("sa-sqlite")
        tmp.use {
            // tiny table; hand totals:
            // a: rows (1→1B, 2→1B) payloads 2, serials 2, populated 2
            // b: 'aa'/'bb' → 3+3 payloads, serials 2, populated 2
            // c: NULL/1 → payload 1, serial 1, populated 1
            val db = createDb(
                tmp.root.toFile(),
                "agg.sqlite",
                "CREATE TABLE agg (a INTEGER PRIMARY KEY, b TEXT, c TEXT);" +
                    "INSERT INTO agg VALUES (1, 'aa', NULL), (2, 'bb', 'q');",
            )
            val p = SQLiteStorageProvider(listOf(SQLiteDatabaseSpec(db.path)))
            val ctx = AnalysisContext.create()
            val table = p.discoverResources(null, ctx).first { it.kind == ResourceKind.TABLE }
            val out = p.aggregateFieldBreakdown(
                com.mohamed.storageanalyzer.core.entity.spi.CollectionKey(table.identity),
                ctx,
            )
            assertEquals(3, out.size)
            val a = out.first { it.attributeName == "a" }
            val b = out.first { it.attributeName == "b" }
            val c = out.first { it.attributeName == "c" }
            // alias column: its bytes live in the rowid varint, so it contributes 0
            assertEquals(0L, a.totalAcrossRecords.value)
            assertEquals(0L, a.populatedRecords)
            assertEquals(6L, b.totalAcrossRecords.value)
            assertEquals(2L, b.populatedRecords)
            assertEquals(2L, c.totalAcrossRecords.value)
            assertEquals(1L, c.populatedRecords)
            p.close()
        }
    }

    @Test
    fun `foreign key relation factory resolves child rows`() {
        val tmp = TempDir("sa-sqlite")
        tmp.use {
            val db = createDb(
                tmp.root.toFile(),
                "fk.sqlite",
                "CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT);" +
                    "CREATE TABLE orders (id INTEGER PRIMARY KEY, user_id INTEGER, total REAL);" +
                    "INSERT INTO users VALUES (1,'ann'),(2,'bo');" +
                    "INSERT INTO orders VALUES (10, 1, 5.0),(11, 1, 7.0),(12, 2, 3.0);",
            )
            val p = SQLiteStorageProvider(listOf(SQLiteDatabaseSpec(db.path)))
            val ctx = AnalysisContext.create()
            val sysId = p.systems().single()
            val users = p.entityDescriptorForTable("app.User", sysId, "users")!!
            val userIdentity = users.identityOf(com.mohamed.storageanalyzer.core.entity.EntityInstanceKey("1"))
            val rel = com.mohamed.storageanalyzer.sqlite.SQLiteRelations.reverseForeignKey(
                p,
                name = "orders",
                childTable = "orders",
                childColumn = "user_id",
            )
            val links = rel.resolver.resolve(userIdentity)
            assertEquals(2, links.size)
            val ids = links.map { it.toIdentity.nativeId }
            assertTrue(ids.all { it.startsWith("r:orders:") })
            // measuring one of the linked rows works
            val m = p.measure(links[0].toIdentity, emptySet(), ctx)
            assertTrue(m.valueOf(com.mohamed.storageanalyzer.core.measurement.MetricType.DATABASE_RECORD_SIZE) != null)
            p.close()
        }
    }
}
