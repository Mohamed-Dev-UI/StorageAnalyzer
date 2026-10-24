package com.mohamed.storageanalyzer.core.result

import com.mohamed.storageanalyzer.core.config.AnalysisOptions
import com.mohamed.storageanalyzer.core.entity.EntityInstanceKey
import com.mohamed.storageanalyzer.core.identity.ProviderId
import com.mohamed.storageanalyzer.core.measurement.ByteCount
import com.mohamed.storageanalyzer.core.measurement.MetricType
import com.mohamed.storageanalyzer.core.policy.AccountingPolicy
import com.mohamed.storageanalyzer.core.resource.OwnerRef
import java.time.Instant

/**
 * Result of the per-entity storage analysis, with the five separate
 * accounting views: direct, exclusive, inclusive, unique-reachable and
 * attributed-shared — plus per-field breakdowns when supported.
 */
data class EntityReport(
    val entityTypeId: String,
    val providerId: ProviderId,
    val instances: List<EntityInstanceKey>?,

    /** Number of entity instances covered by this report (1 for single-instance runs). */
    val instanceCount: Long,

    /** When inclusive traversal was used, metric totals for the relationship-aware views. */
    val direct: Map<MetricType, ByteCount>,
    val exclusive: Map<MetricType, ByteCount>,
    val inclusive: Map<MetricType, ByteCount>,
    val uniqueReachable: Map<MetricType, ByteCount>,

    /** Shared resources attributed to this entity by the policy. */
    val sharedAllocated: Map<MetricType, ByteCount>,

    /** Shared resources that the policy attributed to nobody (transparency). */
    val sharedNotAttributed: Map<MetricType, ByteCount>,

    /** Physical footprint allocated exclusively per the ownership policy. */
    val attributedPhysical: Map<MetricType, ByteCount>,

    /** Storage resources reached and counted in [uniqueReachable]. */
    val uniqueResourceCount: Long,

    val accountingPolicy: AccountingPolicy,
    val perFieldBreakdowns: List<RecordBreakdown> = emptyList(),
    val sharedAllocations: List<SharedResourceAllocation> = emptyList(),
    val diagnostics: List<StorageDiagnostic> = emptyList(),
    val completeness: Completeness = Completeness.COMPLETE,
    val measuredAt: Instant = Instant.now(),
) {
    /** Total attributed physical footprint for [metric] — semantics defined by [accountingPolicy]. */
    fun totalAttributedFootprint(metric: MetricType = MetricType.STORED_CONTENT_SIZE): ByteCount =
        attributedPhysical[metric] ?: ByteCount.ZERO
}
