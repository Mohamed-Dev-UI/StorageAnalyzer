package com.mohamed.storageanalyzer.core.result

import com.mohamed.storageanalyzer.core.identity.ResourceIdentity
import com.mohamed.storageanalyzer.core.identity.ResourceKind
import com.mohamed.storageanalyzer.core.measurement.ByteCount
import com.mohamed.storageanalyzer.core.measurement.MetricType
import com.mohamed.storageanalyzer.core.measurement.MeasurementState
import com.mohamed.storageanalyzer.core.measurement.SingleMeasurement
import com.mohamed.storageanalyzer.core.resource.OwnerRef
import com.mohamed.storageanalyzer.core.resource.StorageCategory
import com.mohamed.storageanalyzer.core.resource.StorageScope
import java.time.Instant

/** Whether an analysis covered the full requested scope. */
enum class Completeness {
    /** Everything requested was measured. */
    COMPLETE,

    /** The run was bounded (budget/cancellation/error) — subset only. */
    PARTIAL,

    /** Provider could not establish a statement about coverage. */
    UNKNOWN
}

/**
 * Complete, typed result of measuring one storage resource.
 *
 * Results never silently drop or zero-out a missing measurement: unmeasured
 * metrics are reported with an explicit state and reason
 * (see [missing]).
 */
data class StorageMeasurement(
    val identity: ResourceIdentity,
    val kind: ResourceKind,
    val category: StorageCategory,
    val scope: StorageScope? = null,

    /** Human label of a resource in reports; provider may omit it for privacy. */
    val label: String? = null,

    /** Measurements participating in this run's metric query set. */
    val measurements: Map<MetricType, SingleMeasurement>,

    /** Provider-declared owners of the resource (may be empty). */
    val owners: List<OwnerRef> = emptyList(),

    /** Optional per-field record breakdown (when the provider supports it). */
    val recordBreakdown: RecordBreakdown? = null,

    val diagnostics: List<StorageDiagnostic> = emptyList(),
    val completeness: Completeness = Completeness.COMPLETE,
    val measuredAt: Instant = Instant.now(),
) {
    /** Measured value for [metric] or null when it is not a measured value. */
    fun valueOf(metric: MetricType): ByteCount? = measurements[metric]?.let { m ->
        if (m.state == MeasurementState.MEASURED) m.value else null
    }

    /** All metrics present but not measured, with their reasons. */
    fun missing(): Map<MetricType, String> = measurements
        .filterValues { it.state != MeasurementState.MEASURED }
        .mapValues { (_, m) -> m.reason.orEmpty() }

    override fun toString(): String =
        "StorageMeasurement(${identity.stableKey}, measured=${measurements.size})"
}
