package com.mohamed.dev.ui.storageanalyzer.data.model

import com.mohamed.storageanalyzer.core.identity.ResourceIdentity
import com.mohamed.storageanalyzer.core.identity.ResourceKind
import com.mohamed.storageanalyzer.core.measurement.ByteCount
import com.mohamed.storageanalyzer.core.resource.StorageCategory
import com.mohamed.storageanalyzer.core.result.EntityReport
import com.mohamed.storageanalyzer.core.result.GlobalReport
import com.mohamed.storageanalyzer.core.result.ProviderSummary
import com.mohamed.storageanalyzer.core.result.SharedResourceAllocation
import com.mohamed.storageanalyzer.core.result.StorageMeasurement
import java.time.Instant

/** Latest completed manual scan, enriched with explorer rows built from discovery metadata. */
data class ScanSnapshot(
    val report: GlobalReport,
    val digest: String,
    val durationMs: Long,
    val rows: List<ResourceRow>,
    val capturedAt: Instant = Instant.now(),
)

/** One measured resource plus the metadata discovery exposes about it. */
data class ResourceRow(
    val identity: ResourceIdentity,
    val label: String,
    val kind: ResourceKind,
    val category: StorageCategory,
    val bytes: ByteCount,
    val containerPath: String,
) {
    val stableKey: String get() = identity.stableKey
    val providerId: String get() = identity.providerId.value
    val pathLabel: String
        get() = if (containerPath.isBlank()) label else "$containerPath/$label"
}

/** Volume statistics measured through the filesystem provider's volume facade. */
data class CapacityStats(
    val totalBytes: ByteCount?,
    val freeBytes: ByteCount?,
    val reservedBytes: ByteCount?,
    val measuredAt: Instant?,
    val methodId: String?,
    val accuracy: String?,
    /** Provider explanation when the volume metrics are unavailable. */
    val reason: String? = null,
) {
    val usedBytes: ByteCount?
        get() = if (totalBytes != null && freeBytes != null && totalBytes >= freeBytes) {
            totalBytes - freeBytes
        } else {
            null
        }

    val usedFraction: Float?
        get() {
            val total = totalBytes?.value ?: return null
            val used = usedBytes?.value ?: return null
            return if (total == 0L) null else (used.toDouble() / total.toDouble()).toFloat()
        }
}

/** Entity-level accounting view of one demo folder. */
data class EntityFolderInsight(
    val entityTypeId: String,
    val displayName: String,
    val folderName: String,
    val report: EntityReport,
) {
    val inclusiveBytes: ByteCount
        get() = report.inclusive.values.maxOrNull() ?: ByteCount.ZERO
    val directBytes: ByteCount
        get() = report.direct.values.maxOrNull() ?: ByteCount.ZERO
    val uniqueReachableBytes: ByteCount
        get() = report.uniqueReachable.values.maxOrNull() ?: ByteCount.ZERO
    val sharedAllocatedBytes: ByteCount
        get() = report.sharedAllocated.values.fold(ByteCount.ZERO, ByteCount::plus)
}

/** Capability matrix row for the Lab screen. */
data class ProviderCapabilityRow(
    val providerId: String,
    val description: String,
    val systemCount: Int,
    val supported: List<Pair<String, String>>,
    val unsupported: List<UnsupportedCapability>,
)

data class UnsupportedCapability(
    val id: String,
    val description: String,
    val reason: String,
)

/** Lifecycle of the optional `ReactiveAnalyzer` hot monitor. */
data class MonitorStatus(
    val enabled: Boolean = false,
    val phase: String = "Idle",
    val digest: String? = null,
    val footprintBytes: ByteCount? = null,
    val resourceCount: Long? = null,
    val lastCompletedAt: Instant? = null,
    val error: String? = null,
    val turns: Int = 0,
) {
    val isRunning: Boolean get() = phase == "Running"
}

/** One accounting policy run over the exact same inventory. */
data class AccountingSnapshot(
    val policyId: String,
    val uniqueFootprint: ByteCount,
    val sharedOnly: ByteCount,
    val attributedShared: ByteCount,
    val allocations: List<SharedResourceAllocation>,
)

/** Cross-provider deduplication evidence. */
data class DedupSnapshot(
    val uniqueFootprint: ByteCount,
    /** Unique footprint plus every overlap measurement (each provider's own view). */
    val providerViewBytes: ByteCount,
    val overlaps: List<StorageMeasurement>,
    val providerSummaries: List<ProviderSummary>,
)

/** Effect of the run-level exclusion predicate. */
data class ExclusionSnapshot(
    val beforeBytes: ByteCount,
    val afterBytes: ByteCount,
    val beforeCount: Long,
    val afterCount: Long,
    val exclusionDiagnostic: String?,
) {
    val savedBytes: Long get() = beforeBytes.value - afterBytes.value
    val droppedResources: Long get() = beforeCount - afterCount
}

/** Overlap labels before/after `redactLabels`. */
data class PrivacySnapshot(
    val unredactedLabels: List<String>,
    val redactedLabels: List<String>,
)

/** Provider targeting: all registered providers vs a single provider. */
data class ProviderFilterSnapshot(
    val allBytes: ByteCount,
    val filteredBytes: ByteCount,
    val allProviders: List<String>,
    val filteredProviders: List<String>,
)

/** Cooperative cancellation demo result. */
data class CancellationOutcome(
    val cancelled: Boolean,
    val elapsedMs: Long,
    val progressEvents: Int,
    val message: String,
)

/** Progress-event demo result. */
data class ProgressScanResult(
    val events: List<String>,
    val resourceCount: Long,
    val completeness: String,
    val footprintBytes: ByteCount,
)
