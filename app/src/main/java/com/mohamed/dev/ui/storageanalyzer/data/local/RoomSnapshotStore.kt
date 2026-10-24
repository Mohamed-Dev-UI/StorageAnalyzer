package com.mohamed.dev.ui.storageanalyzer.data.local

import com.mohamed.dev.ui.storageanalyzer.di.IoDispatcher
import com.mohamed.storageanalyzer.core.snapshot.StorageSnapshot
import com.mohamed.storageanalyzer.history.SnapshotStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [SnapshotStore] backed by Room.
 *
 * The library interface is synchronous by contract, so each call bridges to
 * the Room DAO with `runBlocking` on the IO dispatcher. Callers on the main
 * thread are therefore never allowed; repositories invoke it from coroutines.
 */
@Singleton
class RoomSnapshotStore @Inject constructor(
    private val dao: HistorySnapshotDao,
    private val codec: SnapshotCodec,
    @IoDispatcher private val io: CoroutineDispatcher,
) : SnapshotStore {

    @Synchronized
    override fun append(snapshot: StorageSnapshot): Long = runBlocking(io) {
        val rowId = dao.insert(
            HistorySnapshotEntity(
                capturedAtEpochMs = snapshot.capturedAt.toEpochMilli(),
                footprintBytes = snapshot.footprintTotal.value,
                resourceCount = snapshot.resourceCount,
                completenessLabel = snapshot.completenessLabel,
                payload = codec.encode(snapshot),
            ),
        )
        (rowId - 1L).coerceAtLeast(0L)
    }

    @Synchronized
    override fun readAll(): List<StorageSnapshot> = runBlocking(io) {
        dao.getAll().map { row ->
            try {
                codec.decode(row.payload)
            } catch (e: Exception) {
                throw IllegalStateException(
                    "corrupt stored snapshot at history_snapshots.id=${row.id}; refusing to silently drop it",
                    e,
                )
            }
        }
    }

    @Synchronized
    override fun latest(): StorageSnapshot? = readAll().lastOrNull()

    @get:Synchronized
    override val size: Int
        get() = runBlocking(io) { dao.count() }

    override fun close() = Unit
}
