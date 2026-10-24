package com.mohamed.dev.ui.storageanalyzer.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A user note; saving one also writes a Markdown attachment into the demo storage tree. */
@Entity(tableName = "demo_notes")
data class DemoNoteEntity(
    @PrimaryKey val id: String,
    val title: String,
    val body: String,
    val category: String,
    val updatedAtEpochMs: Long,
    val attachmentName: String?,
)

/** One completed analysis run, persisted for the "recent sessions" views. */
@Entity(tableName = "scan_sessions")
data class ScanSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAtEpochMs: Long,
    val durationMs: Long,
    val footprintBytes: Long,
    val resourceCount: Long,
    val completeness: String,
    val digest: String,
    val trigger: String,
    val providerCount: Int,
)

/** Append-only snapshot storage; the library's synchronous SnapshotStore is adapted over this table. */
@Entity(tableName = "history_snapshots")
data class HistorySnapshotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val capturedAtEpochMs: Long,
    val footprintBytes: Long,
    val resourceCount: Long,
    val completenessLabel: String,
    val payload: String,
)
