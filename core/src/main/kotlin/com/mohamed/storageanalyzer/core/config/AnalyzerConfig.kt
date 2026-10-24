package com.mohamed.storageanalyzer.core.config

import com.mohamed.storageanalyzer.core.measurement.MetricType
import com.mohamed.storageanalyzer.core.policy.AccountingPolicy
import com.mohamed.storageanalyzer.core.policy.AccountingPolicies
import com.mohamed.storageanalyzer.core.provider.StorageProvider

/**
 * Global configuration of one [com.mohamed.storageanalyzer.core.StorageAnalyzer]
 * instance.
 *
 * Registered [StorageProvider]s and entities belong to an analyzer's setup;
 * per-run options belong to [AnalysisOptions].
 */
data class AnalyzerConfig(
    /**
     * Default accounting policy applied when a run does not specify one.
     * Default: count each shared resource exactly once globally (the honest,
     * no-double-counting baseline — see docs/ACCOUNTING.md).
     */
    val defaultAccountingPolicy: AccountingPolicy = AccountingPolicies.COUNT_ONCE_GLOBALLY,

    /**
     * Metric used as the *physical footprint* contribution in global reports;
     * by default the stored representation metric.
     */
    val footprintMetric: MetricType = com.mohamed.storageanalyzer.core.measurement.MetricType.STORED_CONTENT_SIZE,

    /**
     * When true, labels and paths carrying resource identifiers are redacted
     * in reports (privacy).
     */
    val redactLabels: Boolean = false,
) {
    init {
        require(footprintMetric in supportedFootprintMetrics) {
            "footprintMetric must be a stored/allocated dimension metric, was $footprintMetric"
        }
    }

    companion object {
        /** Metrics acceptable as physical footprint contribution. */
        internal val supportedFootprintMetrics: Set<MetricType> = setOf(
            com.mohamed.storageanalyzer.core.measurement.MetricType.STORED_CONTENT_SIZE,
            com.mohamed.storageanalyzer.core.measurement.MetricType.FILE_ALLOCATED_SIZE,
            com.mohamed.storageanalyzer.core.measurement.MetricType.FILE_LOGICAL_SIZE,
            com.mohamed.storageanalyzer.core.measurement.MetricType.DATABASE_TABLE_STORAGE,
            com.mohamed.storageanalyzer.core.measurement.MetricType.TEMPORARY_STORAGE,
        )
    }
}
