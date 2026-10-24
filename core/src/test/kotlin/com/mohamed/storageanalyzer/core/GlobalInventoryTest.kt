package com.mohamed.storageanalyzer.core

import com.mohamed.storageanalyzer.core.analytics.FakeStorageProvider
import com.mohamed.storageanalyzer.core.config.AnalysisOptions
import com.mohamed.storageanalyzer.core.identity.ProviderId
import com.mohamed.storageanalyzer.core.identity.ResourceIdentity
import com.mohamed.storageanalyzer.core.identity.ResourceKind
import com.mohamed.storageanalyzer.core.identity.StorageSystemId
import com.mohamed.storageanalyzer.core.measurement.MetricType
import com.mohamed.storageanalyzer.core.policy.AccountingPolicies
import com.mohamed.storageanalyzer.core.resource.OwnerRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * §11-§7.1 semantic locks: global dedup, no double counting, reconciliation.
 */
class GlobalInventoryTest {

    private fun fsFile(name: String) = ResourceIdentity(
        ProviderId("filesystem"),
        StorageSystemId("/vol"),
        ResourceKind.FILE,
        name,
    )

    private fun sqliteDb(path: String) = ResourceIdentity(
        ProviderId("sqlite"),
        StorageSystemId("/vol/$path"),
        ResourceKind.DATABASE,
        path,
    )

    @Test
    fun `a resource known to two providers is counted once globally`() {
        val file = fsFile("app.db")
        val fs = FakeStorageProvider(
            providerId = ProviderId("filesystem"),
            sizes = mapOf(file to 3000L),
        )
        val sq = FakeStorageProvider(
            providerId = ProviderId("sqlite"),
            sizes = mapOf(file to 3000L),
            // the sqlite provider declares that the filesystem provider reports the same file
            alsoBy = mapOf(file to setOf(file.copy(providerId = ProviderId("filesystem")))),
        )
        StorageAnalyzer(listOf(fs, sq)).use { analyzer ->
            val report = analyzer.analyzeGlobal()
            assertEquals(3000L, report.totals.uniqueFootprint.value, "identical identities must not double-count")
            assertEquals(1L, report.resourceCount)
            // the first provider (deterministic order) is the measuring one
            assertEquals(1L, report.providerSummary(ProviderId("filesystem"))?.breakdown?.resourceCount)
            assertEquals(0L, report.providerSummary(ProviderId("sqlite"))?.breakdown?.resourceCount)
            assertTrue(report.providers.any { it.deduplicatedReferenceCount >= 1L })
        }
    }

    @Test
    fun `shared resources are attributed exactly once under the equal split policy`() {
        val sharedByBoth = fsFile("shared.bin")
        val a1 = fsFile("a1.bin")
        val b1 = fsFile("b1.bin")
        val entityA = OwnerRef("/a1.bin", OwnerRef.OwnerKind.ENTITY, entityTypeId = "Ent")
        val entityB = OwnerRef("/b1.bin", OwnerRef.OwnerKind.ENTITY, entityTypeId = "Ent")
        val fs = FakeStorageProvider(
            providerId = ProviderId("filesystem"),
            sizes = mapOf(
                a1 to 1000L,
                b1 to 1000L,
                sharedByBoth to 600L,
            ),
            owners = mapOf(
                a1 to listOf(entityA),
                b1 to listOf(entityB),
                sharedByBoth to listOf(entityA, entityB),
            ),
        )
        StorageAnalyzer(listOf(fs)).use { analyzer ->
            val report = analyzer.analyzeGlobal(AnalysisOptions(accountingPolicy = AccountingPolicies.EQUAL_SPLIT))
            assertEquals(2600L, report.totals.uniqueFootprint.value)
            // exclusive: a1 + b1 (2000); shared 600 split 300/300 → attributedShared == 600
            assertEquals(2000L, report.totals.exclusiveAttributed.value)
            assertEquals(600L, report.totals.attributedShared.value)
            assertEquals(0L, report.totals.sharedOnly.value)
            assertEquals(0L, report.totals.unattributed.value)
            // conservation check reconciles
            val recon = report.reconciliation.first { it.checkId == "attribution_conservation" }
            assertEquals(0L, recon.difference.value)
            assertEquals(1, report.sharedAllocations.size)
        }
    }

    @Test
    fun `count-once policy leaves shared resources in the shared bucket and ownerless storage unattributed`() {
        val sharedByBoth = fsFile("shared.bin")
        val a1 = fsFile("a1.bin")
        val fs = FakeStorageProvider(
            providerId = ProviderId("filesystem"),
            sizes = mapOf(a1 to 100L, sharedByBoth to 50L),
            owners = mapOf(
                sharedByBoth to listOf(
                    OwnerRef("/a1.bin", OwnerRef.OwnerKind.ENTITY, entityTypeId = "Ent"),
                    OwnerRef("/b2.bin", OwnerRef.OwnerKind.PATH),
                ),
            ),
        )
        StorageAnalyzer(listOf(fs)).use { analyzer ->
            val report = analyzer.analyzeGlobal()
            assertEquals(150L, report.totals.uniqueFootprint.value)
            assertEquals(0L, report.totals.exclusiveAttributed.value)
            assertEquals(0L, report.totals.attributedShared.value)
            assertEquals(50L, report.totals.sharedOnly.value)
            assertEquals(100L, report.totals.unattributed.value, "ownerless storage is unattributed, never exclusive")
            assertEquals(0L, report.reconciliation.first { it.checkId == "attribution_conservation" }.difference.value)
        }
    }

    @Test
    fun `failing providers yield partial results with honest diagnostics`() {
        val ok = fsFile("ok.bin")
        val broken = fsFile("boom.bin")
        val fs = FakeStorageProvider(
            providerId = ProviderId("filesystem"),
            sizes = mapOf(ok to 10L, broken to 20L),
            failOn = setOf(broken),
        )
        StorageAnalyzer(listOf(fs)).use { analyzer ->
            val report = analyzer.analyzeGlobal()
            assertEquals(10L, report.totals.uniqueFootprint.value)
            assertEquals(1L, report.resourceCount)
            assertEquals(1, report.unmeasuredResources.size)
            assertEquals("boom.bin", report.unmeasuredResources.first().identity.nativeId)
            assertTrue(report.diagnostics.any { it.severity == com.mohamed.storageanalyzer.core.result.DiagnosticSeverity.ERROR })
            assertEquals(com.mohamed.storageanalyzer.core.result.Completeness.PARTIAL, report.completeness)
        }
    }

    @Test
    fun `breakdowns group by provider with per-metric sums`() {
        val f1 = fsFile("f1.txt")
        val f2 = fsFile("f2.log")
        val fs = FakeStorageProvider(
            providerId = ProviderId("filesystem"),
            sizes = mapOf(f1 to 11L, f2 to 5L),
        )
        StorageAnalyzer(listOf(fs)).use { analyzer ->
            val report = analyzer.analyzeGlobal(AnalysisOptions(metrics = setOf(MetricType.STORED_CONTENT_SIZE, MetricType.FILE_LOGICAL_SIZE)))
            val bucket = report.byProvider.single { it.key == "filesystem" }
            assertEquals(16L, bucket[MetricType.STORED_CONTENT_SIZE]!!.value)
            assertEquals(16L, bucket[MetricType.FILE_LOGICAL_SIZE]!!.value)
            assertEquals(2L, bucket.resourceCount)
        }
    }

    @Test
    fun `run-level exclusion predicate drops resources from the unique inventory`() {
        val file = fsFile("app.db")
        val foreign = fsFile("notmine.txt")
        val fs = FakeStorageProvider(
            providerId = ProviderId("filesystem"),
            sizes = mapOf(file to 3000L, foreign to 100L),
        )
        val sq = FakeStorageProvider(
            providerId = ProviderId("sqlite"),
            sizes = mapOf(file to 3000L),
            alsoBy = mapOf(file to setOf(file.copy(providerId = ProviderId("filesystem")))),
        )
        StorageAnalyzer(listOf(fs, sq)).use { analyzer ->
            val report = analyzer.analyzeGlobal(AnalysisOptions(excluded = { it == foreign }))
            assertEquals(3000L, report.totals.uniqueFootprint.value)
            assertEquals(1L, report.resourceCount)
            // request-level exclusions are surfaced as one aggregated diagnostic
            assertTrue(report.diagnostics.any {
                it.code == "general.excluded_resources" &&
                    it.severity == com.mohamed.storageanalyzer.core.result.DiagnosticSeverity.INFO &&
                    it.message.contains("1")
            })
        }
    }

    @Test
    fun `excluded dedup owner keeps aliased duplicates out of unique totals too`() {
        val file = fsFile("app.db")
        val fs = FakeStorageProvider(
            providerId = ProviderId("filesystem"),
            sizes = mapOf(file to 3000L),
        )
        val sq = FakeStorageProvider(
            providerId = ProviderId("sqlite"),
            sizes = mapOf(file to 3000L),
            alsoBy = mapOf(file to setOf(file.copy(providerId = ProviderId("filesystem")))),
        )
        StorageAnalyzer(listOf(fs, sq)).use { analyzer ->
            // Excluding the filesystem owner must not resurrect the same bytes
            // under the sqlite alias; the view still de-duplicates to nothing.
            val report = analyzer.analyzeGlobal(AnalysisOptions(excluded = { it == file }))
            assertEquals(0L, report.totals.uniqueFootprint.value)
            assertEquals(0L, report.resourceCount)
            // Both provider views of the excluded identity drop (no alias can
            // resurrect excluded bytes); one aggregated diagnostic reports it.
            assertTrue(report.diagnostics.any {
                it.code == "general.excluded_resources" && it.message.contains("2")
            })
        }
    }

    @Test
    fun `overlap measurements remain visible but outside the unique footprint`() {
        val file = fsFile("app.db")
        val fs = FakeStorageProvider(ProviderId("filesystem"), sizes = mapOf(file to 3000L))
        val sq = FakeStorageProvider(
            ProviderId("sqlite"),
            sizes = mapOf(file to 3000L),
            alsoBy = mapOf(file to setOf(file.copy(providerId = ProviderId("filesystem")))),
        )
        StorageAnalyzer(listOf(fs, sq)).use { analyzer ->
            val report = analyzer.analyzeGlobal(
                AnalysisOptions(
                    metrics = setOf(
                        MetricType.STORED_CONTENT_SIZE,
                        MetricType.DATABASE_PAGE_CAPACITY,
                    ),
                ),
            )
            assertEquals(3000L, report.totals.uniqueFootprint.value)
            // the sqlite view of the same file is reported (unmeasured 3000?) but not merged:
            assertEquals(1, report.overlapMeasurements.size)
        }
    }
}
