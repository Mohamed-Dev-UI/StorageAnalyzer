package com.mohamed.storageanalyzer.core

import com.mohamed.storageanalyzer.core.analytics.GlobalReportBuilder
import com.mohamed.storageanalyzer.core.cache.MeasurementCache
import com.mohamed.storageanalyzer.core.cache.NullMeasurementCache
import com.mohamed.storageanalyzer.core.config.AnalysisContext
import com.mohamed.storageanalyzer.core.config.AnalysisOptions
import com.mohamed.storageanalyzer.core.config.AnalysisProgress
import com.mohamed.storageanalyzer.core.config.AnalyzerConfig
import com.mohamed.storageanalyzer.core.config.DefaultAnalysisContext
import com.mohamed.storageanalyzer.core.entity.EntityAnalyzer
import com.mohamed.storageanalyzer.core.entity.EntityDescriptor
import com.mohamed.storageanalyzer.core.entity.EntityInstanceKey
import com.mohamed.storageanalyzer.core.identity.ProviderId
import com.mohamed.storageanalyzer.core.identity.ResourceIdentity
import com.mohamed.storageanalyzer.core.measurement.ByteCount
import com.mohamed.storageanalyzer.core.provider.StorageProvider
import com.mohamed.storageanalyzer.core.provider.StorageProviderRegistry
import com.mohamed.storageanalyzer.core.result.DiagnosticSeverity
import com.mohamed.storageanalyzer.core.result.EntityReport
import com.mohamed.storageanalyzer.core.result.GlobalReport
import com.mohamed.storageanalyzer.core.result.StorageDiagnostic
import com.mohamed.storageanalyzer.core.result.StorageError
import com.mohamed.storageanalyzer.core.result.StorageMeasurement
import com.mohamed.storageanalyzer.core.snapshot.StorageSnapshot
import java.time.Instant

/**
 * The library facade: registers [StorageProvider]s and offers the
 * stable, high-level analytics entry points.
 *
 * Responsibilities:
 * - deterministic orchestration over providers;
 * - resource identity conflict handling;
 * - partial-result composition;
 * - honest reporting of unmeasured metrics and budget stops.
 *
 * This class is independent of storage technologies, entity frameworks and
 * DI containers: construct it with exactly the providers and entity
 * descriptors relevant to the application.
 */
class StorageAnalyzer(
    private val config: AnalyzerConfig = AnalyzerConfig(),
    providers: Iterable<StorageProvider> = emptyList(),
    /** Optional measurement cache; the default is the honest "no caching" cache. */
    val measurementCache: MeasurementCache = NullMeasurementCache(),
) : AutoCloseable {

    private val registry = StorageProviderRegistry()
    private val entityDescriptors = LinkedHashMap<String, EntityDescriptor>()

    constructor(providers: Iterable<StorageProvider>) : this(AnalyzerConfig(), providers)

    init {
        for (p in providers) registry.register(p)
    }

    // ------------------------------------------------------------- registration

    fun register(provider: StorageProvider) = registry.register(provider)

    fun unregisterProvider(providerId: ProviderId) = registry.unregister(providerId.value)

    /** Registers (or replaces) one entity descriptor. */
    fun registerEntity(descriptor: EntityDescriptor) {
        entityDescriptors[descriptor.entityTypeId] = descriptor
    }

    fun registeredEntities(): List<EntityDescriptor> = entityDescriptors.values.toList()

    fun providers(): List<StorageProvider> = registry.all()

    // ------------------------------------------------------------- global runs

    /**
     * Inventory + aggregation across registered providers with
     * deduplication, shared-resource accounting and reconciliation.
     */
    fun analyzeGlobal(
        options: AnalysisOptions = AnalysisOptions.DEFAULT,
        context: DefaultAnalysisContext = AnalysisContext.create(options.budget),
    ): GlobalReport = GlobalReportBuilder(
        providers = activeProviders(contexted(options)),
        config = config,
        options = contexted(options),
        policy = options.accountingPolicy,
        ctx = context,
    ).build()

    /** Creates a snapshot in one call; persistence is delegated to consumers. */
    fun captureSnapshot(
        options: AnalysisOptions = AnalysisOptions.DEFAULT,
        context: DefaultAnalysisContext = AnalysisContext.create(options.budget),
        includeResourceDetails: Boolean = true,
    ): StorageSnapshot {
        val report = analyzeGlobal(contexted(options), context)
        val footprintMetric = config.footprintMetric
        return StorageSnapshot(
            capturedAt = Instant.now(),
            footprintMetric = footprintMetric,
            footprintTotal = report.totals.uniqueFootprint,
            byMetric = report.totals.byMetric,
            byProvider = report.byProvider.mapNotNull { b ->
                val providerId = report.providers.firstOrNull { it.providerId.value == b.key }?.providerId
                val bytes = b[footprintMetric]
                if (providerId != null && bytes != null) providerId to bytes else null
            }.toMap(),
            byCategory = report.byCategory.mapNotNull { entry ->
                val category = runCatching { com.mohamed.storageanalyzer.core.resource.StorageCategory.valueOf(entry.key) }.getOrNull()
                val bytes = entry[footprintMetric]
                if (category != null && bytes != null) category to bytes else null
            }.toMap(),
            byResource = if (includeResourceDetails) {
                report.resourceSummaries.associate { s ->
                    s.identity.stableKey to (s.footPrintMetrics[footprintMetric] ?: s.footPrintMetrics.values.firstOrNull() ?: ByteCount.ZERO)
                }
            } else emptyMap(),
            resourceCount = report.resourceCount,
            completenessLabel = report.completeness.name,
        )
    }

    // ------------------------------------------------------------ measurements

    /** Measures one concrete resource via its owning provider. */
    fun analyzeResource(
        identity: ResourceIdentity,
        options: AnalysisOptions = AnalysisOptions.DEFAULT,
        context: DefaultAnalysisContext = AnalysisContext.create(options.budget),
    ): StorageMeasurement {
        val provider = registry.first(identity.providerId)
            ?: throw StorageError.ProviderUnavailable(identity.providerId.value, "provider not registered")
        return provider.measure(identity, contexted(options).metrics, context)
    }

    // ------------------------------------------------------------ entity runs

    /** Single-entity instance analysis. */
    fun analyzeEntity(
        descriptor: EntityDescriptor,
        instanceKey: EntityInstanceKey,
        options: AnalysisOptions = AnalysisOptions.DEFAULT,
        context: DefaultAnalysisContext = AnalysisContext.create(options.budget),
    ): EntityReport = withEntityEngine(descriptor, contexted(options), context).analyze(instanceKey, descriptor)

    /** Aggregates every instance of one descriptor (aggregate views). */
    fun analyzeEntityType(
        descriptor: EntityDescriptor,
        options: AnalysisOptions = AnalysisOptions.DEFAULT,
        context: DefaultAnalysisContext = AnalysisContext.create(options.budget),
    ): EntityReport = withEntityEngine(descriptor, contexted(options), context).analyzeAll(descriptor)

    // ------------------------------------------------------------ diagnostics

    /** Runs provider health checks plus global registration checks. */
    fun runDiagnostics(
        context: DefaultAnalysisContext = AnalysisContext.create(AnalysisOptions.DEFAULT.budget),
    ): List<StorageDiagnostic> {
        val out = mutableListOf<StorageDiagnostic>()
        if (registry.size() == 0) {
            out += StorageDiagnostic(
                code = "general.no_providers",
                severity = DiagnosticSeverity.WARNING,
                message = "no StorageProvider registered: nothing to analyze",
            )
        }
        for (p in registry.all()) {
            try {
                out += p.healthCheck(context)
            } catch (e: Exception) {
                out += StorageDiagnostic(
                    code = "general.health_check_failed",
                    severity = DiagnosticSeverity.ERROR,
                    message = "provider '${p.providerId.value}' health check failed: " +
                        (e.message ?: e.javaClass.simpleName),
                )
            }
        }
        for (descriptor in entityDescriptors.values) {
            if (registry.first(descriptor.providerId) == null) {
                out += StorageDiagnostic(
                    code = "general.descriptor_provider_missing",
                    severity = DiagnosticSeverity.WARNING,
                    message = "entity '${descriptor.entityTypeId}' references unregistered provider '${descriptor.providerId.value}'",
                )
            }
        }
        return out
    }

    // ---------------------------------------------------------------- helpers

    /** Applies analyzer defaults to run options (metric defaults, redaction flag). */
    private fun contexted(options: AnalysisOptions): AnalysisOptions = options.copy(
        metrics = options.metrics.ifEmpty { providers().flatMap { it.defaultMetricTypes() }.toSet() },
        redactLabels = options.redactLabels || config.redactLabels,
    )

    private fun activeProviders(options: AnalysisOptions): List<StorageProvider> {
        val all = registry.all()
        return options.providerFilter?.let { f -> all.filter { it.providerId == f } } ?: all
    }

    private fun withEntityEngine(
        descriptor: EntityDescriptor,
        options: AnalysisOptions,
        context: DefaultAnalysisContext,
    ): EntityAnalyzer {
        val developerCheck = registry.first(descriptor.providerId)
            ?: throw StorageError.ProviderUnavailable(descriptor.providerId.value, "provider of entity '${descriptor.entityTypeId}' not registered")
        registerEntity(descriptor)
        return EntityAnalyzer(
            providers = activeProviders(options),
            config = config,
            options = options,
            policy = options.accountingPolicy,
            ctx = context,
        )
    }

    /** Convenience: attach a progress listener to the context (builder style). */
    fun onProgress(context: DefaultAnalysisContext, block: (AnalysisProgress) -> Unit): StorageAnalyzer {
        context.onProgress(block)
        return this
    }

    override fun close() {
        registry.closeAll()
    }
}
