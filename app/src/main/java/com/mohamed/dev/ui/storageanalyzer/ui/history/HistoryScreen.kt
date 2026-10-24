package com.mohamed.dev.ui.storageanalyzer.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mohamed.dev.ui.storageanalyzer.data.model.HistoryPoint
import com.mohamed.dev.ui.storageanalyzer.ui.components.ActionButton
import com.mohamed.dev.ui.storageanalyzer.ui.components.ActionRow
import com.mohamed.dev.ui.storageanalyzer.ui.components.BadgeTone
import com.mohamed.dev.ui.storageanalyzer.ui.components.BulletRow
import com.mohamed.dev.ui.storageanalyzer.ui.components.EmptyState
import com.mohamed.dev.ui.storageanalyzer.ui.components.ErrorBanner
import com.mohamed.dev.ui.storageanalyzer.ui.components.FootprintChart
import com.mohamed.dev.ui.storageanalyzer.ui.components.InfoBanner
import com.mohamed.dev.ui.storageanalyzer.ui.components.KeyValueRow
import com.mohamed.dev.ui.storageanalyzer.ui.components.SectionCard
import com.mohamed.dev.ui.storageanalyzer.ui.components.StatTile
import com.mohamed.dev.ui.storageanalyzer.ui.components.ToneBadge
import com.mohamed.dev.ui.storageanalyzer.ui.format.Formats
import com.mohamed.storageanalyzer.history.GrowthModel
import com.mohamed.storageanalyzer.history.SnapshotComparison
import java.time.Duration
import java.time.Instant

@Composable
fun HistoryScreen(viewModel: HistoryViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val history = state.history

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            TimelineCard(
                state = state,
                onCapture = viewModel::capture,
                onGrow = viewModel::growAndCapture,
                onClear = viewModel::clear,
            )
        }
        state.error?.let { message ->
            item { ErrorBanner(message = message, actionLabel = "Dismiss", onAction = viewModel::dismissMessages) }
        }
        state.notice?.let { message ->
            item { InfoBanner(message = message) }
        }
        history.decodeError?.let { message ->
            item {
                ErrorBanner(
                    message = "A stored snapshot is corrupt: $message",
                    actionLabel = "Clear timeline",
                    onAction = viewModel::clear,
                )
            }
        }
        item { ComparisonCard(comparison = history.comparison, labels = state.labels) }
        item { GrowthCard(growth = history.growth, latest = history.latest) }
        item { SnapshotListCard(points = history.points) }
        item { HistoryNotesCard() }
    }
}

@Composable
private fun TimelineCard(
    state: HistoryUiState,
    onCapture: () -> Unit,
    onGrow: () -> Unit,
    onClear: () -> Unit,
) {
    val points = state.history.points
    SectionCard(
        title = "Snapshot timeline",
        subtitle = "Persisted through the library SnapshotStore contract into Room",
    ) {
        if (points.isEmpty()) {
            EmptyState(
                title = "No snapshots yet",
                message = "A snapshot is a point-in-time summary captured by a full analysis. Capture one to start " +
                    "comparison and growth forecasting.",
                actionLabel = "Capture baseline",
                onAction = onCapture,
            )
        } else {
            FootprintChart(values = points.map { it.snapshot.footprintTotal.value })
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    Formats.dateTime(points.first().capturedAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    Formats.dateTime(points.last().capturedAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val comparison = state.history.comparison
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile(
                    label = "Snapshots",
                    value = points.size.toString(),
                    supporting = "stored rows",
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    label = "Latest",
                    value = Formats.bytes(points.last().snapshot.footprintTotal),
                    supporting = Formats.dateTime(points.last().capturedAt),
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    label = "Change",
                    value = comparison?.let { Formats.signedBytes(it.footprintDeltaBytes) } ?: "—",
                    supporting = "vs previous",
                    modifier = Modifier.weight(1f),
                )
            }
        }
        ActionRow {
            ActionButton(
                label = if (state.isCapturing) "Capturing" else "Capture snapshot",
                onClick = onCapture,
                loading = state.isCapturing,
                icon = Icons.Filled.Refresh,
            )
            ActionButton(
                label = if (state.isGrowing) "Growing" else "Add 128 KiB + capture",
                onClick = onGrow,
                loading = state.isGrowing,
                icon = Icons.Filled.Add,
            )
        }
        if (points.isNotEmpty()) {
            TextButton(onClick = onClear) { Text("Clear timeline") }
        }
        InfoBanner(
            "Each capture runs a complete analysis with per-resource details, so comparisons can attribute changes " +
                "to created, removed and changed identities.",
        )
    }
}

@Composable
private fun ComparisonCard(comparison: SnapshotComparison?, labels: Map<String, String>) {
    SectionCard(
        title = "Latest comparison",
        subtitle = "SnapshotComparison.between(previous, latest)",
    ) {
        if (comparison == null) {
            EmptyState(
                title = "Two snapshots needed",
                message = "Capture another snapshot (or add the 128 KiB demo file first) to compare two points in time.",
            )
            return@SectionCard
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ToneBadge(comparison.accuracy.name, comparisonTone(comparison.accuracy.name))
            ToneBadge("${comparison.from.completenessLabel} → ${comparison.to.completenessLabel}", BadgeTone.Neutral)
        }
        KeyValueRow("From", Formats.dateTime(comparison.fromCapturedAt))
        KeyValueRow("To", Formats.dateTime(comparison.toCapturedAt))
        KeyValueRow("Time span", Formats.duration(Duration.between(comparison.fromCapturedAt, comparison.toCapturedAt)))
        KeyValueRow(
            "Footprint delta",
            Formats.signedBytes(comparison.footprintDeltaBytes),
            valueColor = deltaColor(comparison.footprintDeltaBytes),
        )
        KeyValueRow("Growth", Formats.signedPercent(comparison.footprintGrowthFraction))
        comparison.growthBytesPerSecond?.let {
            KeyValueRow("Rate", Formats.signedBytes(it) + " / s")
        }

        if (comparison.metricDeltas.isNotEmpty()) {
            Text("Metric deltas", style = MaterialTheme.typography.titleSmall)
            comparison.metricDeltas.values.sortedBy { it.metric.id }.forEach { delta ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(Formats.metric(delta.metric), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "${Formats.bytes(delta.fromBytes)} → ${Formats.bytes(delta.toBytes)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        Formats.signedBytes(delta.deltaBytes),
                        style = MaterialTheme.typography.labelLarge,
                        color = deltaColor(delta.deltaBytes),
                    )
                }
            }
        }

        val changedCategories = comparison.categoryDeltas.filterValues { it != 0L }
            .entries.sortedByDescending { kotlin.math.abs(it.value) }
        if (changedCategories.isNotEmpty()) {
            Text("Category deltas", style = MaterialTheme.typography.titleSmall)
            changedCategories.forEach { (category, delta) ->
                KeyValueRow(
                    Formats.category(category),
                    Formats.signedBytes(delta),
                    valueColor = deltaColor(delta),
                )
            }
        }

        val attribution = comparison.attribution
        if (attribution == null) {
            InfoBanner("At least one snapshot was captured without per-resource details, so attribution is unavailable.")
        } else {
            Text("Attribution", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ToneBadge("${attribution.created.size} created", BadgeTone.Primary)
                ToneBadge("${attribution.removed.size} removed", BadgeTone.Warning)
                ToneBadge("${attribution.changed.size} changed", BadgeTone.Neutral)
                ToneBadge("${attribution.unchangedCount} unchanged", BadgeTone.Positive)
            }
            attribution.topChanges(6).forEach { (key, delta) ->
                DeltaResourceRow(
                    label = resourceLabel(key, labels),
                    detail = "changed",
                    delta = delta,
                )
            }
            attribution.created.entries.sortedByDescending { it.value.value }.take(4).forEach { (key, bytes) ->
                DeltaResourceRow(
                    label = resourceLabel(key, labels),
                    detail = "created",
                    delta = bytes.value,
                )
            }
            attribution.removed.entries.sortedByDescending { it.value.value }.take(4).forEach { (key, bytes) ->
                DeltaResourceRow(
                    label = resourceLabel(key, labels),
                    detail = "removed",
                    delta = -bytes.value,
                )
            }
            if (comparison.notComparableMetrics.isNotEmpty()) {
                InfoBanner(
                    "Not comparable: " + comparison.notComparableMetrics.entries.joinToString("; ") {
                        "${it.key.id} — ${it.value}"
                    },
                )
            }
        }
    }
}

@Composable
private fun DeltaResourceRow(label: String, detail: String, delta: Long) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            Formats.signedBytes(delta),
            style = MaterialTheme.typography.labelMedium,
            color = deltaColor(delta),
        )
    }
}

@Composable
private fun GrowthCard(growth: GrowthModel, latest: HistoryPoint?) {
    SectionCard(
        title = "Growth forecast",
        subtitle = "GrowthModelFactory.fit — extrapolation, never a measurement",
    ) {
        when (growth) {
            is GrowthModel.Linear -> {
                val perDay = growth.slopeBytesPerSecond * 86_400.0
                val projectionAt = Instant.now().plus(Duration.ofDays(30))
                val projected = growth.estimateAt(projectionAt)
                val latestBytes = latest?.snapshot?.footprintTotal?.value?.toDouble()
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile(
                        label = "Trend",
                        value = Formats.signedBytes(perDay) + "/day",
                        supporting = "linear slope",
                        modifier = Modifier.weight(1f),
                    )
                    StatTile(
                        label = "R²",
                        value = Formats.percent(growth.r2),
                        supporting = "fit quality",
                        modifier = Modifier.weight(1f),
                    )
                    StatTile(
                        label = "Points",
                        value = growth.pointCount.toString(),
                        supporting = "in the window",
                        modifier = Modifier.weight(1f),
                    )
                }
                KeyValueRow("Accuracy", growth.accuracyKind)
                KeyValueRow("Window starts", Formats.dateTime(growth.windowStart))
                KeyValueRow("Window ends", Formats.dateTime(growth.windowEnd))
                KeyValueRow("30-day estimate", Formats.signedBytes(projected))
                if (latestBytes != null) {
                    KeyValueRow(
                        "Change vs latest",
                        Formats.signedBytes(projected - latestBytes),
                        valueColor = deltaColor((projected - latestBytes).toLong()),
                    )
                }
                InfoBanner(
                    "A negative estimate is allowed when storage is shrinking. The model is not clamped and does not " +
                        "pretend to know the future: R² tells you how well the line fits the observations.",
                )
            }
            is GrowthModel.Unavailable -> {
                EmptyState(
                    title = "No model yet",
                    message = growth.reason,
                )
            }
        }
    }
}

@Composable
private fun SnapshotListCard(points: List<HistoryPoint>) {
    SectionCard(
        title = "Stored snapshots",
        subtitle = "App-owned codec over Room history_snapshots",
    ) {
        if (points.isEmpty()) {
            Text("Nothing stored.", style = MaterialTheme.typography.bodySmall)
            return@SectionCard
        }
        points.reversed().take(8).forEachIndexed { index, point ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(Formats.dateTime(point.capturedAt), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "row ${point.rowId} · ${point.snapshot.resourceCount} resources · " +
                            "${point.snapshot.byResource.size} details",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(Formats.bytes(point.snapshot.footprintTotal), style = MaterialTheme.typography.labelLarge)
                    ToneBadge(
                        point.snapshot.completenessLabel,
                        comparisonTone(point.snapshot.completenessLabel),
                    )
                }
            }
            if (index != minOf(points.size, 8) - 1) HorizontalDivider()
        }
        if (points.size > 8) {
            Text(
                "Showing the 8 newest of ${points.size} stored snapshots.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun HistoryNotesCard() {
    SectionCard(title = "How history works") {
        BulletRow("Snapshots summarize a completed run; they are never estimates themselves.")
        BulletRow("The app encodes the public StorageSnapshot model because the library snapshot format is internal.")
        BulletRow("A comparison is EXACT only when both producing runs were COMPLETE; otherwise it covers the measured subset.")
        BulletRow("Metrics missing from one side are reported as not comparable, never treated as zero growth.")
        BulletRow("Attribution requires per-resource details, which is why captures set includeResourceDetails = true.")
    }
}

private fun resourceLabel(key: String, labels: Map<String, String>): String =
    labels[key] ?: Formats.truncateMiddle(key, 42)

@Composable
private fun deltaColor(delta: Long): Color = when {
    delta > 0 -> MaterialTheme.colorScheme.tertiary
    delta < 0 -> MaterialTheme.colorScheme.secondary
    else -> MaterialTheme.colorScheme.onSurface
}

private fun comparisonTone(name: String): BadgeTone = when (name) {
    "COMPLETE", "EXACT" -> BadgeTone.Positive
    "PARTIAL" -> BadgeTone.Warning
    else -> BadgeTone.Neutral
}
