package com.mohamed.storageanalyzer.core.result

import com.mohamed.storageanalyzer.core.capability.Capabilities
import com.mohamed.storageanalyzer.core.config.AnalysisOptions
import com.mohamed.storageanalyzer.core.identity.ProviderId
import com.mohamed.storageanalyzer.core.identity.ResourceIdentity
import com.mohamed.storageanalyzer.core.identity.ResourceKind
import com.mohamed.storageanalyzer.core.measurement.ByteCount
import com.mohamed.storageanalyzer.core.measurement.MetricType
import com.mohamed.storageanalyzer.core.policy.AccountingPolicy
import com.mohamed.storageanalyzer.core.resource.StorageCategory
import com.mohamed.storageanalyzer.core.resource.StorageScope
import java.time.Duration
import java.time.Instant

/** Honest summary of one provider's share inside a global run. */
data class ProviderSummary(
    val providerId: ProviderId,
    val description: String,
    val capabilities: Capabilities,
    val breakdown: StorageBreakdown,
    /**
     * Resources of *other* providers that this provider reported too and were
     * therefore excluded from the unique global inventory by deduplication.
     */
    val deduplicatedReferenceCount: Long,
)

/** Summary of one shared resource's allocation under the applied policy. */
data class SharedResourceAllocation(
    val identity: ResourceIdentity,
    val footprintBytes: ByteCount,
    val ownerCount: Int,
    /** Per-owner allocation (empty when policy attributes nobody). */
    val allocations: Map<com.mohamed.storageanalyzer.core.resource.OwnerRef, ByteCount>,
    val policyId: String,
)

/** An explicit reconciliation difference between two accounting views. */
data class ReconciliationDifference(
    /** Stable check identifier (see docs/ARCHITECTURE.md checks list). */
    val checkId: String,
    /** What each side of the check accounts for. */
    val leftLabel: String,
    val rightLabel: String,
    val leftBytes: ByteCount,
    val rightBytes: ByteCount,
    /** absolute difference; ≥ 0. */
    val difference: ByteCount,
    /**
     * Whether the difference is explainable by a known, documented accounting
     * difference (then it is still reported, but with an explanation).
     */
    val explained: Boolean,
    val explanation: String?,
)

/** Verdict of a [ReconciliationDifference]. */
enum class ReconciliationVerdict { RECONCILED, EXPECTED_DIFFERENCE, UNEXPECTED_DIFFERENCE }

/**
 * The global storage inventory report across all registered providers.
 *
 * **Accounting guarantee:** every unique resource is counted exactly once in
 * the uniqueFootprint; the sum of entity-level inclusive sizes is never used
 * to derive this total.
 */
data class GlobalReport(
    val generatedAt: Instant,
    val duration: Duration,
    val options: AnalysisOptions,
    val accountingPolicy: AccountingPolicy,

    /** Totals applying the accounting policy. */
    val totals: StorageTotals,

    /** Breakdown by provider (unique resources only). */
    val byProvider: List<StorageBreakdown>,

    /** Per-provider view summary (capabilities, provider-view totals incl. duplicate views). */
    val providers: List<ProviderSummary>,

    /** Breakdown by [ResourceKind]. */
    val byKind: List<StorageBreakdown>,

    /** Breakdown by [StorageCategory]. */
    val byCategory: List<StorageBreakdown>,

    /** Breakdown by explicit [StorageScope] (empty when no scopes assigned). */
    val byScope: List<StorageBreakdown>,

    /** Shared resources and how the applied policy attributed them. */
    val sharedAllocations: List<SharedResourceAllocation>,

    /** One resource discovered but not measurable in this run. */
    val unmeasuredResources: List<UnmeasuredResource>,

    /** A breadcrumb of per-resource footprint summaries for history analyses. */
    val resourceSummaries: List<ResourceSummary>,

    /**
     * Measurements the inventory deduplicated (same underlying resource as
     * another provider's entry). Never merged into `totals`; surfaced for
     * honest per-provider views and reconciliation.
     */
    val overlapMeasurements: List<StorageMeasurement>,

    /** Reconciliation checks. */
    val reconciliation: List<ReconciliationDifference>,

    /** All diagnostics of this run (deduplicated, ordered by severity). */
    val diagnostics: List<StorageDiagnostic>,

    /** Number of unique resources measured. */
    val resourceCount: Long,

    val completeness: Completeness,
) {
    /** Provider view by id (deterministic helper). */
    fun providerSummary(providerId: ProviderId): ProviderSummary? =
        providers.firstOrNull { it.providerId == providerId }
}

/** One resource discovered but not measurable in this run. */
data class UnmeasuredResource(
    val identity: ResourceIdentity,
    val kind: ResourceKind,
    val reason: String,
    val providerId: ProviderId? = null,
)

/** Compact per-resource summary carried inside reports (byResource growth). */
data class ResourceSummary(
    val identity: ResourceIdentity,
    val kind: ResourceKind,
    val footPrintMetrics: Map<MetricType, ByteCount>,
)
