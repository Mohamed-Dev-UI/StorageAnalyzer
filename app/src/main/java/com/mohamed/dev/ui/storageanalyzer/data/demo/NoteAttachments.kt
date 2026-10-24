package com.mohamed.dev.ui.storageanalyzer.data.demo

import com.mohamed.dev.ui.storageanalyzer.data.local.DemoNoteEntity
import java.io.File

/** Markdown attachments written next to the seeded note content. */
object NoteAttachments {

    fun fileFor(environment: DemoEnvironment, noteId: String): File =
        File(File(environment.root, "attachments"), "$noteId.md")

    fun write(environment: DemoEnvironment, note: DemoNoteEntity): String {
        val file = fileFor(environment, note.id)
        file.parentFile?.mkdirs()
        file.writeText(render(note), Charsets.UTF_8)
        return file.name
    }

    fun delete(environment: DemoEnvironment, noteId: String) {
        fileFor(environment, noteId).delete()
    }

    fun length(environment: DemoEnvironment, noteId: String): Long =
        fileFor(environment, noteId).takeIf { it.isFile }?.length() ?: 0L

    private fun render(note: DemoNoteEntity): String = buildString {
        append("# ").append(note.title).append("\n\n")
        append("- category: ").append(note.category).append('\n')
        append("- updated: ").append(note.updatedAtEpochMs).append('\n')
        append("- source: Room `demo_notes` row `").append(note.id).append("`\n\n")
        append(note.body).append('\n')
    }
}
