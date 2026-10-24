package com.mohamed.dev.ui.storageanalyzer.data

import com.mohamed.dev.ui.storageanalyzer.data.provider.ArchiveCatalogProvider
import com.mohamed.dev.ui.storageanalyzer.data.provider.ShowcasePreferencesProvider
import com.mohamed.storageanalyzer.core.StorageAnalyzer
import com.mohamed.storageanalyzer.core.config.AnalysisBudget
import com.mohamed.storageanalyzer.core.config.AnalysisContext
import com.mohamed.storageanalyzer.core.config.AnalysisOptions
import com.mohamed.storageanalyzer.core.config.AnalysisProgress
import com.mohamed.storageanalyzer.core.config.AnalyzerConfig
import com.mohamed.storageanalyzer.core.identity.ProviderId
import com.mohamed.storageanalyzer.core.measurement.ByteCount
import com.mohamed.storageanalyzer.core.measurement.MetricType
import com.mohamed.storageanalyzer.core.policy.AccountingPolicies
import com.mohamed.storageanalyzer.core.result.Completeness
import com.mohamed.storageanalyzer.core.result.DiagnosticSeverity
import com.mohamed.storageanalyzer.core.result.GlobalReport
import com.mohamed.storageanalyzer.core.result.StorageError
import com.mohamed.storageanalyzer.filesystem.FilesystemProvider
import com.mohamed.storageanalyzer.testing.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * End-to-end JVM coverage of the same provider stack the app assembles:
 * filesystem + archive catalog (alias/dedup) + preferences (shared owners).
 * This is what makes the Lab screen's numbers reproducible in CI.
 */
class ShowcaseAnalysisTest {

    private lateinit var fixture: Fixture

    @BeforeTest
    fun setUp() {
        fixture = Fixture()
    }

    @AfterTest
    fun tearDown() {
        fixture.close()
    }

    private val options = AnalysisOptions(metrics = setOf(MetricType.STORED_CONTENT_SIZE))

    @Test
    fun `providers compose in id order and the alias is deduplicated`() {
        assertEquals(
            listOf("filesystem", "x_archive_catalog", "x_showcase_prefs"),
            fixture.analyzer.providers().map { it.providerId.value },
        )

        val report = fixture.analyze(options)

        assertEquals(fixture.filesystemBytes + fixture.preferencesBytes, report.totals.uniqueFootprint.value)

        val overlap = report.overlapMeasurements.single()
        assertEquals("x_archive_catalog", overlap.identity.providerId.value)
        assertEquals(fixture.archiveBytes, overlap.valueOf(MetricType.STORED_CONTENT_SIZE)?.value)

        val archiveView = report.providerSummary(ProviderId("x_archive_catalog"))!!
        assertEquals(1, archiveView.deduplicatedReferenceCount)
        assertEquals(0, report.providerSummary(ProviderId("filesystem"))!!.deduplicatedReferenceCount)

        // A provider view must include the duplicate reporting provider's
        // measurement; the app uses this to show non-zero "duplicate" bytes.
        val providerViews = report.totals.uniqueFootprint + overlap.valueOf(MetricType.STORED_CONTENT_SIZE)!!
        assertEquals(fixture.filesystemBytes + fixture.preferencesBytes + fixture.archiveBytes, providerViews.value)
    }

    @Test
    fun `accounting policies conserve bytes and allocate the shared entry explicitly`() {
        val countOnce = fixture.analyze(options.copy(accountingPolicy = AccountingPolicies.COUNT_ONCE_GLOBALLY))
        val canonical = fixture.analyze(options.copy(accountingPolicy = AccountingPolicies.CANONICAL_OWNER))
        val equal = fixture.analyze(options.copy(accountingPolicy = AccountingPolicies.EQUAL_SPLIT))

        for (report in listOf(countOnce, canonical, equal)) {
            assertEquals(countOnce.totals.uniqueFootprint, report.totals.uniqueFootprint)
            assertEquals(
                report.totals.uniqueFootprint.value,
                (report.totals.exclusiveAttributed +
                    report.totals.attributedShared +
                    report.totals.sharedOnly +
                    report.totals.unattributed).value,
            )
        }

        assertEquals(fixture.sharedEntryBytes, countOnce.totals.sharedOnly.value)
        assertEquals(0L, countOnce.totals.attributedShared.value)

        assertEquals(0L, canonical.totals.sharedOnly.value)
        assertEquals(fixture.sharedEntryBytes, canonical.totals.attributedShared.value)

        assertEquals(0L, equal.totals.sharedOnly.value)
        assertEquals(fixture.sharedEntryBytes, equal.totals.attributedShared.value)

        val allocation = equal.sharedAllocations.single()
        assertEquals(2, allocation.ownerCount)
        assertEquals(fixture.sharedEntryBytes, ByteCount.sum(allocation.allocations.values).value)
    }

    @Test
    fun `provider filter restricts the run without unregistering providers`() {
        val all = fixture.analyze(options)
        val filtered = fixture.analyze(options.copy(providerFilter = ProviderId("filesystem")))

        assertEquals(3, all.providers.size)
        assertEquals(listOf("filesystem"), filtered.providers.map { it.providerId.value })
        assertEquals(fixture.filesystemBytes, filtered.totals.uniqueFootprint.value)
        assertTrue(filtered.overlapMeasurements.isEmpty())
    }

    @Test
    fun `exclusion predicate drops matching identities and is reported`() {
        val before = fixture.analyze(options)
        val after = fixture.analyze(
            options.copy(excluded = { identity ->
                identity.nativeId.endsWith(".tmp") || identity.nativeId.endsWith(".log")
            }),
        )

        assertEquals(96L, before.totals.uniqueFootprint.value - after.totals.uniqueFootprint.value)
        assertTrue(after.diagnostics.any { it.code == "general.excluded_resources" })
    }

    @Test
    fun `redaction hides labels but keeps identities comparable`() {
        val visible = fixture.analyze(options)
        val redacted = fixture.analyze(options.copy(redactLabels = true))

        val visibleOverlap = visible.overlapMeasurements.single()
        val redactedOverlap = redacted.overlapMeasurements.single()

        assertEquals("legacy-backup.zip (catalog entry)", visibleOverlap.label)
        assertTrue(redactedOverlap.label.orEmpty().startsWith("label-"))
        assertEquals(visibleOverlap.identity.stableKey, redactedOverlap.identity.stableKey)
    }

    @Test
    fun `resource budget stops early with an honest partial report`() {
        val full = fixture.analyze(options)
        val partial = fixture.analyze(options.copy(budget = AnalysisBudget(maxResources = 3)))

        assertEquals(Completeness.PARTIAL, partial.completeness)
        assertTrue(partial.resourceCount <= 3)
        assertTrue(partial.totals.uniqueFootprint <= full.totals.uniqueFootprint)
    }

    @Test
    fun `cancellation surfaces as the typed StorageError`() {
        val context = AnalysisContext.create()
        context.onProgress { event ->
            if (event is AnalysisProgress.ResourceMeasured && event.count >= 1) context.cancel()
        }

        val error = assertFailsWith<StorageError.Cancelled> {
            fixture.analyzer.analyzeGlobal(options, context)
        }

        assertTrue(error.message.orEmpty().contains("cancelled"))
    }

    @Test
    fun `health checks are clean for the seeded provider stack`() {
        val diagnostics = fixture.analyzer.runDiagnostics()

        assertTrue(diagnostics.none { it.severity == DiagnosticSeverity.ERROR }, diagnostics.toString())
    }

    // ------------------------------------------------------------------ fixture

    private class Fixture : AutoCloseable {

        val tmp = TempDir("showcase-analysis")

        val entries = sortedMapOf(
            "feature_flags" to "attachments=true;sync=wifi",
            "theme.mode" to "system",
            "sync.endpoint" to "https://sync.example.dev/v2",
        )

        val archiveBytes = 256L

        val filesystemBytes: Long

        val preferencesBytes: Long
            get() = entries.entries.sumOf { (key, value) -> key.length + 1 + value.length }.toLong()

        val sharedEntryBytes: Long
            get() = ("feature_flags".length + 1 + entries.getValue("feature_flags").length).toLong()

        val analyzer: StorageAnalyzer

        init {
            val zip = write("archives/legacy-backup.zip", archiveBytes.toInt())
            filesystemBytes = listOf(
                write("documents/report.txt", 1000),
                write("media/clip.bin", 500),
                write("cache/scratch.tmp", 64),
                write("logs/app.log", 32),
            ).sumOf { Files.size(it) } + archiveBytes

            analyzer = StorageAnalyzer(
                config = AnalyzerConfig(footprintMetric = MetricType.STORED_CONTENT_SIZE),
                providers = listOf(
                    FilesystemProvider(
                        roots = listOf(tmp.root.toString()),
                        allocationUnitResolver = { 4096L },
                    ),
                    ArchiveCatalogProvider(zip.toFile()),
                    ShowcasePreferencesProvider { entries },
                ),
            )
        }

        fun analyze(options: AnalysisOptions): GlobalReport = analyzer.analyzeGlobal(options)

        private fun write(relative: String, size: Int): Path {
            val path = tmp.root.resolve(relative)
            Files.createDirectories(path.parent)
            return Files.write(path, ByteArray(size) { (it % 251).toByte() })
        }

        override fun close() {
            analyzer.close()
            tmp.close()
        }
    }
}
