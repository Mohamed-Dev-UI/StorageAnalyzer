package com.mohamed.storageanalyzer.core.analytics

import com.mohamed.storageanalyzer.core.capability.Capabilities
import com.mohamed.storageanalyzer.core.config.AnalysisContext
import com.mohamed.storageanalyzer.core.identity.ProviderId
import com.mohamed.storageanalyzer.core.identity.ResourceIdentity
import com.mohamed.storageanalyzer.core.identity.StorageSystemId
import com.mohamed.storageanalyzer.core.measurement.ByteCount
import com.mohamed.storageanalyzer.core.measurement.MeasurementAccuracy
import com.mohamed.storageanalyzer.core.measurement.MeasurementMethod
import com.mohamed.storageanalyzer.core.measurement.MeasurementProvenance
import com.mohamed.storageanalyzer.core.measurement.MeasurementState
import com.mohamed.storageanalyzer.core.measurement.MetricType
import com.mohamed.storageanalyzer.core.measurement.SingleMeasurement
import com.mohamed.storageanalyzer.core.provider.StorageProvider
import com.mohamed.storageanalyzer.core.resource.OwnerRef
import com.mohamed.storageanalyzer.core.resource.ReferenceLink
import com.mohamed.storageanalyzer.core.resource.RelationType
import com.mohamed.storageanalyzer.core.resource.StorageCategory
import com.mohamed.storageanalyzer.core.resource.StorageResource
import com.mohamed.storageanalyzer.core.result.StorageDiagnostic
import com.mohamed.storageanalyzer.core.result.StorageMeasurement
import java.time.Instant

/**
 * Scriptable fake provider for core analytics tests: an in-memory resource
 * graph with fixed sizes, optional ownership metadata, optional
 * cross-provider duplicate declarations and optional per-resource failures.
 */
class FakeStorageProvider(
    override val providerId: ProviderId,
    private val sizes: Map<ResourceIdentity, Long>,
    private val owners: Map<ResourceIdentity, List<OwnerRef>> = emptyMap(),
    private val alsoBy: Map<ResourceIdentity, Set<ResourceIdentity>> = emptyMap(),
    private val edges: Map<ResourceIdentity, List<ResourceIdentity>> = emptyMap(),
    private val failOn: Set<ResourceIdentity> = emptySet(),
    /** Extra diagnostics returned with the measurement (for diagnostics tests). */
    private val extraDiagnostics: Map<ResourceIdentity, List<StorageDiagnostic>> = emptyMap(),
) : StorageProvider {

    private val now: Instant = Instant.parse("2026-01-02T03:04:05Z")

    override fun describe(): String = "fake provider #${providerId.value}"

    override fun capabilities(): Capabilities = Capabilities(emptySet(), emptyMap())

    override fun capabilitiesFor(systemId: StorageSystemId): Capabilities = capabilities()

    override fun discoverResources(
        systemId: StorageSystemId?,
        ctx: AnalysisContext,
    ): Sequence<StorageResource> = sizes.keys.asSequence().map { toResource(it) }

    override fun measure(
        resource: ResourceIdentity,
        metrics: Set<MetricType>,
        ctx: AnalysisContext,
    ): StorageMeasurement {
        if (resource in failOn) throw IllegalStateException("fake failure for $resource")
        val requested = metrics.ifEmpty { defaultMetricTypes() }
        val size = sizes[resource] ?: 0L
        val measurements = requested.associateWith { metric ->
            if (metric == MetricType.STORED_CONTENT_SIZE || metric == MetricType.FILE_LOGICAL_SIZE) {
                SingleMeasurement(
                    metric = metric,
                    value = ByteCount(size),
                    state = MeasurementState.MEASURED,
                    provenance = MeasurementProvenance(
                        method = MeasurementMethod.FILE_LENGTH,
                        accuracy = MeasurementAccuracy.EXACT,
                        measuredAt = now,
                        providerId = providerId.value,
                        backend = "fake",
                    ),
                )
            } else {
                SingleMeasurement(
                    metric = metric,
                    value = null,
                    state = MeasurementState.UNAVAILABLE,
                    provenance = MeasurementProvenance.absent(
                        providerId = providerId.value,
                        measuredAt = now,
                        method = MeasurementMethod.custom("x_fake.unsupported"),
                        reason = "not provided by fake",
                    ),
                    reason = "not provided by fake",
                )
            }
        }
        return StorageMeasurement(
            identity = resource,
            kind = resource.kind,
            category = StorageCategory.OTHER,
            measurements = measurements,
            owners = owners[resource] ?: emptyList(),
            diagnostics = extraDiagnostics[resource] ?: emptyList(),
        )
    }

    override fun related(resource: ResourceIdentity, ctx: AnalysisContext): List<ReferenceLink> =
        (edges[resource] ?: emptyList()).map { ReferenceLink(providerId, it, RelationType.CONTAINS) }

    override fun defaultMetricTypes(): Set<MetricType> = setOf(MetricType.STORED_CONTENT_SIZE)

    override fun close() = Unit

    private fun toResource(identity: ResourceIdentity): StorageResource = StorageResource(
        identity = identity,
        providerId = providerId,
        systemId = identity.systemId,
        kind = identity.kind,
        category = StorageCategory.OTHER,
        owners = owners[identity] ?: emptyList(),
        alsoDiscoverableBy = alsoBy[identity] ?: emptySet(),
    )
}
