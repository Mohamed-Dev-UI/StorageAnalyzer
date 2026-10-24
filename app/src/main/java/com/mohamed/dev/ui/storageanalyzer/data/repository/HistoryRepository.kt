package com.mohamed.dev.ui.storageanalyzer.data.repository

import com.mohamed.dev.ui.storageanalyzer.data.local.HistorySnapshotDao
import com.mohamed.dev.ui.storageanalyzer.data.local.SnapshotCodec
import com.mohamed.dev.ui.storageanalyzer.data.model.HistoryPoint
import com.mohamed.dev.ui.storageanalyzer.data.model.HistoryState
import com.mohamed.dev.ui.storageanalyzer.di.IoDispatcher
import com.mohamed.storageanalyzer.core.snapshot.StorageSnapshot
import com.mohamed.storageanalyzer.history.GrowthModelFactory
import com.mohamed.storageanalyzer.history.SnapshotComparison
import com.mohamed.storageanalyzer.history.SnapshotStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Snapshot timeline for the History screen.
 *
 * Snapshots travel through the library's [SnapshotStore] contract (synchronous,
 * append-only) but are persisted through Room. Decoding happens here rather than
 * in the UI so corruption is reported as data, not an exception swallowed by a
 * composable.
 */
@Singleton
class HistoryRepository @Inject constructor(
    private val store: SnapshotStore,
    private val dao: HistorySnapshotDao,
    private val codec: SnapshotCodec,
    private val storage: StorageRepository,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    val history: Flow<HistoryState> = dao.observeAll()
        .map { rows -> buildState(rows) }
        .flowOn(io)

    /** Captures a new snapshot through the public analyzer API and appends it. */
    suspend fun capture(): StorageSnapshot = withContext(io) {
        val snapshot = storage.captureSnapshot(trigger = "history capture")
        store.append(snapshot)
        snapshot
    }

    /** Starts the timeline on first launch without touching an existing series. */
    suspend fun ensureBaseline(): StorageSnapshot? = withContext(io) {
        if (dao.count() > 0) return@withContext null
        val snapshot = storage.captureSnapshot(trigger = "baseline")
        store.append(snapshot)
        snapshot
    }

    suspend fun clear() = withContext(io) { dao.clear() }

    private fun buildState(rows: List<com.mohamed.dev.ui.storageanalyzer.data.local.HistorySnapshotEntity>): HistoryState {
        val points = mutableListOf<HistoryPoint>()
        var decodeError: String? = null
        for (row in rows) {
            try {
                points += HistoryPoint(rowId = row.id, snapshot = codec.decode(row.payload))
            } catch (e: Exception) {
                decodeError = "snapshot row ${row.id} could not be decoded: ${e.message}"
            }
        }
        val ordered = points.sortedBy { it.snapshot.capturedAt }
        val comparison = if (ordered.size >= 2) {
            runCatching {
                SnapshotComparison.between(
                    from = ordered[ordered.size - 2].snapshot,
                    to = ordered.last().snapshot,
                )
            }.getOrNull()
        } else {
            null
        }
        return HistoryState(
            points = ordered,
            comparison = comparison,
            growth = GrowthModelFactory.fit(ordered.map { it.snapshot }),
            decodeError = decodeError,
        )
    }
}
