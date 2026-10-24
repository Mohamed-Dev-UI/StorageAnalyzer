package com.mohamed.storageanalyzer.reactive

import com.mohamed.storageanalyzer.core.StorageAnalyzer
import com.mohamed.storageanalyzer.core.capability.Capabilities
import com.mohamed.storageanalyzer.core.capability.StorageCapability
import com.mohamed.storageanalyzer.core.config.AnalysisContext
import com.mohamed.storageanalyzer.core.identity.ProviderId
import com.mohamed.storageanalyzer.core.identity.ResourceIdentity
import com.mohamed.storageanalyzer.core.identity.ResourceKind
import com.mohamed.storageanalyzer.core.identity.StorageSystemId
import com.mohamed.storageanalyzer.core.measurement.MeasurementAccuracy
import com.mohamed.storageanalyzer.core.measurement.MeasurementMethod
import com.mohamed.storageanalyzer.core.measurement.MeasurementProvenance
import com.mohamed.storageanalyzer.core.measurement.MetricType
import com.mohamed.storageanalyzer.core.provider.StorageProvider
import com.mohamed.storageanalyzer.core.resource.StorageCategory
import com.mohamed.storageanalyzer.core.resource.StorageResource
import com.mohamed.storageanalyzer.core.result.Completeness
import com.mohamed.storageanalyzer.core.result.StorageMeasurement
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Minimal fixed-content provider; sizes can be mutated between turns. */
private class FakeFormatterProvider(private val files: MutableMap<String, Long>) : StorageProvider {

    override val providerId = ProviderId("x_testfake")

    override fun describe() = "fake provider for reactive tests"

    override fun capabilities() = Capabilities(
        supported = setOf(StorageCapability.FILE_LENGTH),
        unsupportedReasons = emptyMap(),
    )

    override fun capabilitiesFor(systemId: StorageSystemId) = capabilities()

    override fun discoverResources(
        systemId: StorageSystemId?,
        ctx: AnalysisContext,
    ) = sequence {
        ctx.ensureActive()
        for ((name, _) in files) {
            yield(
                StorageResource(
                    identity = ResourceIdentity(
                        providerId = providerId,
                        systemId = StorageSystemId("testfake/root"),
                        kind = ResourceKind.FILE,
                        nativeId = name,
                    ),
                    providerId = providerId,
                    systemId = StorageSystemId("testfake/root"),
                    label = name,
                    kind = ResourceKind.FILE,
                    category = StorageCategory.OTHER,
                ),
            )
        }
    }

    override fun measure(
        resource: ResourceIdentity,
        metrics: Set<MetricType>,
        ctx: AnalysisContext,
    ): StorageMeasurement {
        ctx.ensureActive()
        val now = Instant.now()
        return StorageMeasurement(
            identity = resource,
            kind = resource.kind,
            category = StorageCategory.OTHER,
            measurements = metrics.associateWith { m ->
                if (m == MetricType.STORED_CONTENT_SIZE) {
                    com.mohamed.storageanalyzer.core.measurement.SingleMeasurement.measured(
                        m,
                        files.getValue(resource.nativeId),
                        MeasurementProvenance(
                            method = MeasurementMethod.custom("x_testfake.length"),
                            accuracy = MeasurementAccuracy.EXACT,
                            measuredAt = now,
                            providerId = providerId.value,
                            backend = "fake",
                        ),
                    )
                } else {
                    com.mohamed.storageanalyzer.core.measurement.SingleMeasurement(
                        metric = m,
                        value = null,
                        state = com.mohamed.storageanalyzer.core.measurement.MeasurementState.UNAVAILABLE,
                        provenance = MeasurementProvenance.absent(
                            providerId = providerId.value,
                            measuredAt = now,
                            method = MeasurementMethod.custom("x_testfake.length"),
                            reason = "fake provider only sizes objects",
                        ),
                        reason = "fake provider only sizes objects",
                    )
                }
            },
            completeness = Completeness.COMPLETE,
        )
    }

    override fun defaultMetricTypes() = setOf(MetricType.STORED_CONTENT_SIZE)

    override fun close() {}
}

@OptIn(ExperimentalCoroutinesApi::class)
class ReactivePipelinesTest {

    // --------------------------------------------------------- primitive loop

    @Test
    fun `every policy runs turns and results publish only on digest change`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val controller = RerunController<Snapshot>(
            userScope = backgroundScope,
            policy = RefreshPolicy.Every(Duration.ofMillis(50)),
            dispatcher = dispatcher,
            produce = { turn += 1; Snapshot(content, turn) },
            digestOf = { it.size.toString() },
        )
        controller.start()
        runCurrent()
        assertEquals(1, turn)
        val ready = assertIs<RerunState.Completed<Snapshot>>(controller.state.value)
        assertEquals(1L, ready.value.size)

        // Same content again: result suppressed, state still moves forward.
        advanceTimeBy(60)
        runCurrent()
        assertEquals(2, turn)
        assertSame(ready.value, controller.results.value)

        // Content change: republished.
        content = 5
        advanceTimeBy(60)
        runCurrent()
        assertEquals(3, turn)
        assertEquals(5L, controller.results.value?.size)
    }

    @Test
    fun `failure turns keep the previous value and the loop recovers`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var shouldFail = false
        val controller = RerunController<Snapshot>(
            userScope = CoroutineScope(dispatcher),
            policy = RefreshPolicy.Manual,
            dispatcher = dispatcher,
            produce = {
                if (shouldFail) throw IllegalStateException("forced")
                Snapshot(content, ++turn)
            },
            digestOf = { it.size.toString() },
        )
        val first = controller.refreshNow()
        assertIs<RerunState.Completed<Snapshot>>(first)
        assertEquals(1L, controller.results.value?.size)

        shouldFail = true
        val failed = controller.refreshNow()
        val failure = assertIs<RerunState.Failed<Snapshot>>(failed)
        assertEquals("forced", failure.error.message)
        assertEquals(1L, failure.previous?.value?.size)
        assertIs<RerunState.Failed<Snapshot>>(controller.state.value)

        shouldFail = false
        content = 42
        val recovered = controller.refreshNow()
        assertIs<RerunState.Completed<Snapshot>>(recovered)
        assertEquals(42L, controller.results.value?.size)
    }

    @Test
    fun `manual policy never auto-runs`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val controller = RerunController<Snapshot>(
            userScope = backgroundScope,
            policy = RefreshPolicy.Manual,
            dispatcher = dispatcher,
            produce = { Snapshot(content, ++turn) },
            digestOf = { it.size.toString() },
        )
        controller.start()
        runCurrent()
        assertEquals(0, turn)
        val done = controller.refreshNow()
        assertIs<RerunState.Completed<Snapshot>>(done)
    }

    @Test
    fun `onChange policy runs on baseline then only on fingerprint change`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var fingerprint: String? = "static"
        val controller = RerunController<Snapshot>(
            userScope = backgroundScope,
            policy = RefreshPolicy.OnChange(
                probe = { fingerprint },
                pollInterval = Duration.ofMillis(50),
            ),
            dispatcher = dispatcher,
            produce = { Snapshot(content, ++turn) },
            digestOf = { it.size.toString() },
        )
        controller.start()
        advanceTimeBy(400)
        runCurrent()
        assertEquals(1, turn) // constant fingerprint -> baseline turn only

        fingerprint = "moved"
        advanceTimeBy(60)
        runCurrent()
        assertEquals(2, turn)

        fingerprint = null // unknown -> triggers a run
        advanceTimeBy(60)
        runCurrent()
        assertEquals(3, turn)
    }

    @Test
    fun `close stops the loop`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val controller = RerunController<Snapshot>(
            userScope = backgroundScope,
            policy = RefreshPolicy.Every(Duration.ofMillis(50)),
            dispatcher = dispatcher,
            produce = { Snapshot(content, ++turn) },
            digestOf = { it.size.toString() },
        )
        controller.start()
        runCurrent()
        advanceTimeBy(120)
        runCurrent()
        assertTrue(turn >= 2)
        controller.close()
        val stopped = turn
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(stopped, turn)
    }

    // --------------------------------------------------- analyzer integration

    @Test
    fun `ReactiveAnalyzer publishes global reports with digest-based suppression`() = runTest {
        val files = mutableMapOf("a.bin" to 48L, "b.bin" to 42L)
        val analyzer = StorageAnalyzer(providers = listOf(FakeFormatterProvider(files)))
        val dispatcher = StandardTestDispatcher(testScheduler)
        val reactive = ReactiveAnalyzer(
            analyzer = analyzer,
            scope = backgroundScope,
            policy = RefreshPolicy.Every(Duration.ofMillis(60)),
            dispatcher = dispatcher,
        )
        reactive.start()
        runCurrent()
        val first = assertNotNull(reactive.results.value)
        assertEquals(90L, first.totals.uniqueFootprint.value)

        advanceTimeBy(80)
        runCurrent()
        assertSame(first, reactive.results.value) // unchanged content suppressed

        files["b.bin"] = 400L
        advanceTimeBy(80)
        runCurrent()
        val second = assertNotNull(reactive.results.value)
        assertNotSame(first, second)
        assertEquals(448L, reactive.results.value?.totals?.uniqueFootprint?.value)
    }

    @Test
    fun `AnalysisDigest is deterministic and content-sensitive`() {
        val files = mutableMapOf("a.bin" to 1L)
        val report = StorageAnalyzer(listOf(FakeFormatterProvider(files))).use {
            it.analyzeGlobal()
        }
        val d1 = AnalysisDigest.of(report)
        val d2 = AnalysisDigest.of(report)
        assertEquals(d1, d2)
        assertTrue(d1.value.isNotEmpty())

        val report2 = StorageAnalyzer(listOf(FakeFormatterProvider(mutableMapOf("a.bin" to 90L)))).use {
            it.analyzeGlobal()
        }
        val d3 = AnalysisDigest.of(report2)
        assertTrue(d1 != d3)
    }

    @Test
    fun `coldReportFlow runs a fresh analysis per collection`() = runTest {
        val files = mutableMapOf("a.bin" to 7L)
        val analyzer = StorageAnalyzer(listOf(FakeFormatterProvider(files)))
        val first = analyzer.coldReportFlow().singleOrNull()
        assertNotNull(first)
        assertEquals(7L, first.totals.uniqueFootprint.value)
        files["b.bin"] = 2L
        val second = analyzer.coldReportFlow().singleOrNull()
        assertEquals(9L, second?.totals?.uniqueFootprint?.value)
    }

    // ---------------------------------------------------------------- helpers

    private class Snapshot(
        val size: Long,
        val turnNo: Int,
    )

    private var content: Long = 1
    private var turn: Int = 0
}
