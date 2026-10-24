package com.mohamed.dev.ui.storageanalyzer.data.provider

import com.mohamed.storageanalyzer.core.capability.Capabilities
import com.mohamed.storageanalyzer.core.capability.StorageCapability
import com.mohamed.storageanalyzer.core.config.AnalysisContext
import com.mohamed.storageanalyzer.core.identity.IdentityStability
import com.mohamed.storageanalyzer.core.identity.PathIdentity
import com.mohamed.storageanalyzer.core.identity.ProviderId
import com.mohamed.storageanalyzer.core.identity.ResourceIdentity
import com.mohamed.storageanalyzer.core.identity.ResourceKind
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
import com.mohamed.storageanalyzer.core.resource.StorageCategory
import com.mohamed.storageanalyzer.core.resource.StorageResource
import com.mohamed.storageanalyzer.core.result.Completeness
import com.mohamed.storageanalyzer.core.result.DiagnosticSeverity
import com.mohamed.storageanalyzer.core.result.StorageDiagnostic
import com.mohamed.storageanalyzer.core.result.StorageMeasurement
import java.io.File
import java.time.Instant

/**
 * Teaching provider that reveals the deduplication mechanism: it catalogs the
 * same physical ZIP that `FilesystemProvider` already discovered and declares
 * the filesystem identity through `alsoDiscoverableBy`.
 *
 * `StorageProviderRegistry` orders providers by id, so `filesystem` composes
 * the inventory first and this provider's entry is reported as an overlap
 * instead of inflating the unique footprint.
 */
class ArchiveCatalogProvider(
    private val archive: File,
) : StorageProvider {

    override val providerId: ProviderId = ProviderId(PROVIDER_ID)

    private val systemId = StorageSystemId("catalog/legacy")
    private val nativeId = "legacy-backup.zip"
    private val method = MeasurementMethod.custom("x_archive_catalog.length")

    override fun describe(): String = "Archive catalog (logical backup objects; aliases filesystem paths)"

    override fun capabilities(): Capabilities = Capabilities(
        supported = setOf(
            StorageCapability.OBJECT_STORAGE_ANALYTICS,
            StorageCapability.SERIALIZED_SIZE,
        ),
        unsupportedReasons = mapOf(
            StorageCapability.VOLUME_SPACE_STATS to "the catalog knows object sizes, not volume capacity",
            StorageCapability.FILE_ALLOCATED_SIZE to "allocated size must be measured by the filesystem provider",
            StorageCapability.DB_PAGE_ACCOUNTING to "archives have no page structure",
        ),
    )

    override fun capabilitiesFor(systemId: StorageSystemId): Capabilities = capabilities()

    override fun discoverResources(
        systemId: StorageSystemId?,
        ctx: AnalysisContext,
    ): Sequence<StorageResource> = sequence {
        if (systemId != null && systemId != this@ArchiveCatalogProvider.systemId) return@sequence
        if (!archive.isFile) return@sequence
        ctx.ensureActive()
        val identity = identity()
        yield(
            StorageResource(
                identity = identity,
                providerId = providerId,
                systemId = this@ArchiveCatalogProvider.systemId,
                label = nativeId,
                kind = ResourceKind.ARCHIVE,
                category = StorageCategory.ARCHIVE,
                identityStability = IdentityStability.STABLE,
                owners = listOf(OwnerRef("catalog:legacy", OwnerRef.OwnerKind.SCOPE)),
                alsoDiscoverableBy = setOf(PathIdentity.forPath(archive.path, ResourceKind.FILE)),
            ),
        )
    }

    override fun measure(
        resource: ResourceIdentity,
        metrics: Set<MetricType>,
        ctx: AnalysisContext,
    ): StorageMeasurement {
        ctx.ensureActive()
        val now = Instant.now()
        val requested = metrics.ifEmpty { defaultMetricTypes() }
        val length = archive.takeIf { it.isFile }?.length()
        val measurements = LinkedHashMap<MetricType, SingleMeasurement>()
        for (metric in requested) {
            measurements[metric] = when {
                length == null -> SingleMeasurement(
                    metric = metric,
                    value = null,
                    state = MeasurementState.UNAVAILABLE,
                    provenance = MeasurementProvenance.absent(
                        providerId = providerId.value,
                        measuredAt = now,
                        method = method,
                        reason = "the catalogued archive no longer exists",
                    ),
                    reason = "the catalogued archive no longer exists",
                )
                metric == MetricType.STORED_CONTENT_SIZE || metric == MetricType.ARCHIVE_STORAGE ->
                    SingleMeasurement(
                        metric = metric,
                        value = ByteCount.of(length),
                        state = MeasurementState.MEASURED,
                        provenance = MeasurementProvenance(
                            method = method,
                            accuracy = MeasurementAccuracy.EXACT,
                            measuredAt = now,
                            providerId = providerId.value,
                            backend = "java.io.File.length",
                            note = "catalog entry points at ${archive.name}",
                        ),
                    )
                else -> SingleMeasurement(
                    metric = metric,
                    value = null,
                    state = MeasurementState.UNAVAILABLE,
                    provenance = MeasurementProvenance.absent(
                        providerId = providerId.value,
                        measuredAt = now,
                        method = method,
                        reason = "the catalog only tracks archive payload size",
                    ),
                    reason = "the catalog only tracks archive payload size",
                )
            }
        }
        return StorageMeasurement(
            identity = resource,
            kind = ResourceKind.ARCHIVE,
            category = StorageCategory.ARCHIVE,
            label = "$nativeId (catalog entry)",
            measurements = measurements,
            owners = listOf(OwnerRef("catalog:legacy", OwnerRef.OwnerKind.SCOPE)),
            completeness = if (length == null) Completeness.PARTIAL else Completeness.COMPLETE,
            measuredAt = now,
        )
    }

    override fun healthCheck(ctx: AnalysisContext): List<StorageDiagnostic> =
        if (!archive.isFile) {
            listOf(
                StorageDiagnostic(
                    code = "x_archive_catalog.missing_archive",
                    severity = DiagnosticSeverity.WARNING,
                    message = "catalogued archive '${archive.path}' is missing; the demo seeder recreates it",
                    providerId = providerId,
                ),
            )
        } else {
            emptyList()
        }

    override fun defaultMetricTypes(): Set<MetricType> = setOf(MetricType.STORED_CONTENT_SIZE)

    override fun close() = Unit

    private fun identity(): ResourceIdentity = ResourceIdentity(
        providerId = providerId,
        systemId = systemId,
        kind = ResourceKind.ARCHIVE,
        nativeId = nativeId,
        namespace = "legacy-catalog",
        stability = IdentityStability.STABLE,
    )

    private companion object {
        const val PROVIDER_ID = "x_archive_catalog"
    }
}
