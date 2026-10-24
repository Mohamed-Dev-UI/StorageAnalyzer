package com.mohamed.storageanalyzer.history

import com.mohamed.storageanalyzer.core.measurement.ByteCount
import com.mohamed.storageanalyzer.core.measurement.MeasurementAccuracy
import com.mohamed.storageanalyzer.core.measurement.MetricType
import com.mohamed.storageanalyzer.core.resource.StorageCategory
import com.mohamed.storageanalyzer.core.snapshot.StorageSnapshot
import com.mohamed.storageanalyzer.core.snapshot.hasResourceDetails
import java.time.Duration
import java.time.Instant

/**
 * Signed byte delta (deltas may be negative — shrinking storage is a normal
 * outcome — while [ByteCount] itself stays non-negative everywhere else).
 */
data class MetricDelta(
    val metric: MetricType,
    val fromBytes: ByteCount,
    val toBytes: ByteCount,
    /** Signed difference `to - from`; negative when storage shrinks. */
    val deltaBytes: Long,
    /**
     * Growth fraction `delta / from`, or `null` when the "from" value is zero
     * (a change from zero has no meaningful percentage).
     */
    val growthFraction: Double?,
)

/** Per-resource growth classification when both snapshots carry details. */
data class ResourceAttribution(
    /** Resources present only in the later snapshot, with their bytes. */
    val created: Map<String, ByteCount>,
    /** Resources present only in the earlier snapshot, with their bytes. */
    val removed: Map<String, ByteCount>,
    /** Resources present in both; signed byte change. */
    val changed: Map<String, Long>,
    /** Resources byte-identical in both snapshots. */
    val unchangedCount: Int,
) {
    /** Largest positive changes, descending by bytes. */
    fun topChanges(limit: Int = 10): List<Pair<String, Long>> =
        changed.entries.sortedByDescending { it.value }.take(limit).map { it.key to it.value }
}

/**
 * Result of comparing two snapshots.
 *
 * Honesty rules:
 * - Metrics present in only one of the two snapshots are **not** treated as
 *   zero growth; they are reported in [notComparableMetrics] with a reason.
 * - The comparison's [accuracy] is EXACT only when both runs were COMPLETE;
 *   otherwise the comparison speaks about the covered subset only (PARTIAL /
 *   UNKNOWN carried over from the underlying runs).
 * - Growth rates require strictly increasing timestamps and a non-zero
 *   baseline; when undefined they are `null`, never zero.
 */
data class SnapshotComparison(
    val from: StorageSnapshot,
    val to: StorageSnapshot,
    val accuracy: MeasurementAccuracy,
    /** Signed footprint delta `to - from` against [StorageSnapshot.footprintTotal]s. */
    val footprintDeltaBytes: Long,
    val footprintGrowthFraction: Double?,
    /** Mean growth per second, or null when both timestamps are identical. */
    val growthBytesPerSecond: Double?,
    val metricDeltas: Map<MetricType, MetricDelta>,
    val notComparableMetrics: Map<MetricType, String>,
    /** Signed per-category deltas (categories missing on both sides disappear; on one side include null-from/to handling). */
    val categoryDeltas: Map<StorageCategory, Long>,
    /** Per-resource attribution, or null when either snapshot lacks details. */
    val attribution: ResourceAttribution?,
) {
    val fromCapturedAt: Instant get() = from.capturedAt
    val toCapturedAt: Instant get() = to.capturedAt

    companion object {
        /**
         * Compares the earlier snapshot [from] with the later snapshot [to].
         * Timestamps are not required to be ordered by callers (the exception
         * message points it out) but a comparison where `to` precedes `from`
         * in time is refused — swap the arguments.
         */
        fun between(from: StorageSnapshot, to: StorageSnapshot): SnapshotComparison {
            if (to.capturedAt.isBefore(from.capturedAt)) {
                throw IllegalArgumentException(
                    "snapshot comparison needs `to` at or after `from` (from=${from.capturedAt}, to=${to.capturedAt}); swap the arguments",
                )
            }

            val accuracy = when {
                from.completenessLabel == "COMPLETE" && to.completenessLabel == "COMPLETE" -> MeasurementAccuracy.EXACT
                from.completenessLabel.startsWith("PARTIAL") || to.completenessLabel.startsWith("PARTIAL") -> MeasurementAccuracy.PARTIAL
                else -> MeasurementAccuracy.UNKNOWN
            }

            val footprintDelta = to.footprintTotal.value - from.footprintTotal.value
            val footprintFraction = growthFraction(from.footprintTotal.value, to.footprintTotal.value)
            val seconds = Duration.between(from.capturedAt, to.capturedAt).toMillis() / 1000.0
            val growthPerSecond =
                if (seconds > 0.0) footprintDelta / seconds else null

            // Metric deltas: only for metrics present on BOTH sides — a metric
            // missing on one side is never treated as zero growth.
            val metricDeltas = LinkedHashMap<MetricType, MetricDelta>()
            val notComparable = LinkedHashMap<MetricType, String>()
            val allMetrics = from.byMetric.keys + to.byMetric.keys
            for (metric in allMetrics) {
                val fromV = from.byMetric[metric]
                val toV = to.byMetric[metric]
                when {
                    fromV != null && toV != null -> metricDeltas[metric] = MetricDelta(
                        metric = metric,
                        fromBytes = fromV,
                        toBytes = toV,
                        deltaBytes = toV.value - fromV.value,
                        growthFraction = growthFraction(fromV.value, toV.value),
                    )
                    fromV == null -> notComparable[metric] = "metric '${metric.id}' is absent from the earlier snapshot"
                    else -> notComparable[metric] = "metric '${metric.id}' is absent from the later snapshot"
                }
            }

            // Category deltas: entries present on either side; a category missing
            // on one side legitimately changes from nothing to its value. Missing
            // bytes are zero here — the metric-level map above carries the
            // honesty annotations; categories are presentation summaries.
            val categoryDeltas = LinkedHashMap<StorageCategory, Long>()
            val allCategories = from.byCategory.keys + to.byCategory.keys
            for (category in allCategories) {
                val fromV = from.byCategory[category]?.value ?: 0L
                val toV = to.byCategory[category]?.value ?: 0L
                categoryDeltas[category] = toV - fromV
            }

            val attribution =
                if (from.hasResourceDetails && to.hasResourceDetails) {
                    val created = to.byResource.filterKeys { it !in from.byResource }
                    val removed = from.byResource.filterKeys { it !in to.byResource }
                    val changed = LinkedHashMap<String, Long>()
                    var unchanged = 0
                    for ((key, fromBytes) in from.byResource) {
                        val toBytes = to.byResource[key] ?: continue // not in both
                        if (toBytes.value == fromBytes.value) unchanged++
                        else changed[key] = toBytes.value - fromBytes.value
                    }
                    ResourceAttribution(created, removed, changed, unchanged)
                } else {
                    null
                }

            return SnapshotComparison(
                from = from,
                to = to,
                accuracy = accuracy,
                footprintDeltaBytes = footprintDelta,
                footprintGrowthFraction = footprintFraction,
                growthBytesPerSecond = growthPerSecond,
                metricDeltas = metricDeltas,
                notComparableMetrics = notComparable,
                categoryDeltas = categoryDeltas,
                attribution = attribution,
            )
        }

        private fun growthFraction(fromValue: Long, toValue: Long): Double? =
            if (fromValue == 0L) null else (toValue - fromValue).toDouble() / fromValue.toDouble()
    }
}
