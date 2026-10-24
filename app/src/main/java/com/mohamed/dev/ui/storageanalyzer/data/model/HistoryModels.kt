package com.mohamed.dev.ui.storageanalyzer.data.model

import com.mohamed.storageanalyzer.core.snapshot.StorageSnapshot
import com.mohamed.storageanalyzer.history.GrowthModel
import com.mohamed.storageanalyzer.history.SnapshotComparison

/** One persisted snapshot row decoded back through the app-owned codec. */
data class HistoryPoint(
    val rowId: Long,
    val snapshot: StorageSnapshot,
) {
    val capturedAt get() = snapshot.capturedAt
    val footprintBytes get() = snapshot.footprintTotal
}

/** Timeline plus the two derived history features the UI renders. */
data class HistoryState(
    val points: List<HistoryPoint> = emptyList(),
    val comparison: SnapshotComparison? = null,
    val growth: GrowthModel = GrowthModel.Unavailable("history has not loaded yet"),
    val decodeError: String? = null,
) {
    val latest: HistoryPoint? get() = points.lastOrNull()
    val hasEnoughForComparison: Boolean get() = points.size >= 2
}
