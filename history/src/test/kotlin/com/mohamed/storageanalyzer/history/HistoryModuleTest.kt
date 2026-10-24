package com.mohamed.storageanalyzer.history

import com.mohamed.storageanalyzer.core.identity.ProviderId
import com.mohamed.storageanalyzer.core.measurement.ByteCount
import com.mohamed.storageanalyzer.core.measurement.MeasurementAccuracy
import com.mohamed.storageanalyzer.core.measurement.MetricDimension
import com.mohamed.storageanalyzer.core.measurement.MetricType
import com.mohamed.storageanalyzer.core.resource.StorageCategory
import com.mohamed.storageanalyzer.core.snapshot.StorageSnapshot
import java.io.IOException
import java.nio.file.Files
import java.time.Instant
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Unit tests for snapshot persistence, comparison and growth modelling. */
class HistoryModuleTest {

    // ------------------------------------------------------------- fixtures

    private fun snap(
        at: Instant,
        footprint: Long,
        metrics: Map<MetricType, Long> = emptyMap(),
        providers: Map<String, Long> = emptyMap(),
        categories: Map<StorageCategory, Long> = emptyMap(),
        resources: Map<String, Long> = emptyMap(),
        completeness: String = "COMPLETE",
    ): StorageSnapshot = StorageSnapshot(
        capturedAt = at,
        footprintMetric = MetricType.STORED_CONTENT_SIZE,
        footprintTotal = ByteCount(footprint),
        byMetric = metrics.mapValues { ByteCount(it.value) },
        byProvider = providers.mapKeys { ProviderId(it.key) }.mapValues { ByteCount(it.value) },
        byCategory = categories.mapValues { ByteCount(it.value) },
        byResource = resources.mapValues { ByteCount(it.value) },
        resourceCount = resources.size.toLong(),
        completenessLabel = completeness,
    )

    private fun at(epochSecond: Long): Instant = Instant.ofEpochSecond(epochSecond)

    // ------------------------------------------------------------- codec

    @Test
    fun `codec round-trips vanilla snapshots`() {
        val s = snap(
            at = at(1_700_000_000),
            footprint = 12345,
            metrics = mapOf(MetricType.STORED_CONTENT_SIZE to 12345, MetricType.FILE_LOGICAL_SIZE to 900),
            providers = mapOf("filesystem" to 700, "sqlite" to 535),
            categories = mapOf(StorageCategory.DATABASE to 535, StorageCategory.OTHER to 700),
            resources = mapOf("C:/db.sqlite|t:users" to 400, "C:/data.bin" to 900),
            completeness = "COMPLETE",
        )
        val decoded = SnapshotFormat.decode(SnapshotFormat.encode(s))
        assertEquals(s, decoded)
    }

    @Test
    fun `codec round-trips hostile keys and custom metrics`() {
        val custom = MetricType.custom("x_myprovider.ptrmap_pages", MetricDimension.ALLOCATED)
        val s = snap(
            at = at(5),
            footprint = 7,
            metrics = mapOf(custom to 2),
            resources = mapOf(
                "weird key/=newline\n%~\t_mid" to 1,
                "sqlite://C:/db file.sqlite|t:üñí" to 4,
            ),
            completeness = "PARTIAL(budget)",
        )
        val decoded = SnapshotFormat.decode(SnapshotFormat.encode(s))
        assertEquals(s, decoded)
        assertEquals(2L, decoded.byMetric[custom]!!.value)
        assertEquals(1L, decoded.byResource["weird key/=newline\n%~\t_mid"]!!.value)
    }

    @Test
    fun `codec refuses corrupted documents`() {
        assertFailsWith<IllegalArgumentException> {
            SnapshotFormat.decode("#storageanalyzer-snapshot-v2\ncapturedAt=2020-01-01T00:00:00Z\n")
        }
        assertFailsWith<IllegalArgumentException> {
            SnapshotFormat.decode(HEADER_LINE + "\nfootprintTotal=-1\n")
        }
        assertFailsWith<IllegalArgumentException> {
            SnapshotFormat.decode(HEADER_LINE + "\nbyMetric=stored_content_size|STORED=1=notanumber\n")
        }
        assertFailsWith<IllegalArgumentException> {
            SnapshotFormat.decode(HEADER_LINE + "\nbyProvider=oops%zz=3\n") // malformed escape
        }
    }

    // ------------------------------------------------------------- stores

    @Test
    fun `in-memory store preserves append order and latest`() {
        val store = InMemorySnapshotStore()
        assertEquals(0, store.size)
        val a = snap(at(1), 10)
        val b = snap(at(2), 20)
        assertEquals(0L, store.append(a))
        assertEquals(1L, store.append(b))
        assertEquals(listOf(a, b), store.readAll())
        assertEquals(b, store.latest())
        assertEquals(2, store.size)
        store.close()
    }

    @Test
    fun `file store round-trips append order, indexes and clear`() {
        val dir = Files.createTempDirectory("sa-history")
        val store = FileSnapshotStore(dir)
        val a = snap(at(100), 111, resources = mapOf("C:/a.db|t:x" to 50))
        val b = snap(at(200), 222, completeness = "PARTIAL")
        assertEquals(0L, store.append(a))
        assertEquals(1L, store.append(b))
        assertEquals(listOf(a, b), store.readAll())
        assertEquals(2, store.size)

        // re-open against the same directory: nothing is lost
        assertEquals(listOf(a, b), FileSnapshotStore(dir).readAll())
        assertEquals(2, FileSnapshotStore(dir).append(snap(at(300), 333)))

        assertEquals(3, store.clear())
        assertEquals(emptyList(), store.readAll())
        store.close()
    }

    @Test
    fun `file store fails loudly on a corrupt document`() {
        val dir = Files.createTempDirectory("sa-history")
        Files.writeString(dir.resolve("000000000000.snapshot"), "#garbage\n")
        val store = FileSnapshotStore(dir)
        assertFailsWith<IOException> { store.readAll() }
        store.close()
    }

    // ------------------------------------------------------------- compare

    @Test
    fun `comparison computes exact deltas, attribution and honesty annotations`() {
        val from = snap(
            at(1000),
            footprint = 1000,
            metrics = mapOf(MetricType.STORED_CONTENT_SIZE to 600, MetricType.FILE_LOGICAL_SIZE to 400),
            providers = mapOf("filesystem" to 1000),
            categories = mapOf(StorageCategory.OTHER to 1000),
            resources = mapOf("a" to 600, "b" to 400),
        )
        val to = snap(
            at(1600),
            footprint = 1500,
            metrics = mapOf(MetricType.STORED_CONTENT_SIZE to 900, MetricType.TOTAL_CAPACITY to 500),
            providers = mapOf("sqlite" to 1500),
            categories = mapOf(StorageCategory.OTHER to 1500),
            resources = mapOf("a" to 700, "c" to 800),
        )
        val cmp = SnapshotComparison.between(from, to)

        // footprint: +500 over 600s
        assertEquals(500L, cmp.footprintDeltaBytes)
        assertEquals(0.5, cmp.footprintGrowthFraction!!, 1e-12)
        assertEquals(500.0 / 600.0, cmp.growthBytesPerSecond!!, 1e-12)
        assertEquals(MeasurementAccuracy.EXACT, cmp.accuracy)

        // shared metric grows; one-sided metrics are never treated as zero
        assertEquals(300L, cmp.metricDeltas.getValue(MetricType.STORED_CONTENT_SIZE).deltaBytes)
        assertEquals(0.5, cmp.metricDeltas.getValue(MetricType.STORED_CONTENT_SIZE).growthFraction!!, 1e-12)
        assertEquals(
            "metric 'file_logical_size' is absent from the later snapshot",
            cmp.notComparableMetrics.getValue(MetricType.FILE_LOGICAL_SIZE),
        )
        assertEquals(
            "metric 'total_capacity' is absent from the earlier snapshot",
            cmp.notComparableMetrics.getValue(MetricType.TOTAL_CAPACITY),
        )

        // categories: OTHER grew by 500
        assertEquals(500L, cmp.categoryDeltas.getValue(StorageCategory.OTHER))
        assertEquals(1, cmp.categoryDeltas.size)

        // attribution
        val attr = cmp.attribution!!
        assertEquals(mapOf("c" to ByteCount(800)), attr.created)
        assertEquals(mapOf("b" to ByteCount(400)), attr.removed)
        assertEquals(mapOf("a" to 100L), attr.changed)
        assertEquals(0, attr.unchangedCount)
    }

    @Test
    fun `comparison labels partially-covered runs and guards ordering and undefined rates`() {
        val incomplete = snap(at(10), 5, completeness = "PARTIAL")
        val later = snap(at(20), 7)
        assertEquals(MeasurementAccuracy.PARTIAL, SnapshotComparison.between(incomplete, later).accuracy)

        // identical timestamps → growth rate undefined, never zero
        val same = snap(at(50), 10)
        val other = snap(at(50), 20)
        assertNull(SnapshotComparison.between(same, other).growthBytesPerSecond)

        // timestamp order is refused so deltas keep their meaning
        assertFailsWith<IllegalArgumentException> {
            SnapshotComparison.between(snap(at(100), 3), snap(at(50), 2))
        }
    }

    @Test
    fun `comparison without resource details reports attribution as unavailable`() {
        val cmp = SnapshotComparison.between(snap(at(1), 10), snap(at(2), 12))
        assertNull(cmp.attribution)
    }

    // ------------------------------------------------------------- growth

    @Test
    fun `collinear series yields the exact slope and predictions stay on the line`() {
        val model = GrowthModelFactory.fit(
            listOf(
                snap(at(0), 1000),
                snap(at(60), 2000),
                snap(at(120), 3000),
            ),
        )
        val linear = assertIs<GrowthModel.Linear>(model)
        assertEquals(1000.0 / 60.0, linear.slopeBytesPerSecond, 1e-9)
        assertEquals(2000.0 - linear.slopeBytesPerSecond * 60.0, linear.interceptBytes, 1e-6)
        assertEquals(1.0, linear.r2, 1e-9)
        assertEquals(3, linear.pointCount)
        // the model line keeps rising beyond the window: 1000 + (1000/60) * 240
        assertEquals(5000.0, linear.estimateAt(at(240)), 1e-6)
    }

    @Test
    fun `non-collinear series matches the hand-computed least-squares fit`() {
        // points (0,1), (60,3), (120,4)  -> slope = 3/120, intercept = 7/6
        val model = GrowthModelFactory.fit(
            listOf(
                snap(at(0), 1),
                snap(at(60), 3),
                snap(at(120), 4),
            ),
        )
        val linear = assertIs<GrowthModel.Linear>(model)
        assertEquals(1.5 / 60.0, linear.slopeBytesPerSecond, 1e-9)
        assertEquals(7.0 / 6.0, linear.interceptBytes, 1e-9)
        assertEquals(0.9642857142857143, linear.r2, 1e-6)
        // estimate beyond the window uses the model line
        assertEquals(1.5 * 3 + 7.0 / 6.0, linear.estimateAt(at(180)), 1e-9)
    }

    @Test
    fun `shrinking storage keeps its negative slope and unclamped estimates`() {
        val model = GrowthModelFactory.fit(listOf(snap(at(0), 1000), snap(at(100), 500)))
        val linear = assertIs<GrowthModel.Linear>(model)
        assertTrue(linear.slopeBytesPerSecond < 0.0)
        assertTrue(linear.estimateAt(at(1000)) < 0.0, "negative estimates must not be silently clamped")
    }

    @Test
    fun `insufficient input produces an unavailable model with a reason`() {
        assertTrue(
            assertIs<GrowthModel.Unavailable>(GrowthModelFactory.fit(emptyList())).reason.contains(
                "no snapshots",
                ignoreCase = true,
            ),
        )
        assertTrue(
            assertIs<GrowthModel.Unavailable>(GrowthModelFactory.fit(listOf(snap(at(0), 1)))).reason.contains(
                "two",
                ignoreCase = true,
            ),
        )
        val sameInstant = listOf(snap(at(0), 1), snap(at(0), 2))
        assertTrue(
            assertIs<GrowthModel.Unavailable>(GrowthModelFactory.fit(sameInstant)).reason.contains(
                "timestamp",
                ignoreCase = true,
            ),
        )
    }

    @Test
    fun `unsorted input is normalized by capturedAt before fitting`() {
        val model = GrowthModelFactory.fit(
            listOf(
                snap(at(120), 3000),
                snap(at(0), 1000),
                snap(at(60), 2000),
            ),
        )
        val linear = assertIs<GrowthModel.Linear>(model)
        assertEquals(1.0, linear.r2, 1e-9)
        assertEquals(abs(linear.slopeBytesPerSecond), 1000.0 / 60.0, 1e-9)
    }

    companion object {
        private val HEADER_LINE = SnapshotFormat.headerLine
    }
}
