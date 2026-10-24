package com.mohamed.storageanalyzer.core.measurement

import java.time.Instant

/**
 * Quantified confidence interval for an [MeasurementAccuracy.ESTIMATED] or
 * [MeasurementAccuracy.PARTIAL] measurement, when quantifiable.
 */
data class MeasurementUncertainty(
    /** Lower bound of the plausible range (inclusive). */
    val lowerInclusive: ByteCount,

    /** Upper bound of the plausible range (inclusive). */
    val upperInclusive: ByteCount,
) {
    init {
        require(lowerInclusive <= upperInclusive) { "lower bound $lowerInclusive > upper bound $upperInclusive" }
    }
}

/**
 * Where a value comes from, and under which rules it was produced.
 *
 * Every [com.mohamed.storageanalyzer.core.result.SingleMeasurement] carries one
 * provenance entry so every number in every report can be audited.
 */
data class MeasurementProvenance(
    /** Method that produced the value (e.g. `sqlite.dbstat_btree_sum`). */
    val method: MeasurementMethod,

    /** Accuracy classification of the value or the unavailable state. */
    val accuracy: MeasurementAccuracy,

    /** When the measurement was taken. */
    val measuredAt: Instant,

    /** Id of the measuring provider, e.g. `filesystem`. */
    val providerId: String,

    /** Backend/version string, e.g. `jdbc:sqlite driver 3.41.2.2`. */
    val backend: String? = null,

    /**
     * Stable limitation tokens (documented in `docs/MEASUREMENT_ACCURACY.md`),
     * e.g. `sqlite.excludes.cell.overhead`.
     */
    val limitations: Set<String> = emptySet(),

    /** Non-fatal warnings observed while measuring (stable tokens or messages). */
    val warnings: Set<String> = emptySet(),

    /** Known numeric uncertainty, if quantifiable. */
    val uncertainty: MeasurementUncertainty? = null,

    /** Free-form, short, human-readable clarification (never user payload). */
    val note: String? = null,
) {
    companion object {
        /**
         * Provenance for a value that is *unavailable* or *unknown*, recording
         * why, so reporting code never has to invent its own placeholders.
         */
        fun absent(
            providerId: String,
            measuredAt: Instant,
            method: MeasurementMethod,
            accuracy: MeasurementAccuracy = MeasurementAccuracy.UNAVAILABLE,
            reason: String,
        ): MeasurementProvenance = MeasurementProvenance(
            method = method,
            accuracy = accuracy,
            measuredAt = measuredAt,
            providerId = providerId,
            note = reason,
        )
    }
}
