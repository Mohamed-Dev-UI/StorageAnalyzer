package com.mohamed.storageanalyzer.core.snapshot

import com.mohamed.storageanalyzer.core.identity.ProviderId
import com.mohamed.storageanalyzer.core.measurement.ByteCount
import com.mohamed.storageanalyzer.core.measurement.MetricType
import com.mohamed.storageanalyzer.core.measurement.StorageUnits
import com.mohamed.storageanalyzer.core.resource.StorageCategory
import java.time.Instant

/**
 * A point-in-time summary of storage usage.
 *
 * Snapshots summarize measured data — they are never estimates themselves.
 * Snapshots inherit the accounting guarantees of the full run that produced
 * them: unique counting, honestly-reported unmeasured resources,
 * deterministic totals.
 */
data class StorageSnapshot(
    val capturedAt: Instant,

    /** The footprint metric this snapshot was taken against (canonical default). */
    val footprintMetric: MetricType,

    val footprintTotal: ByteCount,

    val byMetric: Map<MetricType, ByteCount>,

    val byProvider: Map<ProviderId, ByteCount>,

    val byCategory: Map<StorageCategory, ByteCount>,

    /** When captured with resource details: footprint per resource (stableKey → bytes). */
    val byResource: Map<String, ByteCount>,

    /** Number of unique resources measured in the run that produced this snapshot. */
    val resourceCount: Long,

    /** Completeness label of the producing run (e.g. `COMPLETE` / `PARTIAL`). */
    val completenessLabel: String,
) {
    /** Formats the footprint total (presentation only; never round-trips). */
    fun formatFootprint(decimals: Int = 2, convention: StorageUnits.Convention = StorageUnits.Convention.IEC): String =
        when (convention) {
            StorageUnits.Convention.IEC -> StorageUnits.formatIec(footprintTotal, decimals)
            StorageUnits.Convention.SI -> StorageUnits.formatSi(footprintTotal, decimals)
        }
}

/** True when the snapshot carries per-resource details used for growth attribution. */
val StorageSnapshot.hasResourceDetails: Boolean
    get() = byResource.isNotEmpty()
