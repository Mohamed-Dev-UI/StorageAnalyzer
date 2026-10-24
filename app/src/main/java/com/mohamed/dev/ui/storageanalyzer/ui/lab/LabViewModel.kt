package com.mohamed.dev.ui.storageanalyzer.ui.lab

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mohamed.dev.ui.storageanalyzer.data.model.AccountingSnapshot
import com.mohamed.dev.ui.storageanalyzer.data.model.CancellationOutcome
import com.mohamed.dev.ui.storageanalyzer.data.model.DedupSnapshot
import com.mohamed.dev.ui.storageanalyzer.data.model.ExclusionSnapshot
import com.mohamed.dev.ui.storageanalyzer.data.model.PrivacySnapshot
import com.mohamed.dev.ui.storageanalyzer.data.model.ProgressScanResult
import com.mohamed.dev.ui.storageanalyzer.data.model.ProviderCapabilityRow
import com.mohamed.dev.ui.storageanalyzer.data.model.ProviderFilterSnapshot
import com.mohamed.dev.ui.storageanalyzer.data.repository.StorageRepository
import com.mohamed.storageanalyzer.core.result.StorageDiagnostic
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LabUiState(
    val diagnostics: List<StorageDiagnostic>? = null,
    val capabilities: List<ProviderCapabilityRow> = emptyList(),
    val progress: ProgressScanResult? = null,
    val cancellation: CancellationOutcome? = null,
    val accounting: List<AccountingSnapshot> = emptyList(),
    val dedup: DedupSnapshot? = null,
    val exclusion: ExclusionSnapshot? = null,
    val privacy: PrivacySnapshot? = null,
    val providerFilter: ProviderFilterSnapshot? = null,
    val busy: String? = null,
    val error: String? = null,
) {
    val isBusy: Boolean get() = busy != null
}

@HiltViewModel
class LabViewModel @Inject constructor(
    private val repository: StorageRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(LabUiState())
    val state: StateFlow<LabUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            _state.update { it.copy(busy = "startup") }
            try {
                val diagnostics = repository.diagnostics()
                val capabilities = repository.providerCapabilities()
                _state.update {
                    it.copy(diagnostics = diagnostics, capabilities = capabilities)
                }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: e.javaClass.simpleName) }
            } finally {
                _state.update { it.copy(busy = null) }
            }
        }
    }

    fun loadDiagnostics() = runTask("diagnostics") {
        val diagnostics = repository.diagnostics()
        _state.update { it.copy(diagnostics = diagnostics) }
    }

    fun loadCapabilities() = runTask("capabilities") {
        val capabilities = repository.providerCapabilities()
        _state.update { it.copy(capabilities = capabilities) }
    }

    fun runBudgetProgress() = runTask("progress") {
        val progress = repository.progressScan()
        _state.update { it.copy(progress = progress) }
    }

    fun runCancellation() = runTask("cancellation") {
        val outcome = repository.cancellationDemo()
        _state.update { it.copy(cancellation = outcome) }
    }

    fun runAccounting() = runTask("accounting") {
        val accounting = repository.accountingExperiment()
        _state.update { it.copy(accounting = accounting) }
    }

    fun runDedup() = runTask("dedup") {
        val dedup = repository.dedupExperiment()
        _state.update { it.copy(dedup = dedup) }
    }

    fun runExclusion() = runTask("exclusion") {
        val exclusion = repository.exclusionExperiment()
        _state.update { it.copy(exclusion = exclusion) }
    }

    fun runPrivacy() = runTask("privacy") {
        val privacy = repository.privacyExperiment()
        _state.update { it.copy(privacy = privacy) }
    }

    fun runProviderFilter() = runTask("filter") {
        val filter = repository.providerFilterExperiment()
        _state.update { it.copy(providerFilter = filter) }
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    private fun runTask(key: String, block: suspend () -> Unit) {
        if (_state.value.isBusy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = key, error = null) }
            try {
                block()
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: e.javaClass.simpleName) }
            } finally {
                _state.update { it.copy(busy = null) }
            }
        }
    }
}
