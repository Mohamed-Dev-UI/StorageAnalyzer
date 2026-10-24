package com.mohamed.dev.ui.storageanalyzer.ui.database

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mohamed.dev.ui.storageanalyzer.data.local.DemoNoteEntity
import com.mohamed.dev.ui.storageanalyzer.data.model.DatabaseStats
import com.mohamed.dev.ui.storageanalyzer.data.model.NoteRow
import com.mohamed.dev.ui.storageanalyzer.data.repository.DatabaseInspector
import com.mohamed.dev.ui.storageanalyzer.data.repository.NotesRepository
import com.mohamed.dev.ui.storageanalyzer.data.repository.StorageRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DatabaseUiState(
    val notes: List<NoteRow> = emptyList(),
    val stats: DatabaseStats? = null,
    val isStatsLoading: Boolean = false,
    val isSaving: Boolean = false,
    val isCheckingPoint: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val isEditorOpen: Boolean = false,
    val editorNote: DemoNoteEntity? = null,
)

@HiltViewModel
class DatabaseViewModel @Inject constructor(
    private val notesRepository: NotesRepository,
    private val inspector: DatabaseInspector,
    private val storage: StorageRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(DatabaseUiState())
    val state: StateFlow<DatabaseUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            notesRepository.observeNotes().collect { notes ->
                _state.update { it.copy(notes = notes) }
            }
        }
        viewModelScope.launch { refreshStatsInternal() }
    }

    fun refreshStats() {
        if (_state.value.isStatsLoading) return
        viewModelScope.launch { refreshStatsInternal() }
    }

    fun checkpoint() {
        if (_state.value.isCheckingPoint) return
        viewModelScope.launch {
            _state.update { it.copy(isCheckingPoint = true, error = null, notice = null) }
            try {
                val stats = inspector.checkpoint()
                _state.update {
                    it.copy(
                        stats = stats,
                        isCheckingPoint = false,
                        notice = "WAL checkpoint complete. Sidecar bytes are now ${stats.sidecarBytes} B.",
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isCheckingPoint = false,
                        error = e.message ?: e.javaClass.simpleName,
                    )
                }
            }
        }
    }

    fun openCreate() = _state.update { it.copy(isEditorOpen = true, editorNote = null, error = null) }

    fun openEdit(note: DemoNoteEntity) = _state.update { it.copy(isEditorOpen = true, editorNote = note, error = null) }

    fun closeEditor() = _state.update { it.copy(isEditorOpen = false, editorNote = null) }

    fun save(title: String, body: String, category: String) {
        if (_state.value.isSaving) return
        if (title.isBlank() || body.isBlank()) {
            _state.update { it.copy(error = "A note needs both a title and a body.") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(isSaving = true, error = null, notice = null) }
            try {
                val existing = _state.value.editorNote
                if (existing != null) {
                    notesRepository.update(
                        existing.copy(
                            title = title.trim(),
                            body = body.trim(),
                            category = category.trim().ifBlank { "General" },
                        ),
                    )
                } else {
                    notesRepository.create(title = title, body = body, category = category)
                }
                // The note attachment is part of the analyzed tree; a rescan makes
                // the database write visible to the rest of the showcase.
                storage.refresh(trigger = "room note save")
                _state.update {
                    it.copy(
                        isSaving = false,
                        isEditorOpen = false,
                        editorNote = null,
                        notice = "Saved the note, wrote its Markdown attachment and re-ran the analysis.",
                    )
                }
                refreshStatsInternal()
            } catch (e: Exception) {
                _state.update { it.copy(isSaving = false, error = e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    fun delete(note: DemoNoteEntity) {
        viewModelScope.launch {
            _state.update { it.copy(error = null, notice = null) }
            try {
                notesRepository.delete(note.id)
                storage.refresh(trigger = "room note delete")
                _state.update {
                    it.copy(notice = "Deleted the note and its attachment, then re-ran the analysis.")
                }
                refreshStatsInternal()
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    fun dismissMessages() = _state.update { it.copy(error = null, notice = null) }

    private suspend fun refreshStatsInternal() {
        _state.update { it.copy(isStatsLoading = true) }
        try {
            val stats = inspector.stats()
            _state.update { it.copy(stats = stats, isStatsLoading = false) }
        } catch (e: Exception) {
            _state.update {
                it.copy(
                    isStatsLoading = false,
                    error = e.message ?: e.javaClass.simpleName,
                )
            }
        }
    }
}
