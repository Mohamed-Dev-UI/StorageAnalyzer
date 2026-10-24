package com.mohamed.dev.ui.storageanalyzer.data.provider

import com.mohamed.dev.ui.storageanalyzer.data.preferences.ShowcasePreferences
import com.mohamed.storageanalyzer.core.capability.Capabilities
import com.mohamed.storageanalyzer.core.capability.StorageCapability
import com.mohamed.storageanalyzer.core.config.AnalysisContext
import com.mohamed.storageanalyzer.core.identity.IdentityStability
import com.mohamed.storageanalyzer.core.identity.ProviderId
import com.mohamed.storageanalyzer.core.identity.ResourceIdentity
import com.mohamed.storageanalyzer.core.identity.ResourceKind
import com.mohamed.storageanalyzer.core.identity.StorageSystemId
import com.mohamed.storageanalyzer.core.measurement.MeasurementAccuracy
import com.mohamed.storageanalyzer.core.measurement.MeasurementMethod
import com.mohamed.storageanalyzer.core.measurement.MeasurementProvenance
import com.mohamed.storageanalyzer.core.measurement.MeasurementState
import com.mohamed.storageanalyzer.core.measurement.MetricType
import com.mohamed.storageanalyzer.core.measurement.SingleMeasurement
import com.mohamed.storageanalyzer.core.provider.StorageProvider
import com.mohamed.storageanalyzer.core.provider.StorageSystemRef
import com.mohamed.storageanalyzer.core.resource.OwnerRef
import com.mohamed.storageanalyzer.core.resource.StorageCategory
import com.mohamed.storageanalyzer.core.resource.StorageResource
import com.mohamed.storageanalyzer.core.resource.StorageScope
import com.mohamed.storageanalyzer.core.result.Completeness
import com.mohamed.storageanalyzer.core.result.DiagnosticSeverity
import com.mohamed.storageanalyzer.core.result.StorageDiagnostic
import com.mohamed.storageanalyzer.core.result.StorageMeasurement
import java.time.Instant

/**
 * Custom provider SPI sample: measures SharedPreferences entries as UTF-8
 * `key=value` payloads. It is deliberately honest — only the encoded content
 * metric is available; everything else is UNAVAILABLE with a reason.
 *
 * `feature_flags` is declared with two SCOPE owners so the accounting-policy
 * lab can show shared-resource allocation.
 */
class ShowcasePreferencesProvider(
    private val entries: () -> Map<String, String>,
) : StorageProvider {

    override val providerId: ProviderId = ProviderId(PROVIDER_ID)

    private val systemId = StorageSystemId("showcase/preferences")

    private val method = MeasurementMethod.custom("x_showcase_prefs.utf8_entry")

    override fun describe(): String = "Showcase SharedPreferences provider (UTF-8 key=value accounting)"

    override fun capabilities(): Capabilities = Capabilities(
        supported = setOf(
            StorageCapability.KEY_VALUE_ANALYTICS,
            StorageCapability.SERIALIZED_SIZE,
        ),
        unsupportedReasons = mapOf(
            StorageCapability.VOLUME_SPACE_STATS to "SharedPreferences does not expose a volume or quota",
            StorageCapability.DB_FILES to "entries live inside an XML file managed by the platform",
            StorageCapability.OBJECT_STORAGE_ANALYTICS to "values are local preference entries, not objects",
        ),
    )

    override fun capabilitiesFor(systemId: StorageSystemId): Capabilities = capabilities()

    override fun discoverSystems(): List<StorageSystemRef> = listOf(
        StorageSystemRef(
            systemId = systemId,
            label = "showcase_prefs",
            kind = ResourceKind.KEY_VALUE_ENTRY,
            capabilities = capabilities(),
        ),
    )

    override fun discoverResources(
        systemId: StorageSystemId?,
        ctx: AnalysisContext,
    ): Sequence<StorageResource> = sequence {
        if (systemId != null && systemId != this@ShowcasePreferencesProvider.systemId) return@sequence
        for ((key, _) in entries().toSortedMap()) {
            ctx.ensureActive()
            yield(
                StorageResource(
                    identity = identityOf(key),
                    providerId = providerId,
                    systemId = this@ShowcasePreferencesProvider.systemId,
                    label = key,
                    kind = ResourceKind.KEY_VALUE_ENTRY,
                    category = StorageCategory.OTHER,
                    identityStability = IdentityStability.STABLE,
                    owners = ownersOf(key),
                ),
            )
        }
    }

    override fun measure(
        resource: ResourceIdentity,
        metrics: Set<MetricType>,
        ctx: AnalysisContext,
    ): StorageMeasurement {
        ctx.ensureActive()
        val now = Instant.now()
        val requested = metrics.ifEmpty { defaultMetricTypes() }
        val key = resource.nativeId
        val value = entries()[key]
        if (value == null) {
            return StorageMeasurement(
                identity = resource,
                kind = ResourceKind.KEY_VALUE_ENTRY,
                category = StorageCategory.OTHER,
                label = key,
                measurements = requested.associateWith { metric ->
                    unavailable(metric, now, "the preference entry was removed after discovery")
                },
                owners = ownersOf(key),
                completeness = Completeness.PARTIAL,
                measuredAt = now,
            )
        }

        val bytes = (key.length + 1 + value.length).toLong()
        val provenance = MeasurementProvenance(
            method = method,
            accuracy = MeasurementAccuracy.CONVENTION,
            measuredAt = now,
            providerId = providerId.value,
            backend = "SharedPreferences XML",
            limitations = setOf("x_showcase_prefs.excludes_xml_overhead"),
            note = "UTF-8 byte length of the key and value; XML envelope bytes are not attributed",
        )
        val measurements = LinkedHashMap<MetricType, SingleMeasurement>()
        for (metric in requested) {
            measurements[metric] = when (metric) {
                MetricType.STORED_CONTENT_SIZE, MetricType.ENCODED_CONTENT_SIZE ->
                    SingleMeasurement(
                        metric = metric,
                        value = com.mohamed.storageanalyzer.core.measurement.ByteCount.of(bytes),
                        state = MeasurementState.MEASURED,
                        provenance = provenance,
                    )
                else -> unavailable(metric, now, "this provider only measures the encoded key=value payload")
            }
        }

        return StorageMeasurement(
            identity = resource,
            kind = ResourceKind.KEY_VALUE_ENTRY,
            category = StorageCategory.OTHER,
            scope = StorageScope(id = "preferences", label = "SharedPreferences"),
            label = key,
            measurements = measurements,
            owners = ownersOf(key),
            completeness = Completeness.COMPLETE,
            measuredAt = now,
        )
    }

    override fun healthCheck(ctx: AnalysisContext): List<StorageDiagnostic> =
        if (entries().isEmpty()) {
            listOf(
                StorageDiagnostic(
                    code = "x_showcase_prefs.empty",
                    severity = DiagnosticSeverity.WARNING,
                    message = "no SharedPreferences entries are available to measure",
                    providerId = providerId,
                ),
            )
        } else {
            emptyList()
        }

    override fun defaultMetricTypes(): Set<MetricType> = setOf(
        MetricType.STORED_CONTENT_SIZE,
        MetricType.ENCODED_CONTENT_SIZE,
    )

    override fun close() = Unit

    private fun identityOf(key: String): ResourceIdentity = ResourceIdentity(
        providerId = providerId,
        systemId = systemId,
        kind = ResourceKind.KEY_VALUE_ENTRY,
        nativeId = key,
        stability = IdentityStability.STABLE,
    )

    private fun ownersOf(key: String): List<OwnerRef> = if (key == ShowcasePreferences.SHARED_ENTRY) {
        listOf(
            OwnerRef(id = "scope:onboarding", kind = OwnerRef.OwnerKind.SCOPE),
            OwnerRef(id = "scope:sync", kind = OwnerRef.OwnerKind.SCOPE),
        )
    } else {
        listOf(OwnerRef(id = "scope:preferences", kind = OwnerRef.OwnerKind.SCOPE))
    }

    private fun unavailable(metric: MetricType, now: Instant, reason: String): SingleMeasurement =
        SingleMeasurement(
            metric = metric,
            value = null,
            state = MeasurementState.UNAVAILABLE,
            provenance = MeasurementProvenance.absent(
                providerId = providerId.value,
                measuredAt = now,
                method = method,
                reason = reason,
            ),
            reason = reason,
        )

    private companion object {
        const val PROVIDER_ID = "x_showcase_prefs"
    }
}
