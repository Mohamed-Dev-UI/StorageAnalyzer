package com.mohamed.dev.ui.storageanalyzer.data.model

import com.mohamed.dev.ui.storageanalyzer.data.local.DemoNoteEntity

/** A Room note and the size of the Markdown attachment it writes into the analyzed tree. */
data class NoteRow(
    val note: DemoNoteEntity,
    val attachmentBytes: Long,
)
