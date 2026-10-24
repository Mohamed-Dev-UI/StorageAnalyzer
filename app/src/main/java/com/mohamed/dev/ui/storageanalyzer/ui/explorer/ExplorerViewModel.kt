package com.mohamed.dev.ui.storageanalyzer.ui.explorer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mohamed.dev.ui.storageanalyzer.data.model.EntityFolderInsight
import com.mohamed.dev.ui.storageanalyzer.data.model.ResourceRow
import com.mohamed.dev.ui.storageanalyzer.data.repository.StorageRepository
import com.mohamed.storageanalyzer.core.measurement.ByteCount
import com.mohamed.storageanalyzer.core.resource.StorageCategory
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

data class ExplorerUiState(
    val rows: List<ResourceRow> = emptyList(),
    val query: String = "",
    val category: StorageCategory? = null,
    val capturedAt: Instant? = null,
    val uniqueFootprint: ByteCount = ByteCount.ZERO,
    val resourceCount: Long = 0,
    val isScanning: Boolean = false,
    val error: String? = null,
    val folders: List<EntityFolderInsight> = emptyList(),
    val foldersLoaded: Boolean = false,
    val isLoadingFolders: Boolean = false,
    val folderError: String? = null,
) {
    val categories: List<StorageCategory>
        get() = rows.map { it.category }.distinct().sortedBy { it.name }

    val filteredRows: List<ResourceRow>
        get() {
            val needle = query.trim()
            return rows.filter { row ->
                (category == null || row.category == category) &&
                    (needle.isEmpty() ||
                        row.label.contains(needle, ignoreCase = true) ||
                        row.pathLabel.contains(needle, ignoreCase = true) ||
                        row.identity.nativeId.contains(needle, ignoreCase = true) ||
                        row.providerId.contains(needle, ignoreCase = true))
            }
        }

    val filteredBytes: ByteCount
        get() = ByteCount.sum(filteredRows.map { it.bytes })
}

@HiltViewModel
class ExplorerViewModel @Inject constructor(
    private val repository: StorageRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ExplorerUiState())
    val state: StateFlow<ExplorerUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            repository.latest.collect { snapshot ->
                _state.update {
                    it.copy(
                        rows = snapshot?.rows.orEmpty(),
                        capturedAt = snapshot?.capturedAt,
                        uniqueFootprint = snapshot?.report?.totals?.uniqueFootprint ?: ByteCount.ZERO,
                        resourceCount = snapshot?.report?.resourceCount ?: 0L,
                    )
                }
            }
        }
    }

    fun setQuery(query: String) = _state.update { it.copy(query = query) }

    fun setCategory(category: StorageCategory?) = _state.update { it.copy(category = category) }

    fun refresh() {
        if (_state.value.isScanning) return
        viewModelScope.launch {
            _state.update {
                it.copy(
                    isScanning = true,
                    error = null,
                    folders = emptyList(),
                    foldersLoaded = false,
                )
            }
            try {
                repository.refresh(trigger = "explorer rescan")
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: e.javaClass.simpleName) }
            } finally {
                _state.update { it.copy(isScanning = false) }
            }
        }
    }

    fun loadEntityFolders() {
        if (_state.value.isLoadingFolders) return
        viewModelScope.launch {
            _state.update { it.copy(isLoadingFolders = true, folderError = null) }
            try {
                val folders = repository.entityFolders()
                _state.update {
                    it.copy(
                        folders = folders,
                        foldersLoaded = true,
                        isLoadingFolders = false,
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        folderError = e.message ?: e.javaClass.simpleName,
                        isLoadingFolders = false,
                    )
                }
            }
        }
    }

    fun dismissError() = _state.update { it.copy(error = null) }
}
