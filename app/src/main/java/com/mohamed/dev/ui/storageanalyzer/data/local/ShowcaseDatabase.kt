package com.mohamed.dev.ui.storageanalyzer.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [DemoNoteEntity::class, ScanSessionEntity::class, HistorySnapshotEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class ShowcaseDatabase : RoomDatabase() {
    abstract fun demoNoteDao(): DemoNoteDao
    abstract fun scanSessionDao(): ScanSessionDao
    abstract fun historySnapshotDao(): HistorySnapshotDao

    companion object {
        const val NAME = "showcase.db"
        const val VERSION = 1
    }
}
