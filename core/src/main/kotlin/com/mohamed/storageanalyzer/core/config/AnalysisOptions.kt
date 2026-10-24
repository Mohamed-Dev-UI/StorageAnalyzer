package com.mohamed.storageanalyzer.core.config

import com.mohamed.storageanalyzer.core.identity.ProviderId
import com.mohamed.storageanalyzer.core.identity.ResourceIdentity
import com.mohamed.storageanalyzer.core.measurement.MetricType
import com.mohamed.storageanalyzer.core.policy.AccountingPolicy
import com.mohamed.storageanalyzer.core.policy.AccountingPolicies
import com.mohamed.storageanalyzer.core.resource.StorageCategory
import java.time.Duration

/**
 * Budgets and bounds that every analysis respects. They exist so that large
 * analyses never consume unbounded time/memory, with partial results instead
 * of unbounded blocking — all bounds are opt-in (`null` = unlimited).
 */
data class AnalysisBudget(
    /** Wall-clock deadline; when exceeded, results become partial. */
    val time: Duration? = null,

    /** Maximum number of resources to measure. */
    val maxResources: Long? = null,

    /** Maximum traversal depth of entity/graph analysis; `0` = direct only. */
    val maxDepth: Int? = 8,

    /** Maximum number of resource-to-resource edges traversed. */
    val maxEdgesTraversed: Long? = null,

    /** Maximum number of reporter progress events processed. */
    val maxProgressEvents: Long? = null,
) {
    init {
        require(maxDepth == null || maxDepth >= 0) { "maxDepth must be >= 0 when set" }
        require(maxResources == null || maxResources >= 0) { "maxResources must be >= 0 when set" }
    }

    companion object {
        val UNBOUNDED = AnalysisBudget()
    }
}

/**
 * Options of a single analysis run.
 */
data class AnalysisOptions(
    /** Metrics to measure; empty set = provider's default metric set. */
    val metrics: Set<MetricType> = emptySet(),

    /** Accounting policy applied to shared resources in this run. */
    val accountingPolicy: AccountingPolicy = AccountingPolicies.COUNT_ONCE_GLOBALLY,

    val budget: AnalysisBudget = AnalysisBudget.UNBOUNDED,

    /** Whether indexes / invisible helper structures participate in measurement. */
    val includeIndexes: Boolean = true,

    /** Whether related resources (relations) participate in entity analysis. */
    val includeRelations: Boolean = true,

    /** Whether inaccessible-but-discovered resources appear in the report. */
    val includeUnavailableResources: Boolean = true,

    /** Whether free/unused allocator space is measured (opt-in since capacity scans are cheap but noisy). */
    val includeFreeSpace: Boolean = false,

    /** Exclusion predicate; returns true when the resource must be dropped. */
    val excluded: (ResourceIdentity) -> Boolean = { false },

    /** Optional provider targeting: null = all registered providers. */
    val providerFilter: ProviderId? = null,

    /** Coarse category filtering, used by consumers to scan only selected buckets. */
    val categories: Set<StorageCategory>? = null,

    /**
     * Redacts labels/paths from public results (privacy option). Redaction
     * replaces identifying strings with a stable local hash for readability.
     */
    val redactLabels: Boolean = false,

    /**
     * (Optional) cap on measurement caching; when null, providers may reuse
     * their own cache policy. `true` forces full measurement for determinism.
     */
    val disableCaches: Boolean = false,
) {
    companion object {
        /** Default global metrics per provider. */
        val DEFAULT = AnalysisOptions()
    }
}
