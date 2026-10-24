package com.mohamed.dev.ui.storageanalyzer.data

import com.mohamed.dev.ui.storageanalyzer.data.demo.DemoContentSeeder
import com.mohamed.dev.ui.storageanalyzer.data.repository.DatabaseInspector
import com.mohamed.dev.ui.storageanalyzer.data.repository.HistoryRepository
import com.mohamed.dev.ui.storageanalyzer.data.repository.StorageRepository
import com.mohamed.dev.ui.storageanalyzer.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** First-launch bootstrap: seed, baseline snapshot, first scan. */
sealed interface BootstrapState {
    data class Working(val step: String) : BootstrapState
    data object Ready : BootstrapState
    data class Failed(val message: String) : BootstrapState
}

/**
 * Runs the work the screens assume exists: demo files, preferences, a baseline
 * history point and one completed scan. It is idempotent and retryable, and it
 * never hides a failure behind an empty screen.
 */
@Singleton
class AppInitializer @Inject constructor(
    private val seeder: DemoContentSeeder,
    private val storage: StorageRepository,
    private val history: HistoryRepository,
    private val databaseInspector: DatabaseInspector,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow<BootstrapState>(BootstrapState.Working("Starting"))
    val state: StateFlow<BootstrapState> = _state.asStateFlow()

    fun start() {
        scope.launch { ensureReady() }
    }

    fun retry() {
        scope.launch { ensureReady(force = true) }
    }

    suspend fun ensureReady(force: Boolean = false) = mutex.withLock {
        if (!force && _state.value is BootstrapState.Ready) return@withLock
        try {
            _state.value = BootstrapState.Working("Seeding demo storage")
            seeder.seed()

            _state.value = BootstrapState.Working("Capturing baseline snapshot")
            history.ensureBaseline()

            _state.value = BootstrapState.Working("Running the first analysis")
            storage.refresh(trigger = "startup")

            _state.value = BootstrapState.Working("Reading Room database stats")
            databaseInspector.stats()

            _state.value = BootstrapState.Ready
        } catch (e: Exception) {
            _state.value = BootstrapState.Failed(
                e.message ?: e.javaClass.simpleName,
            )
        }
    }
}
