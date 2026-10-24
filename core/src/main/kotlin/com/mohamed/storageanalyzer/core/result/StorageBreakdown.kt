package com.mohamed.storageanalyzer.core.result

import com.mohamed.storageanalyzer.core.measurement.ByteCount
import com.mohamed.storageanalyzer.core.measurement.MetricType

/**
 * One aggregation bucket inside a report grouping (by provider, category,
 * kind, scope, ...).
 */
data class StorageBreakdown(
    /** Stable bucket key (provider id, category name, scope id, ...). */
    val key: String,

    /** Human-readable bucket label (redactable by policy). */
    val label: String?,

    /** Measured totals per metric inside this bucket. */
    val byMetric: Map<MetricType, ByteCount>,

    /** Number of unique resources counted in this bucket. */
    val resourceCount: Long,

    /** Number of requested-but-unmeasured metrics inside this bucket. */
    val unmeasuredMetrics: Long = 0,
) {
    /** Metric total when measured for this bucket. */
    operator fun get(metric: MetricType): ByteCount? = byMetric[metric]
}
