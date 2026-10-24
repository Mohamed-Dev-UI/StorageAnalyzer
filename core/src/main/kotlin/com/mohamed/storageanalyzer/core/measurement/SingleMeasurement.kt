package com.mohamed.storageanalyzer.core.measurement

/** Lifecycle state of a single [SingleMeasurement]. */
enum class MeasurementState {
    /** `value` is populated and the provenance describes how it was obtained. */
    MEASURED,

    /** The requested metric is not obtainable from this resource; reason set. */
    UNAVAILABLE,

    /** Metric not applicable for this resource kind (e.g. index size of a file). */
    NOT_APPLICABLE,

    /** Metric not yet measured in this run (optionally enabled later). */
    NOT_YET_MEASURED,
}

/**
 * One measured value of [metric] for one resource, with full provenance.
 *
 * **Invariant:** exactly one of `value`/`state` semantics holds —
 * - `state == MEASURED` requires a non-null `value` whose provenance accuracy
 *   is not `UNAVAILABLE`/`UNKNOWN` and whose method is set,
 * - `state != MEASURED` requires `value == null` and a non-null [reason].
 *
 * There is **no implicit zero**: an unknown size is represented as an explicit
 * state, never as `0`.
 */
data class SingleMeasurement(
    val metric: MetricType,
    val value: ByteCount?,
    val state: MeasurementState,
    val provenance: MeasurementProvenance,
    val reason: String? = null,
) {

    init {
        if (state == MeasurementState.MEASURED) {
            require(value != null) { "MEASURED measurement of ${metric.id} requires a value" }
            require(provenance.accuracy != MeasurementAccuracy.UNAVAILABLE) {
                "MEASURED measurement of ${metric.id} must not carry UNAVAILABLE accuracy"
            }
            require(provenance.accuracy != MeasurementAccuracy.UNKNOWN) {
                "MEASURED measurement of ${metric.id} must not carry UNKNOWN accuracy"
            }
        } else {
            require(value == null) { "non-MEASURED measurement of ${metric.id} must have no value" }
            require(reason != null) { "non-MEASURED measurement of ${metric.id} requires a reason" }
        }
    }

    companion object {
        /** Convenience factory for a measured value. */
        fun measured(
            metric: MetricType,
            value: Long,
            provenance: MeasurementProvenance,
        ): SingleMeasurement = SingleMeasurement(
            metric = metric,
            value = ByteCount.of(value),
            state = MeasurementState.MEASURED,
            provenance = provenance,
        )
    }
}
