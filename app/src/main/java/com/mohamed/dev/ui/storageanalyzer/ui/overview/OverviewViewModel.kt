package com.mohamed.dev.ui.storageanalyzer.ui.overview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mohamed.dev.ui.storageanalyzer.data.demo.DemoContentSeeder
import com.mohamed.dev.ui.storageanalyzer.data.local.ScanSessionEntity
import com.mohamed.dev.ui.storageanalyzer.data.model.CapacityStats
import com.mohamed.dev.ui.storageanalyzer.data.model.MonitorStatus
import com.mohamed.dev.ui.storageanalyzer.data.model.ScanSnapshot
import com.mohamed.dev.ui.storageanalyzer.data.repository.StorageRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class OverviewUiState(
    val scan: ScanSnapshot? = null,
    val capacity: CapacityStats? = null,
    val capacityError: String? = null,
    val monitor: MonitorStatus = MonitorStatus(),
    val sessions: List<ScanSessionEntity> = emptyList(),
    val isRefreshing: Boolean = false,
    val isGrowing: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val demoRoot: String = "",
    val databasePath: String = "",
)

@HiltViewModel
class OverviewViewModel @Inject constructor(
    private val repository: StorageRepository,
    private val seeder: DemoContentSeeder,
) : ViewModel() {

    private val _state = MutableStateFlow(
        OverviewUiState(
            demoRoot = repository.demoRootPath,
            databasePath = repository.databasePath,
        ),
    )
    val state: StateFlow<OverviewUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            repository.latest.collect { snapshot ->
                _state.update { it.copy(scan = snapshot) }
            }
        }
        viewModelScope.launch {
            repository.monitorStatus.collect { status ->
                _state.update { it.copy(monitor = status) }
            }
        }
        viewModelScope.launch {
            repository.scanSessions.collect { sessions ->
                _state.update { it.copy(sessions = sessions) }
            }
        }
        loadCapacity()
    }

    fun refresh() {
        if (_state.value.isRefreshing) return
        viewModelScope.launch {
            _state.update { it.copy(isRefreshing = true, error = null, notice = null) }
            try {
                repository.refresh(trigger = "overview manual")
                loadCapacity()
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: e.javaClass.simpleName) }
            } finally {
                _state.update { it.copy(isRefreshing = false) }
            }
        }
    }

    fun growDemo() {
        if (_state.value.isGrowing) return
        viewModelScope.launch {
            _state.update { it.copy(isGrowing = true, error = null, notice = null) }
            try {
                val relative = seeder.grow()
                repository.refresh(trigger = "grow demo")
                _state.update {
                    it.copy(notice = "Added $relative (128 KiB) and re-ran the analysis.")
                }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: e.javaClass.simpleName) }
            } finally {
                _state.update { it.copy(isGrowing = false) }
            }
        }
    }

    fun setMonitoring(enabled: Boolean) {
        runCatching { repository.setMonitoring(enabled) }
            .onFailure { e ->
                _state.update { it.copy(error = e.message ?: e.javaClass.simpleName) }
            }
    }

    fun dismissMessages() {
        _state.update { it.copy(error = null, notice = null) }
    }

    fun reloadCapacity() = loadCapacity()

    private fun loadCapacity() {
        viewModelScope.launch {
            try {
                val capacity = repository.capacity()
                _state.update { it.copy(capacity = capacity, capacityError = null) }
            } catch (e: Exception) {
                _state.update {
                    it.copy(capacityError = e.message ?: e.javaClass.simpleName)
                }
            }
        }
    }
}
