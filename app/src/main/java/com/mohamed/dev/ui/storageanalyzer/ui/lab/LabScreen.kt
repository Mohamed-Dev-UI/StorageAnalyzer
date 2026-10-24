package com.mohamed.dev.ui.storageanalyzer.ui.lab

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.HorizontalDivider
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
import com.mohamed.dev.ui.storageanalyzer.data.model.AccountingSnapshot
import com.mohamed.dev.ui.storageanalyzer.data.model.CancellationOutcome
import com.mohamed.dev.ui.storageanalyzer.data.model.DedupSnapshot
import com.mohamed.dev.ui.storageanalyzer.data.model.ExclusionSnapshot
import com.mohamed.dev.ui.storageanalyzer.data.model.PrivacySnapshot
import com.mohamed.dev.ui.storageanalyzer.data.model.ProgressScanResult
import com.mohamed.dev.ui.storageanalyzer.data.model.ProviderCapabilityRow
import com.mohamed.dev.ui.storageanalyzer.data.model.ProviderFilterSnapshot
import com.mohamed.dev.ui.storageanalyzer.ui.components.ActionButton
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
import com.mohamed.storageanalyzer.core.measurement.ByteCount
import com.mohamed.storageanalyzer.core.measurement.MeasurementProvenance
import com.mohamed.storageanalyzer.core.measurement.MetricType
import com.mohamed.storageanalyzer.core.result.StorageDiagnostic
import com.mohamed.storageanalyzer.core.result.StorageMeasurement

@Composable
fun LabScreen(viewModel: LabViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { LabIntroCard() }
        state.error?.let { message ->
            item { ErrorBanner(message = message, actionLabel = "Dismiss", onAction = viewModel::dismissError) }
        }
        item {
            BudgetProgressCard(
                result = state.progress,
                busy = state.busy == "progress",
                enabled = !state.isBusy,
                onRun = viewModel::runBudgetProgress,
            )
        }
        item {
            CancellationCard(
                outcome = state.cancellation,
                busy = state.busy == "cancellation",
                enabled = !state.isBusy,
                onRun = viewModel::runCancellation,
            )
        }
        item {
            DiagnosticsCard(
                diagnostics = state.diagnostics,
                busy = state.busy == "diagnostics" || state.busy == "startup",
                enabled = !state.isBusy,
                onLoad = viewModel::loadDiagnostics,
            )
        }
        item {
            CapabilitiesCard(
                rows = state.capabilities,
                busy = state.busy == "capabilities",
                enabled = !state.isBusy,
                onLoad = viewModel::loadCapabilities,
            )
        }
        item {
            AccountingCard(
                accounting = state.accounting,
                busy = state.busy == "accounting",
                enabled = !state.isBusy,
                onRun = viewModel::runAccounting,
            )
        }
        item {
            DedupCard(
                dedup = state.dedup,
                busy = state.busy == "dedup",
                enabled = !state.isBusy,
                onRun = viewModel::runDedup,
            )
        }
        item {
            ExclusionCard(
                exclusion = state.exclusion,
                busy = state.busy == "exclusion",
                enabled = !state.isBusy,
                onRun = viewModel::runExclusion,
            )
        }
        item {
            PrivacyCard(
                privacy = state.privacy,
                busy = state.busy == "privacy",
                enabled = !state.isBusy,
                onRun = viewModel::runPrivacy,
            )
        }
        item {
            ProviderFilterCard(
                filter = state.providerFilter,
                busy = state.busy == "filter",
                enabled = !state.isBusy,
                onRun = viewModel::runProviderFilter,
            )
        }
        item { CustomProviderCard() }
    }
}

@Composable
private fun LabIntroCard() {
    SectionCard(
        title = "Experiments",
        subtitle = "Real analyzer calls, not mocked output",
    ) {
        Text(
            "Every result below is the library's own object rendered directly. Runs are serialized on the repository " +
                "Mutex, so only one experiment executes at a time and the buttons disable while a run is in flight.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        InfoBanner(
            "The demo inventory is small enough to run each experiment interactively. Larger budgets would raise " +
                "latency, not change the semantics.",
        )
    }
}

@Composable
private fun BudgetProgressCard(
    result: ProgressScanResult?,
    busy: Boolean,
    enabled: Boolean,
    onRun: () -> Unit,
) {
    SectionCard(
        title = "Budget and progress",
        subtitle = "AnalysisBudget(maxResources = 12) + AnalysisContext.onProgress",
        action = {
            ActionButton(
                label = "Run limited scan",
                onClick = onRun,
                loading = busy,
                enabled = enabled,
                icon = Icons.Filled.PlayArrow,
            )
        },
    ) {
        Text(
            "A budget truncates the inventory deliberately. The report stays usable but completeness becomes PARTIAL, " +
                "and every measured resource emits a progress event before the run ends.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (result == null) {
            Text("Run it to see the phase and resource events.", style = MaterialTheme.typography.bodySmall)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile(
                    label = "Measured",
                    value = result.resourceCount.toString(),
                    supporting = "rows",
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    label = "Footprint",
                    value = Formats.bytes(result.footprintBytes),
                    supporting = "subset only",
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    label = "Completeness",
                    value = result.completeness,
                    supporting = "budget outcome",
                    modifier = Modifier.weight(1f),
                )
            }
            Text("Events (truncated to 40)", style = MaterialTheme.typography.titleSmall)
            CodeBlock(result.events.joinToString("\n").ifBlank { "no progress events were reported" })
            InfoBanner("Truncation is reported, not hidden: the caller can tell the run only covered a subset.")
        }
    }
}

@Composable
private fun CancellationCard(
    outcome: CancellationOutcome?,
    busy: Boolean,
    enabled: Boolean,
    onRun: () -> Unit,
) {
    SectionCard(
        title = "Cooperative cancellation",
        subtitle = "AnalysisContext.cancel() observed at a provider safe point",
        action = {
            ActionButton(
                label = "Run cancellation",
                onClick = onRun,
                loading = busy,
                enabled = enabled,
                icon = Icons.Filled.PlayArrow,
            )
        },
    ) {
        Text(
            "A progress listener cancels the context after the fifth measured resource. The analyzer stops at its next " +
                "ensureActive() check and throws StorageError.Cancelled; the showcase reports the exception it actually saw.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (outcome == null) {
            Text("Run it to observe the cancellation path.", style = MaterialTheme.typography.bodySmall)
            return@SectionCard
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(
                label = "Observed",
                value = if (outcome.cancelled) "Cancelled" else "Completed",
                supporting = "control flow",
                modifier = Modifier.weight(1f),
                containerColor = if (outcome.cancelled) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.tertiaryContainer
                },
            )
            StatTile(
                label = "Elapsed",
                value = Formats.durationMs(outcome.elapsedMs),
                supporting = "before stop",
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = "Events",
                value = outcome.progressEvents.toString(),
                supporting = "measured",
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            outcome.message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        InfoBanner("No partial report is invented for a cancelled run; the caller sees the typed cancellation.")
    }
}

@Composable
private fun DiagnosticsCard(
    diagnostics: List<StorageDiagnostic>?,
    busy: Boolean,
    enabled: Boolean,
    onLoad: () -> Unit,
) {
    SectionCard(
        title = "Diagnostics",
        subtitle = "Provider health checks plus registration checks",
        action = {
            ActionButton(
                label = "Refresh",
                onClick = onLoad,
                loading = busy,
                enabled = enabled,
                icon = Icons.Filled.Refresh,
            )
        },
    ) {
        when {
            diagnostics == null && busy -> LoadingPanel("Running provider health checks…")
            diagnostics == null -> Text("Not loaded yet.", style = MaterialTheme.typography.bodySmall)
            diagnostics.isEmpty() -> EmptyState(
                title = "No diagnostics",
                message = "Every registered provider passed its health check and every entity descriptor points at a registered provider.",
            )
            else -> diagnostics.forEachIndexed { index, diagnostic ->
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ToneBadge(diagnostic.severity.name, severityTone(diagnostic.severity.name))
                        Text(
                            diagnostic.code,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        diagnostic.providerId?.let {
                            ToneBadge(it.value, BadgeTone.Neutral)
                        }
                    }
                    Text(
                        diagnostic.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (index != diagnostics.lastIndex) HorizontalDivider()
            }
        }
    }
}

@Composable
private fun CapabilitiesCard(
    rows: List<ProviderCapabilityRow>,
    busy: Boolean,
    enabled: Boolean,
    onLoad: () -> Unit,
) {
    SectionCard(
        title = "Declared capabilities",
        subtitle = "What each provider supports, and the reason for every gap",
        action = {
            ActionButton(
                label = "Refresh",
                onClick = onLoad,
                loading = busy,
                enabled = enabled,
                icon = Icons.Filled.Refresh,
            )
        },
    ) {
        if (rows.isEmpty()) {
            if (busy) LoadingPanel("Reading provider capabilities…") else Text(
                "No providers registered.",
                style = MaterialTheme.typography.bodySmall,
            )
            return@SectionCard
        }
        rows.forEachIndexed { index, row ->
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        row.providerId,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall,
                        fontFamily = FontFamily.Monospace,
                    )
                    ToneBadge("${row.supported.size} supported", BadgeTone.Primary)
                    ToneBadge("${row.unsupported.size} unsupported", BadgeTone.Warning)
                }
                Text(
                    row.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                KeyValueRow("Systems", row.systemCount.toString())
                if (row.supported.isNotEmpty()) {
                    Text(
                        row.supported.joinToString(", ") { it.first },
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                row.unsupported.forEach { capability ->
                    Text(
                        "✕ ${capability.id}: ${capability.reason}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (index != rows.lastIndex) HorizontalDivider()
        }
    }
}

@Composable
private fun AccountingCard(
    accounting: List<AccountingSnapshot>,
    busy: Boolean,
    enabled: Boolean,
    onRun: () -> Unit,
) {
    SectionCard(
        title = "Accounting policies",
        subtitle = "The same shared resource under three attribution rules",
        action = {
            ActionButton(
                label = "Run policies",
                onClick = onRun,
                loading = busy,
                enabled = enabled,
                icon = Icons.Filled.PlayArrow,
            )
        },
    ) {
        Text(
            "The custom SharedPreferences provider declares `feature_flags` with two SCOPE owners. Policy changes " +
                "only attribution — unique bytes must stay conserved.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (accounting.isEmpty()) {
            Text("Run the experiment to compare COUNT_ONCE_GLOBALLY, CANONICAL_OWNER and EQUAL_SPLIT.", style = MaterialTheme.typography.bodySmall)
            return@SectionCard
        }
        val uniqueValues = accounting.map { it.uniqueFootprint }.distinct()
        if (uniqueValues.size == 1) {
            InfoBanner("All ${accounting.size} policies agree on a unique footprint of ${Formats.bytes(uniqueValues.first())}.")
        } else {
            ErrorBanner(
                message = "Policies disagree on the unique footprint: ${uniqueValues.joinToString { Formats.bytes(it) }}",
                actionLabel = "Re-run",
                onAction = onRun,
            )
        }
        accounting.forEachIndexed { index, snapshot ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    Formats.humanize(snapshot.policyId),
                    style = MaterialTheme.typography.titleSmall,
                )
                KeyValueRow("Unique footprint", Formats.bytes(snapshot.uniqueFootprint))
                KeyValueRow("Shared only", Formats.bytes(snapshot.sharedOnly))
                KeyValueRow("Attributed shared", Formats.bytes(snapshot.attributedShared))
                if (snapshot.allocations.isEmpty()) {
                    Text(
                        "No shared allocations were produced.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    snapshot.allocations.forEach { allocation ->
                        val owners = allocation.allocations.entries.joinToString(", ") {
                            "${it.key.id} = ${Formats.bytes(it.value)}"
                        }
                        Text(
                            "${allocation.identity.nativeId} · ${Formats.bytes(allocation.footprintBytes)} · " +
                                "${allocation.ownerCount} owners",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                        Text(
                            owners.ifBlank { "no owner received bytes under this policy" },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (index != accounting.lastIndex) HorizontalDivider()
        }
    }
}

@Composable
private fun DedupCard(
    dedup: DedupSnapshot?,
    busy: Boolean,
    enabled: Boolean,
    onRun: () -> Unit,
) {
    SectionCard(
        title = "Cross-provider deduplication",
        subtitle = "alsoDiscoverableBy turns a second view into overlap evidence",
        action = {
            ActionButton(
                label = "Run dedup",
                onClick = onRun,
                loading = busy,
                enabled = enabled,
                icon = Icons.Filled.PlayArrow,
            )
        },
    ) {
        Text(
            "ArchiveCatalogProvider catalogues the same legacy-backup.zip that the filesystem provider already " +
                "discovered. Providers are sorted by id, so the filesystem composes first and the catalog entry is " +
                "measured as an overlap instead of inflating the total.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (dedup == null) {
            Text("Run it to compare provider views with the unique footprint.", style = MaterialTheme.typography.bodySmall)
            return@SectionCard
        }
        val excess = (dedup.providerViewBytes.value - dedup.uniqueFootprint.value).coerceAtLeast(0L)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(
                label = "Provider views",
                value = Formats.bytes(dedup.providerViewBytes),
                supporting = "incl. duplicates",
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = "Unique",
                value = Formats.bytes(dedup.uniqueFootprint),
                supporting = "counted once",
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = "Duplicate",
                value = Formats.bytes(excess),
                supporting = "not double-counted",
                modifier = Modifier.weight(1f),
            )
        }
        Text("Overlapping measurements", style = MaterialTheme.typography.titleSmall)
        if (dedup.overlaps.isEmpty()) {
            Text(
                "No overlaps were produced in this run.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            dedup.overlaps.forEach { measurement ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            measurement.label ?: measurement.identity.nativeId,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        val provenance = measurement.primaryProvenance()
                        Text(
                            provenance?.let { "${it.method.id} · ${it.accuracy.name}" } ?: "no provenance",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    ToneBadge(measurement.identity.providerId.value, BadgeTone.Neutral)
                    Text(
                        measurement.footprintBytes()?.let { Formats.bytes(it) } ?: "—",
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
        dedup.providerSummaries.filter { it.deduplicatedReferenceCount > 0 }.forEach { summary ->
            KeyValueRow(
                summary.providerId.value,
                "${summary.deduplicatedReferenceCount} deduplicated reference(s)",
                mono = true,
            )
        }
    }
}

@Composable
private fun ExclusionCard(
    exclusion: ExclusionSnapshot?,
    busy: Boolean,
    enabled: Boolean,
    onRun: () -> Unit,
) {
    SectionCard(
        title = "Exclusions",
        subtitle = "A run-level predicate over identities",
        action = {
            ActionButton(
                label = "Run exclusion",
                onClick = onRun,
                loading = busy,
                enabled = enabled,
                icon = Icons.Filled.PlayArrow,
            )
        },
    ) {
        Text(
            "The predicate drops the entire `cache` directory plus files ending in `.tmp` and `.log`, then re-runs the " +
                "same options. Both the byte and resource deltas are shown, and the diagnostic is surfaced verbatim.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (exclusion == null) {
            Text("Run it to measure how much the exclusions remove.", style = MaterialTheme.typography.bodySmall)
            return@SectionCard
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(
                label = "Before",
                value = Formats.bytes(exclusion.beforeBytes),
                supporting = "${exclusion.beforeCount} resources",
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = "After",
                value = Formats.bytes(exclusion.afterBytes),
                supporting = "${exclusion.afterCount} resources",
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = "Removed",
                value = Formats.bytes(exclusion.savedBytes.coerceAtLeast(0L)),
                supporting = "${exclusion.droppedResources} resources",
                modifier = Modifier.weight(1f),
            )
        }
        KeyValueRow(
            "Diagnostic",
            exclusion.exclusionDiagnostic ?: "no explicit exclusion diagnostic was emitted",
        )
    }
}

@Composable
private fun PrivacyCard(
    privacy: PrivacySnapshot?,
    busy: Boolean,
    enabled: Boolean,
    onRun: () -> Unit,
) {
    SectionCard(
        title = "Label redaction",
        subtitle = "redactLabels hides human labels but preserves comparable identities",
        action = {
            ActionButton(
                label = "Run redaction",
                onClick = onRun,
                loading = busy,
                enabled = enabled,
                icon = Icons.Filled.PlayArrow,
            )
        },
    ) {
        Text(
            "The experiment analyzes twice, once with redactLabels = false and once with true, and compares the " +
                "overlap labels. Stable keys are intentionally not redacted because deduplication needs them.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (privacy == null) {
            Text("Run it to see both label sets.", style = MaterialTheme.typography.bodySmall)
            return@SectionCard
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(
                label = "Visible labels",
                value = privacy.unredactedLabels.size.toString(),
                supporting = privacy.unredactedLabels.joinToString().ifBlank { "none" },
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = "Redacted labels",
                value = privacy.redactedLabels.size.toString(),
                supporting = privacy.redactedLabels.joinToString().ifBlank { "none" },
                modifier = Modifier.weight(1f),
            )
        }
        KeyValueRow("Before", privacy.unredactedLabels.joinToString(", ").ifBlank { "—" })
        KeyValueRow("After", privacy.redactedLabels.joinToString(", ").ifBlank { "—" })
    }
}

@Composable
private fun ProviderFilterCard(
    filter: ProviderFilterSnapshot?,
    busy: Boolean,
    enabled: Boolean,
    onRun: () -> Unit,
) {
    SectionCard(
        title = "Provider targeting",
        subtitle = "providerFilter limits a run without re-registering providers",
        action = {
            ActionButton(
                label = "Run filter",
                onClick = onRun,
                loading = busy,
                enabled = enabled,
                icon = Icons.Filled.PlayArrow,
            )
        },
    ) {
        Text(
            "The experiment compares all providers against a filesystem-only run. The difference is exactly the " +
                "preference entries (the archive catalog is already deduplicated).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (filter == null) {
            Text("Run it to compare the two provider sets.", style = MaterialTheme.typography.bodySmall)
            return@SectionCard
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(
                label = "All providers",
                value = Formats.bytes(filter.allBytes),
                supporting = filter.allProviders.joinToString(),
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = "Filesystem only",
                value = Formats.bytes(filter.filteredBytes),
                supporting = filter.filteredProviders.joinToString(),
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = "Difference",
                value = Formats.bytes((filter.allBytes.value - filter.filteredBytes.value).coerceAtLeast(0L)),
                supporting = "excluded providers",
                modifier = Modifier.weight(1f),
            )
        }
        KeyValueRow("Providers in all", filter.allProviders.joinToString(", "), mono = true)
        KeyValueRow("Providers filtered", filter.filteredProviders.joinToString(", "), mono = true)
    }
}

@Composable
private fun CustomProviderCard() {
    SectionCard(
        title = "Custom provider SPI",
        subtitle = "How the showcase extends the library",
    ) {
        BulletRow(
            "ArchiveCatalogProvider catalogs one ZIP, declares `alsoDiscoverableBy` for its filesystem path and " +
                "measures its length with EXACT accuracy.",
        )
        BulletRow(
            "ShowcasePreferencesProvider measures UTF-8 `key=value` bytes with CONVENTION accuracy and returns " +
                "explicit UNAVAILABLE reasons for metrics it cannot provide.",
        )
        BulletRow(
            "Both attach owners during measure(). Discovery owners are metadata; measurement owners are what the " +
                "accounting policies consume.",
        )
        CodeBlock(
            """
class MyProvider : StorageProvider {
    override val providerId = ProviderId("x_my_provider")

    override fun discoverResources(systemId, ctx) = sequence {
        ctx.ensureActive()
        yield(StorageResource(identity = ..., owners = owners))
    }

    override fun measure(resource, metrics, ctx) =
        StorageMeasurement(
            identity = resource, kind = ..., category = ...,
            measurements = measuredOrUnavailable(metrics),
            owners = owners,
        )

    override fun capabilities() = Capabilities(supported = ..., unsupportedReasons = ...)
}
            """.trimIndent(),
        )
    }
}

private fun StorageMeasurement.footprintBytes(): ByteCount? =
    valueOf(MetricType.STORED_CONTENT_SIZE)
        ?: measurements.values.firstOrNull { it.value != null }?.value

private fun StorageMeasurement.primaryProvenance(): MeasurementProvenance? =
    measurements[MetricType.STORED_CONTENT_SIZE]?.provenance
        ?: measurements.values.firstOrNull()?.provenance

private fun severityTone(name: String): BadgeTone = when (name) {
    "ERROR" -> BadgeTone.Negative
    "WARNING" -> BadgeTone.Warning
    else -> BadgeTone.Neutral
}
