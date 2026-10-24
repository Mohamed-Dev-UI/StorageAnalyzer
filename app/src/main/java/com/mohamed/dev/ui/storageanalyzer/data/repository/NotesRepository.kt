package com.mohamed.dev.ui.storageanalyzer.data.repository

import com.mohamed.dev.ui.storageanalyzer.data.demo.DemoEnvironment
import com.mohamed.dev.ui.storageanalyzer.data.demo.NoteAttachments
import com.mohamed.dev.ui.storageanalyzer.data.local.DemoNoteDao
import com.mohamed.dev.ui.storageanalyzer.data.local.DemoNoteEntity
import com.mohamed.dev.ui.storageanalyzer.data.model.NoteRow
import com.mohamed.dev.ui.storageanalyzer.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Room notes with a filesystem side effect: every save writes the note body as
 * a Markdown attachment inside the analyzed demo tree. This is what makes the
 * Database screen feed the scanner — a DB write and a file write in one action.
 */
@Singleton
class NotesRepository @Inject constructor(
    private val dao: DemoNoteDao,
    private val environment: DemoEnvironment,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    fun observeNotes(): Flow<List<NoteRow>> = dao.observeAll()
        .map { notes ->
            notes.map { note ->
                NoteRow(note = note, attachmentBytes = NoteAttachments.length(environment, note.id))
            }
        }
        .flowOn(io)

    fun observeCount(): Flow<Int> = dao.observeCount().flowOn(io)

    suspend fun find(id: String): DemoNoteEntity? = withContext(io) { dao.find(id) }

    suspend fun save(note: DemoNoteEntity): NoteRow = withContext(io) {
        val attachment = NoteAttachments.write(environment, note)
        val stored = note.copy(attachmentName = attachment)
        dao.upsert(stored)
        NoteRow(note = stored, attachmentBytes = NoteAttachments.length(environment, stored.id))
    }

    suspend fun create(title: String, body: String, category: String): NoteRow = withContext(io) {
        val note = DemoNoteEntity(
            id = "note-" + UUID.randomUUID().toString().take(8),
            title = title.trim(),
            body = body.trim(),
            category = category.trim().ifBlank { "General" },
            updatedAtEpochMs = System.currentTimeMillis(),
            attachmentName = null,
        )
        save(note)
    }

    suspend fun update(note: DemoNoteEntity): NoteRow = withContext(io) {
        save(note.copy(updatedAtEpochMs = System.currentTimeMillis()))
    }

    suspend fun delete(id: String) = withContext(io) {
        NoteAttachments.delete(environment, id)
        dao.delete(id)
    }
}
