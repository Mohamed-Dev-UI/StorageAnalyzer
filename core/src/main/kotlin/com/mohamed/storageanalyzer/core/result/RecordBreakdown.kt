package com.mohamed.storageanalyzer.core.result

import com.mohamed.storageanalyzer.core.measurement.ByteCount
import com.mohamed.storageanalyzer.core.measurement.MetricType
import com.mohamed.storageanalyzer.core.measurement.MeasurementState
import com.mohamed.storageanalyzer.core.measurement.MeasurementProvenance
import java.time.Instant

/** One attribute's storage contribution inside a record/entity breakdown. */
data class FieldMeasurement(
    /** Attribute name (column name, path segment, custom strategy label). */
    val attributeName: String,
    val metric: MetricType,
    val value: ByteCount?,
    val state: MeasurementState,
    val provenance: MeasurementProvenance,
    val reason: String? = null,
)

/**
 * Field-level breakdown of a record/entity.
 *
 * `overhead` is provider-defined non-attribute storage directly attached to
 * the record (e.g. record header varints). `unknown` collects the size of
 * content the provider cannot attribute to individual attributes
 * (e.g. compressed/packed rows); it is never folded into attribute values.
 */
data class RecordBreakdown(
    val fields: List<FieldMeasurement>,
    val overhead: ByteCount? = null,
    val unknown: ByteCount? = null,

    /** Instant the breakdown was taken (needed for increment-based comparisons). */
    val measuredAt: Instant = Instant.now(),
) {
    val fieldNames: List<String> get() = fields.map { it.attributeName }
}
