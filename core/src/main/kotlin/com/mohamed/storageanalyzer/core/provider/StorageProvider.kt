package com.mohamed.storageanalyzer.core.provider

import com.mohamed.storageanalyzer.core.capability.Capabilities
import com.mohamed.storageanalyzer.core.config.AnalysisContext
import com.mohamed.storageanalyzer.core.identity.ProviderId
import com.mohamed.storageanalyzer.core.identity.ResourceIdentity
import com.mohamed.storageanalyzer.core.measurement.MetricType
import com.mohamed.storageanalyzer.core.measurement.SingleMeasurement
import com.mohamed.storageanalyzer.core.resource.ReferenceLink
import com.mohamed.storageanalyzer.core.result.RecordBreakdown
import com.mohamed.storageanalyzer.core.result.StorageMeasurement
import com.mohamed.storageanalyzer.core.resource.StorageResource

/**
 * A pluggable analytics engine for one family of storage systems (filesystem,
 * SQLite, object store, ...).
 *
 * Implementation contract:
 * - **Non-destructive**: never modify stored content, schemas, indexes,
 *   transaction state, ownership, or move files during analysis.
 * - **Lazy & streaming**: discovery and relation traversal stream resources
 *   (no unbounded materialization); measurement is per-resource.
 * - **Honest**: unable metrics are reported as unmeasured with a reason;
 *   never silently return zeros.
 * - Providers must be safe to call from multiple threads only where they
 *   declare thread-safety; the analyzer drives them sequentially by default.
 */
interface StorageProvider : AutoCloseable {

    /** Stable identity, e.g. `filesystem`, `sqlite`. */
    val providerId: ProviderId

    /** Human-readable summary incl. backend/version (used in provenance). */
    fun describe(): String

    /** Capabilities common to all systems of this provider. */
    fun capabilities(): Capabilities

    /**
     * Capabilities probed for a concrete system (may run cheap runtime checks
     * like opening a connection). Must not modify state.
     */
    fun capabilitiesFor(systemId: com.mohamed.storageanalyzer.core.identity.StorageSystemId): Capabilities

    /**
     * Lists storage systems (e.g. roots, database files) known to this
     * provider. Explicitly registered systems are always returned; provider-
     * driven discovery beyond that is optional.
     */
    fun discoverSystems(): List<StorageSystemRef> = emptyList()

    /**
     * Streams all discoverable (or exposed) resources of this provider,
     * optionally restricted to [systemId] when non-null.
     */
    fun discoverResources(
        systemId: com.mohamed.storageanalyzer.core.identity.StorageSystemId? = null,
        ctx: AnalysisContext,
    ): Sequence<StorageResource>

    /**
     * Measures the requested metrics of one resource. Missing values are
     * returned as unmeasured entries with a reason — never silent zeros.
     */
    fun measure(
        resource: ResourceIdentity,
        metrics: Set<MetricType>,
        ctx: AnalysisContext,
    ): StorageMeasurement

    /**
     * Lazily resolves the storage-adjacency edges of [resource] (used for
     * entity graph traversal). Bounded implementations must respect the
     * context's budgets.
     */
    fun related(resource: ResourceIdentity, ctx: AnalysisContext): List<ReferenceLink> = emptyList()

    /**
     * Optional field-level record engine; `null` when the provider doesn't
     * support per-field breakdowns.
     */
    fun recordAnalytics(): com.mohamed.storageanalyzer.core.entity.spi.RecordAnalyticsEngine? = null

    /**
     * Optional entity-level resource resolver for descriptor enumeration.
     */
    fun entityResolver(): com.mohamed.storageanalyzer.core.entity.spi.EntityResourceResolver? = null

    /**
     * Health check: verifies the provider's working state and consistency of
     * the systems it manages. Must be read-only.
     */
    fun healthCheck(ctx: AnalysisContext): List<com.mohamed.storageanalyzer.core.result.StorageDiagnostic> = emptyList()

    /**
     * Metric set this provider measures by default when a run leaves the
     * query set empty. Used by the analyzer to apply provider-relevant
     * defaults; providers MUST keep this set honest (only metrics they
     * measure on their default resources).
     */
    fun defaultMetricTypes(): Set<MetricType> = emptySet()
}

/** A storage system known to a provider. */
data class StorageSystemRef(
    val systemId: com.mohamed.storageanalyzer.core.identity.StorageSystemId,
    /** Human-readable label; may be omitted for privacy. */
    val label: String? = null,
    val kind: com.mohamed.storageanalyzer.core.identity.ResourceKind,
    val capabilities: Capabilities,
)
