package com.mohamed.dev.ui.storageanalyzer.ui.overview

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mohamed.dev.ui.storageanalyzer.data.local.ScanSessionEntity
import com.mohamed.dev.ui.storageanalyzer.data.model.CapacityStats
import com.mohamed.dev.ui.storageanalyzer.data.model.MonitorStatus
import com.mohamed.dev.ui.storageanalyzer.data.model.ScanSnapshot
import com.mohamed.dev.ui.storageanalyzer.ui.components.BadgeTone
import com.mohamed.dev.ui.storageanalyzer.ui.components.BarRow
import com.mohamed.dev.ui.storageanalyzer.ui.components.EmptyState
import com.mohamed.dev.ui.storageanalyzer.ui.components.ErrorBanner
import com.mohamed.dev.ui.storageanalyzer.ui.components.InfoBanner
import com.mohamed.dev.ui.storageanalyzer.ui.components.KeyValueRow
import com.mohamed.dev.ui.storageanalyzer.ui.components.LoadingPanel
import com.mohamed.dev.ui.storageanalyzer.ui.components.SectionCard
import com.mohamed.dev.ui.storageanalyzer.ui.components.StatTile
import com.mohamed.dev.ui.storageanalyzer.ui.components.ToneBadge
import com.mohamed.dev.ui.storageanalyzer.ui.format.Formats
import com.mohamed.storageanalyzer.core.measurement.MetricType
import com.mohamed.storageanalyzer.core.result.GlobalReport
import com.mohamed.storageanalyzer.core.resource.StorageCategory

@Composable
fun OverviewScreen(
    onOpenExplorer: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenGuide: () -> Unit,
    viewModel: OverviewViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            FootprintHero(
                state = state,
                onRefresh = viewModel::refresh,
                onGrow = viewModel::growDemo,
            )
        }
        state.error?.let { message ->
            item { ErrorBanner(message = message, onAction = viewModel::dismissMessages) }
        }
        state.notice?.let { message ->
            item { InfoBanner(message = message) }
        }
        item { SummaryTiles(state.scan) }
        item {
            CapacityCard(
                capacity = state.capacity,
                error = state.capacityError,
                onRetry = viewModel::reloadCapacity,
            )
        }
        item {
            MonitorCard(
                monitor = state.monitor,
                onToggle = viewModel::setMonitoring,
            )
        }
        item { CategoriesCard(report = state.scan?.report, onOpenGuide = onOpenGuide) }
        item { ProvidersCard(report = state.scan?.report) }
        item { TopResourcesCard(scan = state.scan, onOpenExplorer = onOpenExplorer) }
        item { SessionsCard(sessions = state.sessions, onOpenHistory = onOpenHistory) }
        item { PathsCard(demoRoot = state.demoRoot, databasePath = state.databasePath) }
    }
}

@Composable
private fun FootprintHero(
    state: OverviewUiState,
    onRefresh: () -> Unit,
    onGrow: () -> Unit,
) {
    val report = state.scan?.report
    Surface(
        shape = RoundedCornerShape(26.dp),
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .background(
                    Brush.linearGradient(
                        listOf(
                            MaterialTheme.colorScheme.primary,
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.82f),
                        ),
                    ),
                )
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "UNIQUE FOOTPRINT",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.72f),
            )
            Text(
                text = report?.let { Formats.bytes(it.totals.uniqueFootprint) } ?: "—",
                style = MaterialTheme.typography.displaySmall,
                maxLines = 1,
            )
            if (report == null) {
                Text(
                    if (state.isRefreshing) "Analyzing the demo inventory…" else "No completed scan yet",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f),
                )
            } else {
                Text(
                    "${report.resourceCount} unique resources across ${report.providers.size} providers · " +
                        Formats.duration(report.duration),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ToneBadge(report.completeness.name, completenessTone(report))
                    ToneBadge(Formats.humanize(report.accountingPolicy.policyId))
                    ToneBadge(Formats.dateTime(state.scan?.capturedAt))
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.22f))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = onRefresh,
                    enabled = !state.isRefreshing && !state.isGrowing,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.onPrimary,
                        contentColor = MaterialTheme.colorScheme.primary,
                    ),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    if (state.isRefreshing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    } else {
                        Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                    Text(
                        if (state.isRefreshing) "Scanning…" else "Rescan",
                        modifier = Modifier.padding(start = 8.dp),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                OutlinedButton(
                    onClick = onGrow,
                    enabled = !state.isRefreshing && !state.isGrowing,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.55f)),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    if (state.isGrowing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                    Text(
                        if (state.isGrowing) "Growing…" else "Add 128 KiB",
                        modifier = Modifier.padding(start = 8.dp),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
            Text(
                "Adding a file changes the inventory and gives snapshots and the live monitor something real to react to.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f),
            )
        }
    }
}

@Composable
private fun SummaryTiles(scan: ScanSnapshot?) {
    val report = scan?.report ?: return
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(
                label = "Resources",
                value = report.resourceCount.toString(),
                supporting = "unique identities",
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = "Duration",
                value = Formats.duration(report.duration),
                supporting = "measured run",
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = "Providers",
                value = report.providers.size.toString(),
                supporting = "composed together",
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(
                label = "Unmeasured",
                value = Formats.bytes(report.totals.unmeasuredFootprint),
                supporting = "explicitly unavailable",
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = "Shared only",
                value = Formats.bytes(report.totals.sharedOnly),
                supporting = "not attributed",
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = "Attributed",
                value = Formats.bytes(report.totals.attributedShared),
                supporting = "shared bytes assigned",
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun CapacityCard(capacity: CapacityStats?, error: String?, onRetry: () -> Unit) {
    SectionCard(
        title = "Volume capacity",
        subtitle = "Read through the filesystem volume facade, not the demo folder",
    ) {
        when {
            error != null -> ErrorBanner(message = error, onAction = onRetry)
            capacity == null -> LoadingPanel("Reading capacity metrics…")
            else -> {
                val used = capacity.usedBytes
                val total = capacity.totalBytes
                val fraction = capacity.usedFraction
                if (fraction != null && used != null && total != null) {
                    BarRow(
                        label = "Used",
                        value = Formats.bytes(used),
                        fraction = fraction,
                        supporting = Formats.percent(fraction) + " of " + Formats.bytes(total),
                    )
                } else {
                    Text(
                        "Capacity is currently unavailable; the analyzer reports that state instead of inventing a total.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                KeyValueRow("Free", capacity.freeBytes?.let { Formats.bytes(it) } ?: "Unavailable")
                KeyValueRow("Total", capacity.totalBytes?.let { Formats.bytes(it) } ?: "Unavailable")
                KeyValueRow("Reserved", capacity.reservedBytes?.let { Formats.bytes(it) } ?: "Unavailable")
                KeyValueRow("Method", capacity.methodId ?: "—", mono = true)
                KeyValueRow("Accuracy", capacity.accuracy ?: "—")
                capacity.reason?.let { KeyValueRow("Reason", it) }
                KeyValueRow("Measured", Formats.dateTime(capacity.measuredAt))
                InfoBanner(
                    "These are volume-level numbers. The demo footprint is small because it counts only the showcase tree " +
                        "and the app database directory.",
                )
            }
        }
    }
}

@Composable
private fun MonitorCard(monitor: MonitorStatus, onToggle: (Boolean) -> Unit) {
    SectionCard(
        title = "Live monitoring",
        subtitle = "ReactiveAnalyzer + RefreshPolicy.OnChange",
        action = {
            Switch(checked = monitor.enabled, onCheckedChange = onToggle)
        },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ToneBadge(if (monitor.enabled) "Enabled" else "Disabled", if (monitor.enabled) BadgeTone.Positive else BadgeTone.Neutral)
            ToneBadge(monitor.phase, if (monitor.phase == "Failed") BadgeTone.Negative else BadgeTone.Primary)
            monitor.digest?.let { ToneBadge("digest ${Formats.digest(it, 10)}", BadgeTone.Neutral) }
        }
        Text(
            "A lightweight fingerprint (paths, lengths, mtimes, DB sidecars, preference values) is probed every 3 seconds. " +
                "Only a changed fingerprint triggers a new analysis, and identical result digests are suppressed.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (monitor.footprintBytes != null) {
            KeyValueRow("Last footprint", Formats.bytes(monitor.footprintBytes))
        }
        monitor.resourceCount?.let { KeyValueRow("Resources", it.toString()) }
        KeyValueRow("Completed turns", monitor.turns.toString())
        KeyValueRow("Last completion", Formats.dateTime(monitor.lastCompletedAt))
        monitor.error?.let { ErrorBanner(message = it, actionLabel = null) }
        InfoBanner("The monitor owns a separate analyzer instance, so it can never race an interactive scan.")
    }
}

@Composable
private fun CategoriesCard(report: GlobalReport?, onOpenGuide: () -> Unit) {
    SectionCard(
        title = "Footprint by category",
        subtitle = "Unique resources grouped by consumer-facing bucket",
        action = {
            TextButton(onClick = onOpenGuide) { Text("How") }
        },
    ) {
        if (report == null) {
            Text("Waiting for the first scan.", style = MaterialTheme.typography.bodySmall)
            return@SectionCard
        }
        val rows = report.byCategory.mapNotNull { breakdown ->
            val category = runCatching { StorageCategory.valueOf(breakdown.key) }.getOrNull()
                ?: return@mapNotNull null
            val bytes = breakdown[MetricType.STORED_CONTENT_SIZE] ?: return@mapNotNull null
            Triple(category, bytes, breakdown.resourceCount)
        }.filter { it.second.value > 0 }
            .sortedByDescending { it.second.value }
        if (rows.isEmpty()) {
            EmptyState("Nothing categorized", "The current scan produced no footprint-bearing categories.")
            return@SectionCard
        }
        val total = report.totals.uniqueFootprint.value.coerceAtLeast(1L)
        rows.forEach { (category, bytes, count) ->
            BarRow(
                label = Formats.category(category),
                value = Formats.bytes(bytes),
                fraction = bytes.value.toFloat() / total.toFloat(),
                color = categoryColor(category),
                supporting = "$count resources · ${Formats.percent(bytes.value.toDouble() / total)}",
            )
        }
    }
}

@Composable
private fun ProvidersCard(report: GlobalReport?) {
    SectionCard(
        title = "Providers in this run",
        subtitle = "Provider views include duplicate entries; the unique footprint does not",
    ) {
        if (report == null) {
            Text("Waiting for the first scan.", style = MaterialTheme.typography.bodySmall)
            return@SectionCard
        }
        report.providers.forEachIndexed { index, summary ->
            val bytes = summary.breakdown[MetricType.STORED_CONTENT_SIZE]
                ?: summary.breakdown.byMetric.values.firstOrNull()
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        summary.providerId.value,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (summary.deduplicatedReferenceCount > 0) {
                        ToneBadge("${summary.deduplicatedReferenceCount} deduped", BadgeTone.Primary)
                    }
                    Text(
                        bytes?.let { Formats.bytes(it) } ?: "—",
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                Text(
                    summary.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (index != report.providers.lastIndex) HorizontalDivider()
        }
        if (report.overlapMeasurements.isNotEmpty()) {
            InfoBanner(
                "${report.overlapMeasurements.size} overlapping measurement(s) were deduplicated. " +
                    "They appear in provider views but are counted once in the unique footprint.",
            )
        }
    }
}

@Composable
private fun TopResourcesCard(scan: ScanSnapshot?, onOpenExplorer: () -> Unit) {
    SectionCard(
        title = "Largest measured resources",
        subtitle = "One row per unique identity",
        action = {
            TextButton(onClick = onOpenExplorer) { Text("Explorer") }
        },
    ) {
        if (scan == null || scan.rows.isEmpty()) {
            Text("No measured rows yet.", style = MaterialTheme.typography.bodySmall)
            return@SectionCard
        }
        scan.rows.take(6).forEachIndexed { index, row ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenExplorer)
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        row.label,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        row.pathLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                ToneBadge(Formats.category(row.category), BadgeTone.Neutral)
                Text(Formats.bytes(row.bytes), style = MaterialTheme.typography.labelLarge)
            }
            if (index != minOf(scan.rows.size, 6) - 1) HorizontalDivider()
        }
        Text(
            "Showing 6 of ${scan.rows.size} measured identities. The Explorer adds search, categories and per-resource provenance.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SessionsCard(sessions: List<ScanSessionEntity>, onOpenHistory: () -> Unit) {
    SectionCard(
        title = "Recent scans",
        subtitle = "Persisted in Room by the app",
        action = {
            TextButton(onClick = onOpenHistory) { Text("History") }
        },
    ) {
        if (sessions.isEmpty()) {
            Text("No sessions recorded yet.", style = MaterialTheme.typography.bodySmall)
            return@SectionCard
        }
        sessions.take(5).forEachIndexed { index, session ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        Formats.humanize(session.trigger),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "${Formats.dateTime(java.time.Instant.ofEpochMilli(session.startedAtEpochMs))} · " +
                            "${Formats.durationMs(session.durationMs)} · ${session.providerCount} providers",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(Formats.bytes(session.footprintBytes), style = MaterialTheme.typography.labelLarge)
                    Text(
                        "${session.resourceCount} res",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (index != minOf(sessions.size, 5) - 1) HorizontalDivider()
        }
    }
}

@Composable
private fun PathsCard(demoRoot: String, databasePath: String) {
    SectionCard(
        title = "Where the demo lives",
        subtitle = "Both locations are roots of the filesystem provider",
    ) {
        Text("Demo tree", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(demoRoot, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        Text("App database", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(databasePath, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        Text(
            "Use `adb shell run-as com.mohamed.dev.ui.storageanalyzer ls` to inspect files on a debug build.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun completenessTone(report: GlobalReport): BadgeTone = when {
    report.completeness.name == "COMPLETE" -> BadgeTone.Positive
    report.completeness.name == "PARTIAL" -> BadgeTone.Warning
    else -> BadgeTone.Neutral
}

@Composable
private fun categoryColor(category: StorageCategory): Color = when (category) {
    StorageCategory.CACHE, StorageCategory.TEMPORARY, StorageCategory.LOGS -> MaterialTheme.colorScheme.tertiary
    StorageCategory.APPLICATION_DATABASE, StorageCategory.DATABASE -> MaterialTheme.colorScheme.secondary
    else -> MaterialTheme.colorScheme.primary
}
