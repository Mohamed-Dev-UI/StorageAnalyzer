package com.mohamed.storageanalyzer.core.analytics

import com.mohamed.storageanalyzer.core.config.AnalysisContext
import com.mohamed.storageanalyzer.core.config.AnalysisOptions
import com.mohamed.storageanalyzer.core.config.AnalysisProgress
import com.mohamed.storageanalyzer.core.config.AnalyzerConfig
import com.mohamed.storageanalyzer.core.identity.ProviderId
import com.mohamed.storageanalyzer.core.measurement.ByteCount
import com.mohamed.storageanalyzer.core.measurement.MetricType
import com.mohamed.storageanalyzer.core.measurement.MeasurementState
import com.mohamed.storageanalyzer.core.policy.AccountingPolicy
import com.mohamed.storageanalyzer.core.provider.StorageProvider
import com.mohamed.storageanalyzer.core.result.Completeness
import com.mohamed.storageanalyzer.core.result.DiagnosticSeverity
import com.mohamed.storageanalyzer.core.result.GlobalReport
import com.mohamed.storageanalyzer.core.result.ProviderSummary
import com.mohamed.storageanalyzer.core.result.ReconciliationDifference
import com.mohamed.storageanalyzer.core.result.ResourceSummary
import com.mohamed.storageanalyzer.core.result.SharedResourceAllocation
import com.mohamed.storageanalyzer.core.result.StorageBreakdown
import com.mohamed.storageanalyzer.core.result.StorageDiagnostic
import com.mohamed.storageanalyzer.core.result.StorageMeasurement
import com.mohamed.storageanalyzer.core.result.StorageTotals
import com.mohamed.storageanalyzer.core.result.UnmeasuredResource
import com.mohamed.storageanalyzer.core.resource.StorageResource
import java.time.Duration
import java.time.Instant

/**
 * Builds the [GlobalReport] from measurements performed over a
 * deduplicated inventory.
 *
 * Deterministic processing order:
 * 1. Compose a deduplicated inventory across providers
 *    (see [InventoryComposer] dedup rules).
 * 2. Measure each unique resource exactly once — per-resource failures are
 *    isolated and reported, never fatal.
 * 3. Attribute shared resources using the configured [AccountingPolicy].
 * 4. Aggregate into breakdowns and run reconciliation checks.
 */
internal class GlobalReportBuilder(
    private val providers: List<StorageProvider>,
    private val config: AnalyzerConfig,
    private val options: AnalysisOptions,
    private val policy: AccountingPolicy,
    private val ctx: com.mohamed.storageanalyzer.core.config.BudgetedAnalysisContext,
) {

    private val diagnostics = LinkedHashSet<StorageDiagnostic>()
    private val unmeasured = mutableListOf<UnmeasuredResource>()
    private val resourceSummaries = mutableListOf<ResourceSummary>()
    private var truncated = false

    fun build(): GlobalReport {
        val startedAt = Instant.now()
        val composer = InventoryComposer(providers, ctx, options.excluded)
        val inventory = composer.compose()
        if (!ctx.timeRemaining()) truncated = true
        if (composer.excludedCount > 0) {
            diagnostics += StorageDiagnostic(
                code = "general.excluded_resources",
                severity = DiagnosticSeverity.INFO,
                message = "${composer.excludedCount} discovered resource(s) dropped by the run-level exclusion predicate (requested filter, not an availability problem)",
            )
        }

        val results = measureInventory(inventory)
        val overlapMeasurements = if (options.redactLabels) {
            measureOverlaps(composer.overlapRecords()).map { redactLabel(it) }
        } else {
            measureOverlaps(composer.overlapRecords())
        }
        val sharedAllocationsList = attributeShared(results)
        val totals = fold(results, sharedAllocationsList)
        val breakdowns = buildBreakdowns(results)
        val summaries = providerSummaries(composer, breakdowns.byProvider)
        val reconciliation = reconcile(totals)

        val completeness = when {
            truncated -> Completeness.PARTIAL
            unmeasured.isEmpty() && diagnostics.none { it.severity == DiagnosticSeverity.ERROR } -> Completeness.COMPLETE
            else -> Completeness.PARTIAL
        }

        return GlobalReport(
            generatedAt = startedAt,
            duration = Duration.between(startedAt, Instant.now()),
            options = options,
            accountingPolicy = policy,
            totals = totals,
            byProvider = breakdowns.byProvider,
            providers = summaries,
            byKind = breakdowns.byKind,
            byCategory = breakdowns.byCategory,
            byScope = breakdowns.byScope,
            sharedAllocations = sharedAllocationsList,
            unmeasuredResources = unmeasured.toList(),
            resourceSummaries = resourceSummaries.toList(),
            overlapMeasurements = overlapMeasurements,
            reconciliation = reconciliation,
            diagnostics = diagnostics.toList(),
            resourceCount = results.count { it.measurement != null }.toLong(),
            completeness = completeness,
        )
    }

    // ------------------------------------------------------------------- measure

    /**
     * Privacy: when the run asks for [AnalysisOptions.redactLabels],
     * human labels (paths, file names) are replaced by a short stable hash so
     * reports remain comparable but not readable. Stable keys/identities are
     * NOT redacted (they are needed for dedup and growth attribution).
     */
    private fun redactLabel(m: StorageMeasurement): StorageMeasurement {
        val redacted = "label-" + Integer.toHexString(
            (m.label?.hashCode() ?: 0).let { java.util.Objects.hash(it, "sa-redact") },
        )
        return m.copy(label = if (m.label == null) null else redacted)
    }

    private class MeasuredEntry(
        val resource: StorageResource,
        val measurement: StorageMeasurement?,
    )

    private fun measureInventory(inventory: List<InventoryEntry>): List<MeasuredEntry> {
        val out = ArrayList<MeasuredEntry>(inventory.size)
        for (entry in inventory) {
            if (!ctx.consumeResource()) { truncated = true; break }
            ctx.ensureActive()
            val provider = entry.measuredByProvider
            val measurement = try {
                provider.measure(entry.resource.identity, options.metrics, ctx)
            } catch (e: Exception) {
                recordUnmeasured(entry.resource, e, provider)
                out += MeasuredEntry(entry.resource, null)
                continue
            }
            for (d in measurement.diagnostics) diagnostics += d
            for ((metric, reason) in measurement.missing()) {
                diagnostics += StorageDiagnostic(
                    code = "measurement.unavailable",
                    severity = DiagnosticSeverity.INFO,
                    message = "metric ${metric.id} unmeasured: $reason",
                    identity = entry.resource.identity,
                    providerId = provider.providerId,
                )
            }
            out += MeasuredEntry(entry.resource, measurement)
            resourceSummaries += ResourceSummary(
                identity = entry.resource.identity,
                kind = entry.resource.kind,
                footPrintMetrics = measurement.measurements
                    .filterValues { it.state == com.mohamed.storageanalyzer.core.measurement.MeasurementState.MEASURED }
                    .mapValues { (_, s) -> s.value ?: ByteCount.ZERO },
            )
            ctx.report(AnalysisProgress.ResourceMeasured(entry.resource.identity, out.size.toLong(), ctx.elapsed()))
        }
        return out
    }

    // ------------------------------------------------------------------ overlaps

    /**
     * Measures resources that other providers would also report (kept out of
     * the unique footprint, but still surfaced per provider view).
     */
    private fun measureOverlaps(records: List<InventoryComposer.OverlapRecord>): List<StorageMeasurement> {
        val out = mutableListOf<StorageMeasurement>()
        for (record in records) {
            if (!ctx.consumeResource()) { truncated = true; break }
            ctx.ensureActive()
            val provider = providers.firstOrNull { it.providerId.value == record.excludedProviderId } ?: continue
            try {
                out += provider.measure(record.duplicateIdentity, options.metrics, ctx)
            } catch (e: Exception) {
                ctx.diagnostic(
                    StorageDiagnostic(
                        code = "overlap.measurement_failed",
                        severity = DiagnosticSeverity.INFO,
                        message = "duplicate resource measurement failed: ${e.message ?: e::class.simpleName}",
                        identity = record.duplicateIdentity,
                        providerId = provider.providerId,
                    ),
                )
            }
        }
        return out
    }

    private fun recordUnmeasured(resource: StorageResource, e: Exception, provider: StorageProvider) {
        val message = "provider '${provider.providerId.value}' failed measuring resource: " +
            (e.message ?: e.javaClass.simpleName)
        diagnostics += StorageDiagnostic(
            code = "measurement.provider_failed",
            severity = DiagnosticSeverity.ERROR,
            message = message,
            identity = resource.identity,
            providerId = provider.providerId,
        )
        if (options.includeUnavailableResources) {
            unmeasured += UnmeasuredResource(resource.identity, resource.kind, message, provider.providerId)
        }
    }

    // ------------------------------------------------------------------ attribution

    private fun attributeShared(results: List<MeasuredEntry>): List<SharedResourceAllocation> {
        val out = mutableListOf<SharedResourceAllocation>()
        for (m in results) {
            val measurement = m.measurement ?: continue
            if (measurement.owners.size < 2) continue
            val bytes = measurement.valueOf(config.footprintMetric) ?: continue
            val allocation = policy.allocate(measurement.identity, bytes, measurement.owners)
            out += SharedResourceAllocation(
                identity = measurement.identity,
                footprintBytes = bytes,
                ownerCount = measurement.owners.size,
                allocations = allocation.shares,
                policyId = policy.policyId,
            )
        }
        return out
    }

    // ------------------------------------------------------------------- folding

    private fun fold(results: List<MeasuredEntry>, shared: List<SharedResourceAllocation>): StorageTotals {
        val byMetric = mutableMapOf<MetricType, ByteCount>()
        var unique = ByteCount.ZERO
        var exclusive = ByteCount.ZERO
        var attributedShared = ByteCount.ZERO
        var sharedOnly = ByteCount.ZERO
        var unattributed = ByteCount.ZERO

        val sharedByKey = shared.associateBy { it.identity.stableKey }

        for (m in results) {
            val measurement = m.measurement ?: continue
            for ((metric, single) in measurement.measurements) {
                if (single.state != MeasurementState.MEASURED) continue
                val v = single.value ?: continue
                byMetric.merge(metric, v, ByteCount::plus)
            }
            // Decomposition views (tables, indexes, records) attribute the
            // container's bytes; they never inflate the unique footprint.
            if (m.resource.decomposes != null) continue
            val footprint = measurement.valueOf(config.footprintMetric) ?: continue
            // Every distinct identity appears exactly once — never double count.
            unique = unique + footprint
            when {
                measurement.identity.stableKey in sharedByKey -> {
                    sharedByKey.getValue(measurement.identity.stableKey).allocations.values
                        .forEach { attributedShared = attributedShared + it }
                    if (sharedByKey.getValue(measurement.identity.stableKey).allocations.isEmpty()) {
                        sharedOnly = sharedOnly + footprint
                    }
                }
                measurement.owners.size == 1 -> exclusive = exclusive + footprint
                else -> unattributed = unattributed + footprint
            }
        }
        return StorageTotals(
            byMetric = byMetric,
            uniqueFootprint = unique,
            exclusiveAttributed = exclusive,
            attributedShared = attributedShared,
            sharedOnly = sharedOnly,
            unattributed = unattributed,
            unmeasuredFootprint = ByteCount.ZERO,
        )
    }

    // ---------------------------------------------------------------- breakdowns

    private data class BreakdownSet(
        val byProvider: List<StorageBreakdown>,
        val byKind: List<StorageBreakdown>,
        val byCategory: List<StorageBreakdown>,
        val byScope: List<StorageBreakdown>,
    )

    private fun buildBreakdowns(results: List<MeasuredEntry>): BreakdownSet {
        val byProvider = providers.map { providerBreakdown(results, it.providerId, it.providerId.value) }
        val byKind = groupBy(results) { it.measurement?.kind?.name }
        val byCategory = groupBy(results) { it.measurement?.category?.name }
        val byScope = groupBy(results, label = { it.measurement?.scope?.label }) { it.measurement?.scope?.id }
        return BreakdownSet(byProvider, byKind, byCategory, byScope)
    }

    private fun providerBreakdown(
        results: List<MeasuredEntry>,
        providerId: ProviderId,
        key: String,
    ): StorageBreakdown {
        val subset = results.filter { it.measurement != null && it.measurement.identity.providerId == providerId }
        val byMetric = mutableMapOf<MetricType, ByteCount>()
        var count = 0L
        for (m in subset) {
            val measurement = m.measurement ?: continue
            count++
            for ((metric, single) in measurement.measurements) {
                if (single.state != MeasurementState.MEASURED) continue
                val v = single.value ?: continue
                byMetric.merge(metric, v, ByteCount::plus)
            }
        }
        return StorageBreakdown(key = key, label = providerId.value, byMetric = byMetric, resourceCount = count)
    }

    private fun groupBy(
        results: List<MeasuredEntry>,
        label: (MeasuredEntry) -> String? = { null },
        keyOf: (MeasuredEntry) -> String?,
    ): List<StorageBreakdown> {
        data class Acc(var metrics: MutableMap<MetricType, ByteCount>, var count: Long)

        val buckets = LinkedHashMap<String, Acc>()
        val labels = HashMap<String, String?>()
        for (entry in results) {
            val measurement = entry.measurement ?: continue
            val key = keyOf(entry) ?: continue
            val acc = buckets.getOrPut(key) { Acc(mutableMapOf(), 0) }
            acc.count++
            labels[key] = label(entry)
            for ((metric, single) in measurement.measurements) {
                if (single.state != MeasurementState.MEASURED) continue
                val v = single.value ?: continue
                acc.metrics.merge(metric, v, ByteCount::plus)
            }
        }
        return buckets.map { (k, acc) ->
            StorageBreakdown(key = k, label = labels[k], byMetric = acc.metrics, resourceCount = acc.count)
        }
    }

    // ------------------------------------------------------------ reconciliation

    private fun reconcile(totals: StorageTotals): List<ReconciliationDifference> {
        val out = mutableListOf<ReconciliationDifference>()

        // Check: attribution conservation — attributed partitions must not lose bytes:
        //   uniqueFootprint == exclusiveAttributed + attributedShared + sharedOnly + unattributed
        // for the footprint metric, under "no availability gaps for footprint".
        val accounted = totals.exclusiveAttributed + totals.attributedShared + totals.sharedOnly + totals.unattributed
        val diff = computeDifference(totals.uniqueFootprint, accounted)
        out += ReconciliationDifference(
            checkId = "attribution_conservation",
            leftLabel = "unique footprint",
            rightLabel = "attributed + shared-only + unattributed",
            leftBytes = totals.uniqueFootprint,
            rightBytes = accounted,
            difference = diff,
            explained = true,
            explanation = "At the default accounting the difference equals resources whose " +
                "footprint metric was unmeasured (see unmeasuredResources).",
        )
        return out
    }

    private fun computeDifference(a: ByteCount, b: ByteCount): ByteCount =
        if (a.value >= b.value) ByteCount.of(a.value - b.value) else ByteCount.of(b.value - a.value)

    private fun providerSummaries(
        composer: InventoryComposer,
        byProvider: List<StorageBreakdown>,
    ): List<ProviderSummary> = providers.map { provider ->
        val breakdown = byProvider.first { it.key == provider.providerId.value }
        ProviderSummary(
            providerId = provider.providerId,
            description = provider.describe(),
            capabilities = provider.capabilities(),
            breakdown = breakdown,
            deduplicatedReferenceCount = composer.overlapRecords().count { it.excludedProviderId == provider.providerId.value }.toLong(),
        )
    }
}
