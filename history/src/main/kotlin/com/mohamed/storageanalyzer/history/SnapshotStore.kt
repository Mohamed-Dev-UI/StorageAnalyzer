package com.mohamed.storageanalyzer.history

import com.mohamed.storageanalyzer.core.snapshot.StorageSnapshot

/**
 * Append-only persistence for [StorageSnapshot] values.
 *
 * Contract:
 * - [append] stores one snapshot atomically and returns its 0-based index
 *   (monotonically increasing; two appends must never collide).
 * - [readAll] returns every stored snapshot in strictly chronological append
 *   order — the order in which [append] was called, which is also how growth
 *   models interpret the series.
 * - Corrupt storage MUST fail loudly (an exception with the offending
 *   location), never silently reduce to fewer snapshots.
 *
 * Persistence format and concurrency details are store-specific; the
 * [FileSnapshotStore] has a stable, documented on-disk format.
 */
interface SnapshotStore : AutoCloseable {

    /** Stores [snapshot] and returns its assigned 0-based index. */
    fun append(snapshot: StorageSnapshot): Long

    /** All snapshots in append order. */
    fun readAll(): List<StorageSnapshot>

    /** The most recently appended snapshot, or null when the store is empty. */
    fun latest(): StorageSnapshot? = readAll().lastOrNull()

    /** Number of stored snapshots. */
    val size: Int
}

/** Dependency-free in-memory [SnapshotStore] (tests and short-lived runs). */
class InMemorySnapshotStore : SnapshotStore {

    private val snapshots = ArrayList<StorageSnapshot>()

    @Synchronized
    override fun append(snapshot: StorageSnapshot): Long {
        snapshots.add(snapshot)
        return (snapshots.size - 1).toLong()
    }

    @Synchronized
    override fun readAll(): List<StorageSnapshot> = ArrayList(snapshots)

    @Synchronized
    override fun latest(): StorageSnapshot? = snapshots.lastOrNull()

    @get:Synchronized
    override val size: Int
        get() = snapshots.size

    override fun close() {
        // Nothing retained beyond the list itself.
    }
}
