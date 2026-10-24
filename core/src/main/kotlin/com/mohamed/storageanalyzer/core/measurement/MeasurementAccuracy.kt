package com.mohamed.storageanalyzer.core.measurement

/**
 * How a measured value was obtained. Every measurement carries exactly one
 * accuracy class — never silently replace an unavailable measurement with
 * zero, and never present an allocation policy as a direct physical
 * measurement.
 */
enum class MeasurementAccuracy {
    /**
     * Directly measured by the storage technology with an exact guarantee
     * (e.g. file length, `PRAGMA page_count`, dbstat page sums).
     */
    EXACT,

    /**
     * Measured according to a **defined accounting convention** (documented in
     * `docs/MEASUREMENT_ACCURACY.md`), e.g. the SQLite record-format encoding
     * size of a row — exact with respect to that convention, but excluding
     * per-cell/page overhead.
     */
    CONVENTION,

    /**
     * Point estimate based on a documented model (e.g. proportional allocation
     * of table pages across rows). Carries assumptions; check the provenance.
     */
    ESTIMATED,

    /**
     * Measurement covers only part of the requested scope (e.g. traversal hit
     * the depth limit). The value is about the covered subset only.
     */
    PARTIAL,

    /** Not measurable through supported APIs. Reason carried by the state. */
    UNAVAILABLE,

    /** Not yet measured (the metric is reported but the run does not cover it). */
    UNKNOWN
}
