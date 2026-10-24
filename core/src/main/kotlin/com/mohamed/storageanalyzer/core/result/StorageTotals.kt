package com.mohamed.storageanalyzer.core.result

import com.mohamed.storageanalyzer.core.measurement.ByteCount
import com.mohamed.storageanalyzer.core.measurement.MetricType

/**
 * Aggregated attribution totals of a global run, or of one entity view.
 *
 * All physical attribution totals refer to the configured **footprint
 * metric** (see AnalyzerConfig). They are accounting projections — never
 * presented as direct physical measurements of individual resources.
 */
data class StorageTotals(
    /** Simple per-metric sums (no attribution involved). */
    val byMetric: Map<MetricType, ByteCount>,

    /** Physical footprint of the *unique* resources in scope. */
    val uniqueFootprint: ByteCount,

    /** Resources exclusively attributed to their owner(s) per the policy. */
    val exclusiveAttributed: ByteCount,

    /** Physical bytes attributed to owners from shared resources. */
    val attributedShared: ByteCount,

    /** Physical bytes of shared resources NOT attributed to any owner. */
    val sharedOnly: ByteCount,

    /** Bytes that could not be attributed at all (worst-case budget/policy leftovers). */
    val unattributed: ByteCount,

    /** Bytes whose measurement was unavailable (excluded from unique totals). */
    val unmeasuredFootprint: ByteCount,
) {
    val attributed: ByteCount get() = exclusiveAttributed + attributedShared
}
