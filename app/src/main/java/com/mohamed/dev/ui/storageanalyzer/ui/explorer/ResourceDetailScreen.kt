package com.mohamed.dev.ui.storageanalyzer.ui.explorer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import com.mohamed.dev.ui.storageanalyzer.data.model.ResourceRow
import com.mohamed.dev.ui.storageanalyzer.ui.components.ActionButton
import com.mohamed.dev.ui.storageanalyzer.ui.components.BadgeTone
import com.mohamed.dev.ui.storageanalyzer.ui.components.EmptyState
import com.mohamed.dev.ui.storageanalyzer.ui.components.ErrorBanner
import com.mohamed.dev.ui.storageanalyzer.ui.components.InfoBanner
import com.mohamed.dev.ui.storageanalyzer.ui.components.KeyValueRow
import com.mohamed.dev.ui.storageanalyzer.ui.components.LoadingPanel
import com.mohamed.dev.ui.storageanalyzer.ui.components.SectionCard
import com.mohamed.dev.ui.storageanalyzer.ui.components.ToneBadge
import com.mohamed.dev.ui.storageanalyzer.ui.format.Formats
import com.mohamed.storageanalyzer.core.measurement.MeasurementState
import com.mohamed.storageanalyzer.core.measurement.SingleMeasurement
import com.mohamed.storageanalyzer.core.result.StorageMeasurement

@Composable
fun ResourceDetailScreen(
    onBack: () -> Unit,
    viewModel: ResourceDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val row = state.row
        if (row == null) {
            item {
                if (state.notFound) {
                    EmptyState(
                        title = "Resource not in the current inventory",
                        message = "The identity is absent from the latest scan. It may have been removed, or a new scan has not completed yet.",
                        actionLabel = "Back to explorer",
                        onAction = onBack,
                    )
                } else {
                    LoadingPanel("Loading the resource from the latest scan…")
                }
            }
        } else {
            item { IdentityCard(row) }
            item {
                MeasurementCard(
                    state = state,
                    onMeasure = viewModel::measure,
                )
            }
            item { ProvenanceHelpCard() }
        }
    }
}

@Composable
private fun IdentityCard(row: ResourceRow) {
    val identity = row.identity
    SectionCard(
        title = row.label,
        subtitle = row.pathLabel,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ToneBadge(Formats.kind(row.kind), BadgeTone.Primary)
            ToneBadge(Formats.category(row.category), BadgeTone.Neutral)
            ToneBadge(identity.stability.name, if (identity.stability.name == "STABLE") BadgeTone.Positive else BadgeTone.Warning)
        }
        Text(
            "Footprint in the latest scan: ${Formats.bytes(row.bytes)}",
            style = MaterialTheme.typography.titleSmall,
        )
        KeyValueRow("Provider", identity.providerId.value, mono = true)
        KeyValueRow("System", identity.systemId.value, mono = true)
        KeyValueRow("Native id", identity.nativeId, mono = true)
        KeyValueRow("Namespace", identity.namespace ?: "—", mono = true)
        KeyValueRow("Generation", identity.generation ?: "—", mono = true)
        KeyValueRow("Container", row.containerPath)
        Text("Stable key", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            identity.stableKey,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
        InfoBanner(
            "The stable key percent-encodes provider, system, kind, namespace, native id and generation. Two different " +
                "identities can never share a key, which is what makes deduplication and history attribution safe.",
        )
    }
}

@Composable
private fun MeasurementCard(
    state: ResourceDetailUiState,
    onMeasure: () -> Unit,
) {
    val measurement = state.measurement
    SectionCard(
        title = "Measurement",
        subtitle = "Requested metrics, states and provenance",
        action = {
            ActionButton(
                label = "Measure",
                onClick = onMeasure,
                loading = state.isLoading,
                icon = Icons.Filled.Refresh,
            )
        },
    ) {
        state.error?.let { ErrorBanner(message = it, onAction = onMeasure) }
        when {
            state.isLoading && measurement == null -> LoadingPanel("Measuring all requested metrics…")
            measurement == null -> Text(
                "No measurement yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> {
                MeasurementBody(measurement)
            }
        }
    }
}

@Composable
private fun MeasurementBody(measurement: StorageMeasurement) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ToneBadge(measurement.completeness.name, completenessTone(measurement.completeness.name))
        ToneBadge("${measurement.measurements.size} metrics", BadgeTone.Neutral)
        if (measurement.missing().isNotEmpty()) {
            ToneBadge("${measurement.missing().size} unavailable", BadgeTone.Warning)
        }
    }
    KeyValueRow("Measured at", Formats.dateTime(measurement.measuredAt))
    measurement.scope?.let { KeyValueRow("Scope", it.id, mono = true) }
    if (measurement.owners.isNotEmpty()) {
        KeyValueRow("Owners", measurement.owners.joinToString { it.id }, mono = true)
    }

    val ordered = measurement.measurements.values.sortedBy { it.metric.id }
    ordered.forEachIndexed { index, single ->
        MetricBlock(single)
        if (index != ordered.lastIndex) HorizontalDivider()
    }

    if (measurement.missing().isNotEmpty()) {
        InfoBanner(
            "Unavailable metrics are part of the result. Their reasons are captured below rather than replaced by a " +
                "zero that would silently distort totals.",
        )
    }
}

@Composable
private fun MetricBlock(single: SingleMeasurement) {
    val measured = single.state == MeasurementState.MEASURED
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                Formats.metric(single.metric),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
            )
            ToneBadge(Formats.state(single.state), stateTone(single.state))
            if (measured) {
                Text(Formats.bytes(single.value!!), style = MaterialTheme.typography.labelLarge)
            }
        }
        if (!measured) {
            Text(
                single.reason ?: "no reason reported",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            ProvenanceLine("Method", single.provenance.method.id, mono = true)
            ProvenanceLine("Accuracy", single.provenance.accuracy.name)
            ProvenanceLine("Provider", single.provenance.providerId, mono = true)
            single.provenance.backend?.let { ProvenanceLine("Backend", it) }
            single.provenance.note?.let { ProvenanceLine("Note", it) }
            if (single.provenance.limitations.isNotEmpty()) {
                ProvenanceLine("Limitations", single.provenance.limitations.joinToString(", "), mono = true)
            }
            if (single.provenance.warnings.isNotEmpty()) {
                ProvenanceLine("Warnings", single.provenance.warnings.joinToString(", "))
            }
            single.provenance.uncertainty?.let { uncertainty ->
                ProvenanceLine(
                    "Uncertainty",
                    "${Formats.bytes(uncertainty.lowerInclusive)} – ${Formats.bytes(uncertainty.upperInclusive)}",
                )
            }
        }
    }
}

@Composable
private fun ProvenanceLine(label: String, value: String, mono: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            label,
            modifier = Modifier.weight(0.3f),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            modifier = Modifier.weight(0.7f),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = if (mono) FontFamily.Monospace else null,
        )
    }
}

@Composable
private fun ProvenanceHelpCard() {
    SectionCard(title = "What is provenance?") {
        Text(
            "Every metric carries how it was obtained. EXACT means the backend exposes the value directly; " +
                "CONVENTION means a documented rule was applied (for example an allocation unit); ESTIMATED is a model; " +
                "PARTIAL or UNAVAILABLE means the number is not a complete measurement.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "This is why the showcase never prints 0 for a missing value: the state and its reason are part of the " +
                "contract between providers and consumers.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun stateTone(state: MeasurementState): BadgeTone = when (state) {
    MeasurementState.MEASURED -> BadgeTone.Positive
    MeasurementState.UNAVAILABLE -> BadgeTone.Warning
    MeasurementState.NOT_APPLICABLE, MeasurementState.NOT_YET_MEASURED -> BadgeTone.Neutral
}

private fun completenessTone(name: String): BadgeTone = when (name) {
    "COMPLETE" -> BadgeTone.Positive
    "PARTIAL" -> BadgeTone.Warning
    else -> BadgeTone.Neutral
}
