package com.mohamed.storageanalyzer.history

import com.mohamed.storageanalyzer.core.snapshot.StorageSnapshot
import java.time.Duration
import java.time.Instant

/**
 * Linear growth model fitted by ordinary least squares over a chronological
 * series of snapshots. Method identity is
 * `MeasurementMethod.FORECAST_LINEAR_REGRESSION`; domain `x` is seconds since
 * the first snapshot of the window, `y` is the snapshot's footprint total.
 *
 * Honesty rules:
 * - A model exists only when there are two or more distinct timestamps; a
 *   single timestamp (or an empty series) yields [Unavailable] with a reason.
 * - Accuracy class is ESTIMATED: the model extrapolates the past, it does not
 *   measure the future. R² is reported so users can judge fit quality.
 * - Estimates may be negative once the trend line crosses zero (shrinking
 *   storage); they are NOT clamped, because a silent clamp would fabricate a
 *   non-negative "measurement" of the future.
 */
sealed interface GrowthModel {

    /** When the output value is a model estimate, never a measurement. */
    val accuracyKind: String get() = "ESTIMATED"

    /** The fitted line: `bytes(x) = interceptBytes + slopeBytesPerSecond * x`, x in seconds since [windowStart]. */
    data class Linear(
        val slopeBytesPerSecond: Double,
        val interceptBytes: Double,
        /** Coefficient of determination in [0, 1]; 1.0 for perfectly constant series. */
        val r2: Double,
        val windowStart: Instant,
        val windowEnd: Instant,
        val pointCount: Int,
    ) : GrowthModel {
        /** Model estimate of the footprint at [instant] (may be negative). */
        fun estimateAt(instant: Instant): Double =
            interceptBytes + slopeBytesPerSecond * (Duration.between(windowStart, instant).toMillis() / 1000.0)
    }

    /** No model could be fitted; reason explains why (never silent). */
    data class Unavailable(val reason: String) : GrowthModel {
        override val accuracyKind: String get() = "UNAVAILABLE"
    }
}

object GrowthModelFactory {

    /**
     * Fits a linear growth model over [snapshots]. Snapshots are sorted by
     * [StorageSnapshot.capturedAt] first; duplicates of the earliest timestamp
     * are permitted (they participate as ordinary points).
     */
    fun fit(snapshots: List<StorageSnapshot>): GrowthModel {
        if (snapshots.isEmpty()) {
            return GrowthModel.Unavailable("no snapshots available to fit a growth model")
        }
        if (snapshots.size == 1) {
            return GrowthModel.Unavailable("at least two snapshots are required; only one snapshot exists")
        }
        val sorted = snapshots.sortedBy { it.capturedAt }
        val windowStart = sorted.first().capturedAt
        val points = sorted.map { snap ->
            // seconds relative to window start as double
            val x = Duration.between(windowStart, snap.capturedAt).toMillis() / 1000.0
            val y = snap.footprintTotal.value.toDouble()
            x to y
        }

        // Distinct timestamps check (zero-x variance → no unique direction).
        val distinctStarts = points.map { it.first }.distinct()
        if (distinctStarts.size < 2) {
            return GrowthModel.Unavailable(
                "all snapshots share the timestamp ${sorted.first().capturedAt}; a time-based trend needs observations at several distinct times",
            )
        }

        val n = points.size
        val sumX = points.sumOf { it.first }
        val sumY = points.sumOf { it.second }
        val sumXY = points.sumOf { it.first * it.second }
        val sumXX = points.sumOf { it.first * it.first }
        val denominator = n * sumXX - sumX * sumX
        require(denominator > 0.0) { "degenerate least-squares denominator" }
        val slope = (n * sumXY - sumX * sumY) / denominator
        val intercept = (sumY - slope * sumX) / n

        // R^2 = 1 - SS_res / SS_tot; for a flat series SS_tot == 0 → perfect fit.
        val meanY = sumY / n
        val ssTot = points.sumOf { (it.second - meanY) * (it.second - meanY) }
        val ssRes = points.sumOf { (x, y) -> val yh = intercept + slope * x; (y - yh) * (y - yh) }
        val r2 = if (ssTot == 0.0) 1.0 else (1.0 - ssRes / ssTot).coerceIn(0.0, 1.0)

        return GrowthModel.Linear(
            slopeBytesPerSecond = slope,
            interceptBytes = intercept,
            r2 = r2,
            windowStart = windowStart,
            windowEnd = sorted.last().capturedAt,
            pointCount = n,
        )
    }
}
