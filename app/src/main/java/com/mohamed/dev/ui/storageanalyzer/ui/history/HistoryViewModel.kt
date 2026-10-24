package com.mohamed.dev.ui.storageanalyzer.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mohamed.dev.ui.storageanalyzer.data.demo.DemoContentSeeder
import com.mohamed.dev.ui.storageanalyzer.data.model.HistoryState
import com.mohamed.dev.ui.storageanalyzer.data.repository.HistoryRepository
import com.mohamed.dev.ui.storageanalyzer.data.repository.StorageRepository
import com.mohamed.storageanalyzer.history.GrowthModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HistoryUiState(
    val history: HistoryState = HistoryState(
        growth = GrowthModel.Unavailable("history is still loading"),
    ),
    val labels: Map<String, String> = emptyMap(),
    val isCapturing: Boolean = false,
    val isGrowing: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
)

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val historyRepository: HistoryRepository,
    private val storageRepository: StorageRepository,
    private val seeder: DemoContentSeeder,
) : ViewModel() {

    private val _state = MutableStateFlow(HistoryUiState())
    val state: StateFlow<HistoryUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            historyRepository.history.collect { history ->
                _state.update { it.copy(history = history) }
            }
        }
        viewModelScope.launch {
            storageRepository.latest.collect { snapshot ->
                val labels = snapshot?.rows
                    ?.associate { row -> row.stableKey to row.label }
                    .orEmpty()
                _state.update { it.copy(labels = labels) }
            }
        }
    }

    fun capture() {
        if (_state.value.isCapturing || _state.value.isGrowing) return
        viewModelScope.launch {
            _state.update { it.copy(isCapturing = true, error = null, notice = null) }
            try {
                val snapshot = historyRepository.capture()
                _state.update {
                    it.copy(notice = "Captured a snapshot with ${snapshot.resourceCount} resources and per-resource details.")
                }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: e.javaClass.simpleName) }
            } finally {
                _state.update { it.copy(isCapturing = false) }
            }
        }
    }

    fun growAndCapture() {
        if (_state.value.isCapturing || _state.value.isGrowing) return
        viewModelScope.launch {
            _state.update { it.copy(isGrowing = true, error = null, notice = null) }
            try {
                val relative = seeder.grow()
                historyRepository.capture()
                _state.update {
                    it.copy(notice = "Added $relative, then captured a snapshot so the change is attributable.")
                }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: e.javaClass.simpleName) }
            } finally {
                _state.update { it.copy(isGrowing = false) }
            }
        }
    }

    fun clear() {
        viewModelScope.launch {
            _state.update { it.copy(error = null, notice = null) }
            try {
                historyRepository.clear()
                _state.update { it.copy(notice = "Snapshot timeline cleared. Capture a new baseline when ready.") }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    fun dismissMessages() = _state.update { it.copy(error = null, notice = null) }
}
