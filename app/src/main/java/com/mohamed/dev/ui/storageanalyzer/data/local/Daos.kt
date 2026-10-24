package com.mohamed.dev.ui.storageanalyzer.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface DemoNoteDao {
    @Query("SELECT * FROM demo_notes ORDER BY updatedAtEpochMs DESC")
    fun observeAll(): Flow<List<DemoNoteEntity>>

    @Query("SELECT * FROM demo_notes ORDER BY updatedAtEpochMs DESC")
    suspend fun getAll(): List<DemoNoteEntity>

    @Query("SELECT * FROM demo_notes WHERE id = :id")
    suspend fun find(id: String): DemoNoteEntity?

    @Upsert
    suspend fun upsert(note: DemoNoteEntity)

    @Query("DELETE FROM demo_notes WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT COUNT(*) FROM demo_notes")
    fun observeCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM demo_notes")
    suspend fun count(): Int
}

@Dao
interface ScanSessionDao {
    @Insert
    suspend fun insert(session: ScanSessionEntity): Long

    @Query("SELECT * FROM scan_sessions ORDER BY startedAtEpochMs DESC, id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<ScanSessionEntity>>

    @Query("SELECT COUNT(*) FROM scan_sessions")
    fun observeCount(): Flow<Int>

    @Query("DELETE FROM scan_sessions")
    suspend fun clear()
}

@Dao
interface HistorySnapshotDao {
    @Insert
    suspend fun insert(snapshot: HistorySnapshotEntity): Long

    @Query("SELECT * FROM history_snapshots ORDER BY id ASC")
    fun observeAll(): Flow<List<HistorySnapshotEntity>>

    @Query("SELECT * FROM history_snapshots ORDER BY id ASC")
    suspend fun getAll(): List<HistorySnapshotEntity>

    @Query("SELECT COUNT(*) FROM history_snapshots")
    suspend fun count(): Int

    @Query("DELETE FROM history_snapshots")
    suspend fun clear()
}
