package com.mohamed.storageanalyzer.core.entity.spi

import com.mohamed.storageanalyzer.core.config.AnalysisContext
import com.mohamed.storageanalyzer.core.entity.EntityDescriptor
import com.mohamed.storageanalyzer.core.identity.ResourceIdentity
import com.mohamed.storageanalyzer.core.measurement.MeasurementProvenance
import com.mohamed.storageanalyzer.core.measurement.MetricType
import com.mohamed.storageanalyzer.core.measurement.MeasurementState
import com.mohamed.storageanalyzer.core.measurement.ByteCount
import com.mohamed.storageanalyzer.core.result.FieldMeasurement
import com.mohamed.storageanalyzer.core.result.RecordBreakdown

/**
 * SPI implemented by providers that can decompose a record/entity into
 * field-level storage contributions.
 *
 * Implementations must be read-only and must not retain user payload data.
 * Unknown contributions must be reported via [RecordBreakdown.unknown], never
 * folded into an attribute.
 */
interface RecordAnalyticsEngine {

    /**
     * Encoded-size breakdown of one record. Returns `null` when the resource
     * isn't a record this engine decomposes.
     */
    fun fieldBreakdown(resource: ResourceIdentity, ctx: AnalysisContext): RecordBreakdown?

    /**
     * Aggregate encoded sizes per column for a collection of records
     * (table/list). Returns null when unsupported; the analyzer then falls
     * back per record.
     */
    fun aggregateFieldBreakdown(
        collectionKey: CollectionKey,
        ctx: AnalysisContext,
    ): List<AggregatedField> = emptyList()
}

/** Identifies a collection of records to aggregate per-field sizes on. */
data class CollectionKey(
    val resourceIdentity: ResourceIdentity,
    /** Row predicate / limit serialized; provider-specific (must remain stable). */
    val predicate: String? = null,
)

/** Per-field aggregate encoded size of a set of records. */
data class AggregatedField(
    val attributeName: String,
    val metric: MetricType,
    val totalAcrossRecords: ByteCount,
    /** Number of records where the attribute contributes bytes (NULL excluded). */
    val populatedRecords: Long,
    val provenance: MeasurementProvenance,
)

/** Convenience: measurement for an unmeasurable attribute. */
fun unmeasuredField(name: String, metric: MetricType, provenance: MeasurementProvenance, reason: String) =
    FieldMeasurement(name, metric, null, MeasurementState.UNAVAILABLE, provenance, reason)
