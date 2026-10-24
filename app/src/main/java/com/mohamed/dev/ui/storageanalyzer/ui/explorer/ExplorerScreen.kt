package com.mohamed.dev.ui.storageanalyzer.ui.explorer

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mohamed.dev.ui.storageanalyzer.data.model.EntityFolderInsight
import com.mohamed.dev.ui.storageanalyzer.data.model.ResourceRow
import com.mohamed.dev.ui.storageanalyzer.ui.components.ActionButton
import com.mohamed.dev.ui.storageanalyzer.ui.components.BadgeTone
import com.mohamed.dev.ui.storageanalyzer.ui.components.EmptyState
import com.mohamed.dev.ui.storageanalyzer.ui.components.ErrorBanner
import com.mohamed.dev.ui.storageanalyzer.ui.components.InfoBanner
import com.mohamed.dev.ui.storageanalyzer.ui.components.KeyValueRow
import com.mohamed.dev.ui.storageanalyzer.ui.components.LoadingPanel
import com.mohamed.dev.ui.storageanalyzer.ui.components.SearchField
import com.mohamed.dev.ui.storageanalyzer.ui.components.SectionCard
import com.mohamed.dev.ui.storageanalyzer.ui.components.StatTile
import com.mohamed.dev.ui.storageanalyzer.ui.components.ToneBadge
import com.mohamed.dev.ui.storageanalyzer.ui.format.Formats
import com.mohamed.storageanalyzer.core.measurement.ByteCount
import com.mohamed.storageanalyzer.core.resource.StorageCategory

@Composable
fun ExplorerScreen(
    onOpenResource: (String) -> Unit,
    viewModel: ExplorerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val filtered = state.filteredRows

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            InventoryCard(
                state = state,
                onRefresh = viewModel::refresh,
            )
        }
        state.error?.let { message ->
            item { ErrorBanner(message = message, onAction = viewModel::dismissError) }
        }
        item {
            SearchField(
                query = state.query,
                onQueryChange = viewModel::setQuery,
                placeholder = "Search name, path, native id or provider",
            )
        }
        if (state.categories.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = state.category == null,
                        onClick = { viewModel.setCategory(null) },
                        label = { Text("All") },
                    )
                    state.categories.forEach { category ->
                        FilterChip(
                            selected = state.category == category,
                            onClick = { viewModel.setCategory(category) },
                            label = { Text(Formats.category(category)) },
                        )
                    }
                }
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${filtered.size} rows · ${Formats.bytes(state.filteredBytes)}",
                    style = MaterialTheme.typography.labelLarge,
                )
                if (state.query.isNotBlank() || state.category != null) {
                    Text(
                        "Filtered",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (filtered.isEmpty()) {
            item {
                EmptyState(
                    title = "No matching resources",
                    message = if (state.rows.isEmpty()) {
                        "Run a scan from the Overview screen, then return here."
                    } else {
                        "Adjust the search text or clear the category filter."
                    },
                )
            }
        } else {
            items(filtered, key = { it.stableKey }) { row ->
                ResourceCard(row = row, onClick = { onOpenResource(row.stableKey) })
            }
        }
        item {
            EntityFoldersCard(
                state = state,
                onLoad = viewModel::loadEntityFolders,
            )
        }
        item { ExplorerNotesCard() }
    }
}

@Composable
private fun InventoryCard(state: ExplorerUiState, onRefresh: () -> Unit) {
    SectionCard(
        title = "Inventory",
        subtitle = "Latest scan, one row per unique identity",
        action = {
            ActionButton(
                label = if (state.isScanning) "Scanning" else "Rescan",
                onClick = onRefresh,
                loading = state.isScanning,
                icon = Icons.Filled.Refresh,
            )
        },
    ) {
        val listedBytes = ByteCount.sum(state.rows.map { it.bytes })
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(
                label = "Rows",
                value = state.rows.size.toString(),
                supporting = "measured identities",
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = "Row sum",
                value = Formats.bytes(listedBytes),
                supporting = "not an accounting total",
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = "Unique",
                value = Formats.bytes(state.uniqueFootprint),
                supporting = "report footprint",
                modifier = Modifier.weight(1f),
            )
        }
        KeyValueRow("Captured", Formats.dateTime(state.capturedAt))
        KeyValueRow("Report resources", state.resourceCount.toString())
        InfoBanner(
            "Rows come from the report's measured resources. Directories are structural, hard links decompose to " +
                "one path, and overlapping provider entries are deduplicated before the unique footprint is computed.",
        )
    }
}

@Composable
private fun ResourceCard(row: ResourceRow, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    row.label,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    row.pathLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ToneBadge(Formats.category(row.category), BadgeTone.Neutral)
                    ToneBadge(Formats.kind(row.kind), BadgeTone.Neutral)
                    ToneBadge(row.providerId, BadgeTone.Primary)
                }
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(Formats.bytes(row.bytes), style = MaterialTheme.typography.labelLarge)
                Icon(
                    Icons.Filled.ChevronRight,
                    contentDescription = "Open details",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun EntityFoldersCard(state: ExplorerUiState, onLoad: () -> Unit) {
    SectionCard(
        title = "Entity analytics",
        subtitle = "Folders analyzed as entities through provider relations",
    ) {
        Text(
            "Each demo folder is turned into an EntityDescriptor and analyzed with analyzeEntity. The five views " +
                "separate immediate resources from the whole subtree and from resources shared with other owners.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ActionButton(
            label = if (state.foldersLoaded) "Re-analyze folders" else "Analyze demo folders",
            onClick = onLoad,
            loading = state.isLoadingFolders,
        )
        state.folderError?.let { ErrorBanner(message = it, actionLabel = null) }
        if (state.foldersLoaded && state.folders.isEmpty() && state.folderError == null) {
            EmptyState(
                title = "No folder entities",
                message = "The demo tree has no folders, or the filesystem provider returned no containing relations.",
            )
        }
        state.folders.forEachIndexed { index, insight ->
            EntityFolderBlock(insight)
            if (index != state.folders.lastIndex) HorizontalDivider()
        }
        if (state.folders.isNotEmpty()) {
            InfoBanner(
                "Inclusive counts the subtree, direct counts only resources directly contained, unique reachable " +
                    "removes duplicates, and shared allocated follows the active accounting policy.",
            )
        }
    }
}

@Composable
private fun EntityFolderBlock(insight: EntityFolderInsight) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                insight.folderName,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
                fontFamily = FontFamily.Monospace,
            )
            ToneBadge("${insight.report.uniqueResourceCount} resources", BadgeTone.Neutral)
            ToneBadge(insight.report.completeness.name, completenessTone(insight.report.completeness.name))
        }
        KeyValueRow("Inclusive", Formats.bytes(insight.inclusiveBytes))
        KeyValueRow("Direct", Formats.bytes(insight.directBytes))
        KeyValueRow("Unique reachable", Formats.bytes(insight.uniqueReachableBytes))
        KeyValueRow("Shared allocated", Formats.bytes(insight.sharedAllocatedBytes))
    }
}

@Composable
private fun ExplorerNotesCard() {
    SectionCard(title = "Reading the explorer") {
        InfoBanner(
            "Tap any row to measure that exact identity again. The detail screen lists every requested metric, its " +
                "state and its provenance — including why an unavailable metric is not shown as zero.",
        )
        Text(
            "Search also matches the provider id, so `x_` finds the archive catalog and preference entries.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun completenessTone(name: String): BadgeTone = when (name) {
    "COMPLETE" -> BadgeTone.Positive
    "PARTIAL" -> BadgeTone.Warning
    else -> BadgeTone.Neutral
}
