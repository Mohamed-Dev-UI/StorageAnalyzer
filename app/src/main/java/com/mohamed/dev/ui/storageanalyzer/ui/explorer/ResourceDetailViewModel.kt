package com.mohamed.dev.ui.storageanalyzer.ui.explorer

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mohamed.dev.ui.storageanalyzer.data.model.ResourceRow
import com.mohamed.dev.ui.storageanalyzer.data.repository.StorageRepository
import com.mohamed.dev.ui.storageanalyzer.ui.navigation.Routes
import com.mohamed.storageanalyzer.core.result.StorageMeasurement
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ResourceDetailUiState(
    val row: ResourceRow? = null,
    val measurement: StorageMeasurement? = null,
    val isLoading: Boolean = false,
    val notFound: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class ResourceDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: StorageRepository,
) : ViewModel() {

    private val stableKey: String = savedStateHandle.get<String>(Routes.STABLE_KEY_ARG).orEmpty()

    private val _state = MutableStateFlow(ResourceDetailUiState())
    val state: StateFlow<ResourceDetailUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            repository.latest.collect { snapshot ->
                val row = snapshot?.rows?.firstOrNull { it.stableKey == stableKey }
                val shouldMeasure = row != null &&
                    _state.value.measurement == null &&
                    !_state.value.isLoading
                _state.update {
                    it.copy(row = row, notFound = row == null)
                }
                if (shouldMeasure) measure()
            }
        }
    }

    fun measure() {
        if (_state.value.isLoading) return
        val identity = (_state.value.row ?: repository.rowForStableKey(stableKey))?.identity ?: return
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            try {
                val measurement = repository.measureResource(identity)
                _state.update { it.copy(measurement = measurement, isLoading = false) }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        error = e.message ?: e.javaClass.simpleName,
                        isLoading = false,
                    )
                }
            }
        }
    }
}
