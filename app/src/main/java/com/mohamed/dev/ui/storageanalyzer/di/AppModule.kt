package com.mohamed.dev.ui.storageanalyzer.di

import android.content.Context
import androidx.room.Room
import com.mohamed.dev.ui.storageanalyzer.data.demo.DemoEnvironment
import com.mohamed.dev.ui.storageanalyzer.data.local.DemoNoteDao
import com.mohamed.dev.ui.storageanalyzer.data.local.HistorySnapshotDao
import com.mohamed.dev.ui.storageanalyzer.data.local.RoomSnapshotStore
import com.mohamed.dev.ui.storageanalyzer.data.local.ScanSessionDao
import com.mohamed.dev.ui.storageanalyzer.data.local.ShowcaseDatabase
import com.mohamed.storageanalyzer.history.SnapshotStore
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    @ApplicationScope
    fun applicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides
    @Singleton
    @IoDispatcher
    fun ioDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @Singleton
    fun showcaseDatabase(@ApplicationContext context: Context): ShowcaseDatabase =
        Room.databaseBuilder(context, ShowcaseDatabase::class.java, ShowcaseDatabase.NAME)
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .build()

    @Provides
    fun demoNoteDao(database: ShowcaseDatabase): DemoNoteDao = database.demoNoteDao()

    @Provides
    fun scanSessionDao(database: ShowcaseDatabase): ScanSessionDao = database.scanSessionDao()

    @Provides
    fun historySnapshotDao(database: ShowcaseDatabase): HistorySnapshotDao = database.historySnapshotDao()

    /**
     * App-specific external storage keeps the demo visible over adb without
     * requesting broad storage permissions. The databases directory is scanned
     * as a second root so the Room file and its WAL/SHM sidecars appear in the
     * same inventory.
     */
    @Provides
    @Singleton
    fun demoEnvironment(@ApplicationContext context: Context): DemoEnvironment {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        val databaseFile = context.getDatabasePath(ShowcaseDatabase.NAME)
        return DemoEnvironment(
            root = File(base, "showcase-storage"),
            databaseDir = databaseFile.parentFile ?: context.filesDir,
        )
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class PersistenceModule {

    @Binds
    @Singleton
    abstract fun snapshotStore(store: RoomSnapshotStore): SnapshotStore
}
