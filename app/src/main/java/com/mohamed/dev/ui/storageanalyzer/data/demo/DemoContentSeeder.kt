package com.mohamed.dev.ui.storageanalyzer.data.demo

import com.mohamed.dev.ui.storageanalyzer.data.local.DemoNoteDao
import com.mohamed.dev.ui.storageanalyzer.data.local.DemoNoteEntity
import com.mohamed.dev.ui.storageanalyzer.data.preferences.ShowcasePreferences
import com.mohamed.dev.ui.storageanalyzer.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Creates a deterministic ~2.3 MiB tree so every library feature has
 * interesting data to measure. Seeding is marker-guarded and idempotent;
 * notes are only inserted when the Room table is empty.
 */
@Singleton
class DemoContentSeeder @Inject constructor(
    private val environment: DemoEnvironment,
    private val noteDao: DemoNoteDao,
    private val preferences: ShowcasePreferences,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    private val mutex = Mutex()

    suspend fun seed() = withContext(io) {
        mutex.withLock {
            environment.root.mkdirs()
            environment.databaseDir.mkdirs()
            preferences.seedIfEmpty()

            val marker = File(environment.root, MARKER)
            val anchor = File(environment.root, "documents/project-brief.md")
            if (!marker.isFile || !anchor.isFile) {
                seedTree()
                marker.writeText(SEED_VERSION, Charsets.UTF_8)
            }
            seedNotes()
        }
    }

    /** Adds a real 128 KiB archive so snapshots, growth attribution and live probes change. */
    suspend fun grow(): String = withContext(io) {
        mutex.withLock {
            val archives = File(environment.root, "archives").apply { mkdirs() }
            val next = (archives.listFiles()
                ?.count { it.isFile && it.name.startsWith("growth-") && it.name.endsWith(".zip") }
                ?: 0) + 1
            val name = "growth-%02d.zip".format(next)
            writeSized(File(archives, name), 128 * 1024, ascii = null)
            "archives/$name"
        }
    }

    private suspend fun seedNotes() {
        if (noteDao.count() > 0) return
        val now = System.currentTimeMillis()
        val notes = listOf(
            DemoNoteEntity(
                id = "note-seed-room",
                title = "Room is the app's source of truth",
                body = "Notes are stored in `demo_notes` through the KSP-generated DAO. Saving a note also " +
                    "writes a Markdown attachment into the analyzed tree, so the next hot-monitor probe sees " +
                    "both a database change and a filesystem change.",
                category = "Room",
                updatedAtEpochMs = now - 3 * 60 * 60 * 1000,
                attachmentName = null,
            ),
            DemoNoteEntity(
                id = "note-seed-scanner",
                title = "Why the footprint is not the sum of file lengths",
                body = "The analyzer reports one unique footprint per identity. Directories are structural, " +
                    "hard links decompose to the first path, and cross-provider aliases are deduplicated. " +
                    "That is why the Explorer rows and the headline number are related but not identical.",
                category = "Storage",
                updatedAtEpochMs = now - 2 * 60 * 60 * 1000,
                attachmentName = null,
            ),
            DemoNoteEntity(
                id = "note-seed-accounting",
                title = "Shared resources need an accounting policy",
                body = "The custom SharedPreferences provider deliberately gives `feature_flags` two scope " +
                    "owners. COUNT_ONCE_GLOBALLY counts the bytes once and attributes nothing; EQUAL_SPLIT " +
                    "and CANONICAL_OWNER allocate them. The totals stay conserved either way.",
                category = "Accounting",
                updatedAtEpochMs = now - 60 * 60 * 1000,
                attachmentName = null,
            ),
            DemoNoteEntity(
                id = "note-seed-privacy",
                title = "Unavailable is a result, not zero",
                body = "Every metric carries a state and provenance. If a provider cannot measure something " +
                    "it returns UNAVAILABLE with a reason, and the UI renders that state instead of inventing 0.",
                category = "Measurement",
                updatedAtEpochMs = now - 30 * 60 * 1000,
                attachmentName = null,
            ),
        )
        notes.forEachIndexed { index, note ->
            val attachment = if (index < 2) NoteAttachments.write(environment, note) else null
            noteDao.upsert(note.copy(attachmentName = attachment))
        }
    }

    private fun seedTree() {
        val files = listOf(
            SeedFile("documents/project-brief.md", 24_000, "# Storage Analyzer showcase"),
            SeedFile("documents/design-spec.md", 41_000, "# Demo storage tree"),
            SeedFile("documents/quarterly-report.pdf", 180_000, null),
            SeedFile("documents/notes/sync-notes.md", 9_500, "# Sync notes"),
            SeedFile("media/launch-photo.png", 320_000, null),
            SeedFile("media/team-offsite.jpg", 260_000, null),
            SeedFile("media/podcast-episode.mp3", 420_000, null),
            SeedFile("logs/app.log", 150_000, "2026-10-24 INFO analyzer started"),
            SeedFile("logs/sync.log", 90_000, "2026-10-24 INFO sync completed"),
            SeedFile("sources/Main.kt", 12_000, "fun main() = println(\"showcase\")"),
            SeedFile("sources/StorageRepository.kt", 18_000, "class StorageRepository"),
            SeedFile("sources/schema.sql", 7_000, "CREATE TABLE demo_notes(id TEXT PRIMARY KEY)"),
            SeedFile("archives/legacy-backup.zip", 520_000, null),
            SeedFile("archives/site-export.tar", 140_000, null),
            SeedFile("cache/ui-cache.cache", 110_000, "cached-ui-fragment"),
            SeedFile("cache/build.tmp", 64_000, "temporary-build-output"),
            SeedFile("exports/usage-report.csv", 46_000, "category,bytes"),
            SeedFile("exports/usage-report.json", 38_000, "{\"report\":\"usage\"}"),
        )
        for (seed in files) {
            val file = File(environment.root, seed.relativePath)
            writeSized(file, seed.sizeBytes, seed.ascii)
        }
    }

    private fun writeSized(file: File, sizeBytes: Int, ascii: String?) {
        file.parentFile?.mkdirs()
        val bytes = ByteArray(sizeBytes)
        if (ascii != null) {
            val pattern = (ascii + "\n").toByteArray(Charsets.US_ASCII)
            for (i in bytes.indices) bytes[i] = pattern[i % pattern.size]
        } else {
            var state = sizeBytes.toLong() xor 0x5DEECE66DL
            for (i in bytes.indices) {
                state = (state * 6364136223846793005L + 1442695040888963407L)
                bytes[i] = (state ushr 33).toByte()
            }
        }
        file.writeBytes(bytes)
    }

    private data class SeedFile(
        val relativePath: String,
        val sizeBytes: Int,
        val ascii: String?,
    )

    private companion object {
        const val MARKER = ".showcase-seed"
        const val SEED_VERSION = "v1"
    }
}
