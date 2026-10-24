package com.mohamed.dev.ui.storageanalyzer.ui.database

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mohamed.dev.ui.storageanalyzer.data.local.DemoNoteEntity
import com.mohamed.dev.ui.storageanalyzer.data.model.DatabaseStats
import com.mohamed.dev.ui.storageanalyzer.data.model.NoteRow
import com.mohamed.dev.ui.storageanalyzer.ui.components.ActionButton
import com.mohamed.dev.ui.storageanalyzer.ui.components.ActionRow
import com.mohamed.dev.ui.storageanalyzer.ui.components.BadgeTone
import com.mohamed.dev.ui.storageanalyzer.ui.components.BulletRow
import com.mohamed.dev.ui.storageanalyzer.ui.components.CodeBlock
import com.mohamed.dev.ui.storageanalyzer.ui.components.EmptyState
import com.mohamed.dev.ui.storageanalyzer.ui.components.ErrorBanner
import com.mohamed.dev.ui.storageanalyzer.ui.components.InfoBanner
import com.mohamed.dev.ui.storageanalyzer.ui.components.KeyValueRow
import com.mohamed.dev.ui.storageanalyzer.ui.components.LoadingPanel
import com.mohamed.dev.ui.storageanalyzer.ui.components.SectionCard
import com.mohamed.dev.ui.storageanalyzer.ui.components.StatTile
import com.mohamed.dev.ui.storageanalyzer.ui.components.ToneBadge
import com.mohamed.dev.ui.storageanalyzer.ui.format.Formats

@Composable
fun DatabaseScreen(viewModel: DatabaseViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var pendingDelete by remember { mutableStateOf<DemoNoteEntity?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        state.error?.let { message ->
            item { ErrorBanner(message = message, actionLabel = "Dismiss", onAction = viewModel::dismissMessages) }
        }
        state.notice?.let { message ->
            item { InfoBanner(message = message) }
        }
        item {
            NotesCard(
                notes = state.notes,
                onCreate = viewModel::openCreate,
                onEdit = viewModel::openEdit,
                onDelete = { pendingDelete = it },
            )
        }
        item {
            StatsCard(
                stats = state.stats,
                isLoading = state.isStatsLoading,
                isCheckingPoint = state.isCheckingPoint,
                onRefresh = viewModel::refreshStats,
                onCheckpoint = viewModel::checkpoint,
            )
        }
        item { RoomExplainerCard() }
        item { WhyRescanCard() }
    }

    if (state.isEditorOpen) {
        NoteEditorDialog(
            note = state.editorNote,
            saving = state.isSaving,
            onDismiss = viewModel::closeEditor,
            onSave = viewModel::save,
        )
    }

    pendingDelete?.let { note ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete note?") },
            text = {
                Text(
                    "The Room row and its Markdown attachment will be deleted, then the inventory is rescanned. " +
                        "This cannot be undone.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.delete(note)
                        pendingDelete = null
                    },
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun NotesCard(
    notes: List<NoteRow>,
    onCreate: () -> Unit,
    onEdit: (DemoNoteEntity) -> Unit,
    onDelete: (DemoNoteEntity) -> Unit,
) {
    SectionCard(
        title = "Room notes (${notes.size})",
        subtitle = "KSP-generated DAO; saving also writes Markdown into the analyzed tree",
        action = {
            ActionButton(label = "New note", onClick = onCreate, icon = Icons.Filled.Add)
        },
    ) {
        if (notes.isEmpty()) {
            EmptyState(
                title = "No notes",
                message = "Create a note to see Room persistence and a filesystem side effect in the same action.",
                actionLabel = "New note",
                onAction = onCreate,
            )
            return@SectionCard
        }
        notes.forEachIndexed { index, row ->
            val note = row.note
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        note.title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        note.body,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ToneBadge(note.category, BadgeTone.Primary)
                        if (row.attachmentBytes > 0) {
                            ToneBadge("attachment ${Formats.bytes(row.attachmentBytes)}", BadgeTone.Neutral)
                        } else {
                            ToneBadge("database only", BadgeTone.Neutral)
                        }
                    }
                    Text(
                        note.id,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        "updated ${Formats.dateTime(java.time.Instant.ofEpochMilli(note.updatedAtEpochMs))}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column {
                    IconButton(onClick = { onEdit(note) }) {
                        Icon(Icons.Filled.Edit, contentDescription = "Edit note")
                    }
                    IconButton(onClick = { onDelete(note) }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete note")
                    }
                }
            }
            if (index != notes.lastIndex) HorizontalDivider()
        }
        InfoBanner(
            "Each save rewrites `attachments/<note id>.md` and rescanning picks up both the file and the Room " +
                "database sidecars.",
        )
    }
}

@Composable
private fun StatsCard(
    stats: DatabaseStats?,
    isLoading: Boolean,
    isCheckingPoint: Boolean,
    onRefresh: () -> Unit,
    onCheckpoint: () -> Unit,
) {
    SectionCard(
        title = "On-device SQLite",
        subtitle = "File sizes, pragmas and row counts read from Android's SQLite",
    ) {
        when {
            stats == null && isLoading -> LoadingPanel("Opening the Room database…")
            stats == null -> Text(
                "Database statistics are unavailable.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile(
                        label = "Database",
                        value = Formats.bytes(stats.databaseBytes),
                        supporting = "showcase.db",
                        modifier = Modifier.weight(1f),
                    )
                    StatTile(
                        label = "WAL",
                        value = Formats.bytes(stats.walBytes),
                        supporting = "write-ahead log",
                        modifier = Modifier.weight(1f),
                    )
                    StatTile(
                        label = "SHM",
                        value = Formats.bytes(stats.shmBytes),
                        supporting = "shared memory",
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile(
                        label = "Page size",
                        value = "${stats.pageSize} B",
                        supporting = "SQLite page",
                        modifier = Modifier.weight(1f),
                    )
                    StatTile(
                        label = "Pages",
                        value = stats.pageCount.toString(),
                        supporting = "allocated",
                        modifier = Modifier.weight(1f),
                    )
                    StatTile(
                        label = "Free pages",
                        value = stats.freePages.toString(),
                        supporting = "${stats.freeBytes} B",
                        modifier = Modifier.weight(1f),
                    )
                }
                KeyValueRow("Page capacity", Formats.bytes(stats.pageCapacityBytes))
                KeyValueRow("Journal mode", stats.journalMode)
                KeyValueRow("user_version", stats.userVersion.toString())
                KeyValueRow("Schema version", ShowcaseSchemaVersion.toString())
                KeyValueRow("Sidecars", Formats.bytes(stats.sidecarBytes))
                KeyValueRow("Measured", Formats.dateTime(stats.measuredAt))

                Text("Tables", style = MaterialTheme.typography.titleSmall)
                stats.tables.forEach { table ->
                    KeyValueRow(
                        table.name,
                        if (table.rowCount >= 0) "${table.rowCount} rows" else "count unavailable",
                        mono = true,
                    )
                }
                KeyValueRow(
                    "Indexes",
                    if (stats.indexes.isEmpty()) "none" else stats.indexes.joinToString(", "),
                    mono = true,
                )
                ActionRow {
                    ActionButton(
                        label = "Refresh",
                        onClick = onRefresh,
                        loading = isLoading,
                        icon = Icons.Filled.Refresh,
                    )
                    ActionButton(
                        label = "WAL checkpoint",
                        onClick = onCheckpoint,
                        loading = isCheckingPoint,
                    )
                }
                InfoBanner(
                    "WAL and SHM are real bytes in the scanned database directory. Checkpointing can reclaim the WAL " +
                        "while the database file keeps growing in page-size steps.",
                )
            }
        }
    }
}

private const val ShowcaseSchemaVersion = 1

@Composable
private fun RoomExplainerCard() {
    SectionCard(title = "Two different Room stories") {
        BulletRow(
            "This screen uses androidx Room + KSP in the app. The exported schema lives in the app module and the " +
                "DAOs drive Compose through Flow.",
        )
        BulletRow(
            "The library's `:room` module is not the same thing: it inspects arbitrary SQLite files over JDBC and " +
                "adds page accounting and entity analytics.",
        )
        BulletRow(
            "`:sqlite` implements the JDBC inspection; `:room` wraps it as a provider. Both are JVM-only, so the " +
                "showcase verifies them in JVM tests against this app's exported schema.",
        )
        CodeBlock(
            "app/schemas/com.mohamed.dev.ui.storageanalyzer.data.local.ShowcaseDatabase/1.json",
        )
    }
}

@Composable
private fun WhyRescanCard() {
    SectionCard(title = "Why a note triggers a rescan") {
        Text(
            "A create/update/delete changes demo_notes, the WAL and often the attachment file. The filesystem provider " +
                "sees the attachment and database sidecars; the history store sees a new scan session. Re-running the " +
                "analysis keeps every screen consistent with the same observation moment.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "With live monitoring enabled on the Overview screen, the fingerprint probe would also detect these " +
                "changes without an explicit rescan.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun NoteEditorDialog(
    note: DemoNoteEntity?,
    saving: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, String, String) -> Unit,
) {
    var title by rememberSaveable(note?.id) { mutableStateOf(note?.title.orEmpty()) }
    var category by rememberSaveable(note?.id) { mutableStateOf(note?.category.orEmpty()) }
    var body by rememberSaveable(note?.id) { mutableStateOf(note?.body.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (note == null) "New note" else "Edit note") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = category,
                    onValueChange = { category = it },
                    label = { Text("Category") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = body,
                    onValueChange = { body = it },
                    label = { Text("Body") },
                    minLines = 4,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(title, body, category) },
                enabled = title.isNotBlank() && body.isNotBlank() && !saving,
            ) {
                Text(if (saving) "Saving…" else "Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) { Text("Cancel") }
        },
    )
}
