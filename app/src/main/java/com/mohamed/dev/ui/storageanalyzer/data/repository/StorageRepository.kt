package com.mohamed.dev.ui.storageanalyzer.data.repository

import com.mohamed.dev.ui.storageanalyzer.data.demo.DemoEnvironment
import com.mohamed.dev.ui.storageanalyzer.data.demo.DemoFingerprint
import com.mohamed.dev.ui.storageanalyzer.data.local.ScanSessionDao
import com.mohamed.dev.ui.storageanalyzer.data.local.ScanSessionEntity
import com.mohamed.dev.ui.storageanalyzer.data.model.AccountingSnapshot
import com.mohamed.dev.ui.storageanalyzer.data.model.CancellationOutcome
import com.mohamed.dev.ui.storageanalyzer.data.model.CapacityStats
import com.mohamed.dev.ui.storageanalyzer.data.model.DedupSnapshot
import com.mohamed.dev.ui.storageanalyzer.data.model.EntityFolderInsight
import com.mohamed.dev.ui.storageanalyzer.data.model.ExclusionSnapshot
import com.mohamed.dev.ui.storageanalyzer.data.model.MonitorStatus
import com.mohamed.dev.ui.storageanalyzer.data.model.PrivacySnapshot
import com.mohamed.dev.ui.storageanalyzer.data.model.ProgressScanResult
import com.mohamed.dev.ui.storageanalyzer.data.model.ProviderCapabilityRow
import com.mohamed.dev.ui.storageanalyzer.data.model.ProviderFilterSnapshot
import com.mohamed.dev.ui.storageanalyzer.data.model.ResourceRow
import com.mohamed.dev.ui.storageanalyzer.data.model.ScanSnapshot
import com.mohamed.dev.ui.storageanalyzer.data.model.UnsupportedCapability
import com.mohamed.dev.ui.storageanalyzer.data.preferences.ShowcasePreferences
import com.mohamed.dev.ui.storageanalyzer.data.provider.ArchiveCatalogProvider
import com.mohamed.dev.ui.storageanalyzer.data.provider.ShowcasePreferencesProvider
import com.mohamed.dev.ui.storageanalyzer.data.provider.androidVolumeStats
import com.mohamed.dev.ui.storageanalyzer.di.ApplicationScope
import com.mohamed.dev.ui.storageanalyzer.di.IoDispatcher
import com.mohamed.storageanalyzer.core.StorageAnalyzer
import com.mohamed.storageanalyzer.core.config.AnalysisBudget
import com.mohamed.storageanalyzer.core.config.AnalysisContext
import com.mohamed.storageanalyzer.core.config.AnalysisOptions
import com.mohamed.storageanalyzer.core.config.AnalysisProgress
import com.mohamed.storageanalyzer.core.config.AnalyzerConfig
import com.mohamed.storageanalyzer.core.config.DefaultAnalysisContext
import com.mohamed.storageanalyzer.core.entity.EntityDescriptor
import com.mohamed.storageanalyzer.core.entity.EntityInstanceKey
import com.mohamed.storageanalyzer.core.identity.PathIdentity
import com.mohamed.storageanalyzer.core.identity.ProviderId
import com.mohamed.storageanalyzer.core.identity.ResourceIdentity
import com.mohamed.storageanalyzer.core.identity.ResourceKind
import com.mohamed.storageanalyzer.core.identity.StorageSystemId
import com.mohamed.storageanalyzer.core.measurement.ByteCount
import com.mohamed.storageanalyzer.core.measurement.MeasurementState
import com.mohamed.storageanalyzer.core.measurement.MetricType
import com.mohamed.storageanalyzer.core.policy.AccountingPolicies
import com.mohamed.storageanalyzer.core.resource.StorageResource
import com.mohamed.storageanalyzer.core.result.GlobalReport
import com.mohamed.storageanalyzer.core.result.StorageDiagnostic
import com.mohamed.storageanalyzer.core.result.StorageError
import com.mohamed.storageanalyzer.core.result.StorageMeasurement
import com.mohamed.storageanalyzer.core.snapshot.StorageSnapshot
import com.mohamed.storageanalyzer.filesystem.FilesystemProvider
import com.mohamed.storageanalyzer.reactive.AnalysisDigest
import com.mohamed.storageanalyzer.reactive.ReactiveAnalyzer
import com.mohamed.storageanalyzer.reactive.RefreshPolicy
import com.mohamed.storageanalyzer.reactive.RerunState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Duration
import java.time.Instant
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single owner of the analysis stack for the app process.
 *
 * - One long-lived [StorageAnalyzer] serializes manual scans and experiments.
 * - A separate analyzer instance backs the hot monitor, so its loop never
 *   races the interactive scans.
 * - Every manual scan/experiment result is honest: the report object carries
 *   completeness, diagnostics and provenance; nothing is coerced to zero.
 */
@Singleton
class StorageRepository @Inject constructor(
    private val environment: DemoEnvironment,
    private val preferences: ShowcasePreferences,
    private val scanSessionDao: ScanSessionDao,
    private val fingerprint: DemoFingerprint,
    @IoDispatcher private val io: CoroutineDispatcher,
    @ApplicationScope private val appScope: CoroutineScope,
) {
    private val scanMutex = Mutex()

    private val filesystem = newFilesystemProvider()
    private val archiveCatalog = ArchiveCatalogProvider(File(environment.root, "archives/legacy-backup.zip"))
    private val preferencesProvider = ShowcasePreferencesProvider { preferences.entries() }

    private val analyzer = StorageAnalyzer(
        config = AnalyzerConfig(footprintMetric = MetricType.STORED_CONTENT_SIZE),
        providers = listOf(filesystem, archiveCatalog, preferencesProvider),
    )

    private val _latest = MutableStateFlow<ScanSnapshot?>(null)
    val latest: StateFlow<ScanSnapshot?> = _latest.asStateFlow()

    private val _monitorStatus = MutableStateFlow(MonitorStatus())
    val monitorStatus: StateFlow<MonitorStatus> = _monitorStatus.asStateFlow()

    val scanSessions: Flow<List<ScanSessionEntity>> = scanSessionDao.observeRecent(12)

    private var monitor: ReactiveAnalyzer? = null
    private var monitorAnalyzer: StorageAnalyzer? = null
    private var monitorJob: Job? = null

    val demoRootPath: String get() = environment.rootPath
    val databasePath: String get() = environment.databasePath

    // --------------------------------------------------------------- manual scans

    suspend fun refresh(trigger: String): ScanSnapshot = scanMutex.withLock {
        withContext(io) {
            val startedWallClock = System.currentTimeMillis()
            val report = analyzer.analyzeGlobal(scanOptions)
            val digest = AnalysisDigest.of(report).value
            val rows = explorerRows(report)
            val snapshot = ScanSnapshot(
                report = report,
                digest = digest,
                durationMs = report.duration.toMillis(),
                rows = rows,
                capturedAt = Instant.now(),
            )
            _latest.value = snapshot
            recordSession(
                startedAtEpochMs = startedWallClock,
                durationMs = report.duration.toMillis(),
                footprintBytes = report.totals.uniqueFootprint.value,
                resourceCount = report.resourceCount,
                completeness = report.completeness.name,
                digest = digest,
                trigger = trigger,
                providerCount = report.providers.size,
            )
            snapshot
        }
    }

    suspend fun captureSnapshot(trigger: String): StorageSnapshot = scanMutex.withLock {
        withContext(io) {
            val startedWallClock = System.currentTimeMillis()
            val snapshot = analyzer.captureSnapshot(scanOptions, includeResourceDetails = true)
            recordSession(
                startedAtEpochMs = startedWallClock,
                durationMs = Duration.between(startedWallClock.let { Instant.ofEpochMilli(it) }, snapshot.capturedAt).toMillis(),
                footprintBytes = snapshot.footprintTotal.value,
                resourceCount = snapshot.resourceCount,
                completeness = snapshot.completenessLabel,
                digest = "snapshot:${snapshot.capturedAt}",
                trigger = trigger,
                providerCount = reportProviderCount(),
            )
            snapshot
        }
    }

    // ------------------------------------------------------------- measurements

    suspend fun measureResource(identity: ResourceIdentity): StorageMeasurement = scanMutex.withLock {
        withContext(io) {
            analyzer.analyzeResource(
                identity = identity,
                options = AnalysisOptions(metrics = DETAIL_METRICS, budget = AnalysisBudget(maxDepth = 16)),
            )
        }
    }

    suspend fun capacity(): CapacityStats = scanMutex.withLock {
        withContext(io) {
            val identity = PathIdentity.forPath(environment.rootPath, ResourceKind.DIRECTORY)
            val measurement = analyzer.analyzeResource(
                identity = identity,
                options = AnalysisOptions(metrics = CAPACITY_METRICS),
            )
            val total = measurement.measurements[MetricType.TOTAL_CAPACITY]
            CapacityStats(
                totalBytes = measurement.valueOf(MetricType.TOTAL_CAPACITY),
                freeBytes = measurement.valueOf(MetricType.FREE_CAPACITY),
                reservedBytes = measurement.valueOf(MetricType.RESERVED_CAPACITY),
                measuredAt = measurement.measuredAt,
                methodId = total?.provenance?.method?.id,
                accuracy = total?.provenance?.accuracy?.name,
                reason = total?.reason ?: measurement.measurements[MetricType.FREE_CAPACITY]?.reason,
            )
        }
    }

    suspend fun diagnostics(): List<StorageDiagnostic> = scanMutex.withLock {
        withContext(io) { analyzer.runDiagnostics() }
    }

    suspend fun providerCapabilities(): List<ProviderCapabilityRow> = scanMutex.withLock {
        withContext(io) {
            analyzer.providers().map { provider ->
                val capabilities = provider.capabilities()
                ProviderCapabilityRow(
                    providerId = provider.providerId.value,
                    description = provider.describe(),
                    systemCount = provider.discoverSystems().size,
                    supported = capabilities.supported
                        .map { it.id to it.description }
                        .sortedBy { it.first },
                    unsupported = capabilities.unsupportedReasons
                        .map { (capability, reason) -> UnsupportedCapability(capability.id, capability.description, reason) }
                        .sortedBy { it.id },
                )
            }
        }
    }

    suspend fun entityFolders(): List<EntityFolderInsight> = scanMutex.withLock {
        withContext(io) {
            val folders = environment.root.listFiles()
                ?.filter { it.isDirectory }
                ?.sortedBy { it.name }
                .orEmpty()
            folders.mapNotNull { folder ->
                val descriptor = EntityDescriptor(
                    entityTypeId = "showcase.folder.${folder.name}",
                    providerId = ProviderId(FilesystemProvider.providerIdValue),
                    systemId = StorageSystemId(environment.rootPath),
                    kind = ResourceKind.DIRECTORY,
                    displayName = folder.name,
                    nativeIdOf = { key -> key.value },
                )
                runCatching {
                    val report = analyzer.analyzeEntity(
                        descriptor = descriptor,
                        instanceKey = EntityInstanceKey(folder.name),
                        options = AnalysisOptions(
                            metrics = setOf(MetricType.STORED_CONTENT_SIZE),
                            budget = AnalysisBudget(maxDepth = 16),
                        ),
                    )
                    EntityFolderInsight(
                        entityTypeId = descriptor.entityTypeId,
                        displayName = folder.name,
                        folderName = folder.name,
                        report = report,
                    )
                }.getOrNull()
            }.sortedByDescending { it.inclusiveBytes.value }
        }
    }

    // --------------------------------------------------------------- experiments

    suspend fun analyze(
        options: AnalysisOptions,
        context: DefaultAnalysisContext = AnalysisContext.create(options.budget),
    ): GlobalReport = scanMutex.withLock {
        withContext(io) { analyzer.analyzeGlobal(options, context) }
    }

    suspend fun progressScan(): ProgressScanResult = scanMutex.withLock {
        withContext(io) {
            val events = Collections.synchronizedList(mutableListOf<String>())
            val lastCount = AtomicInteger(0)
            val options = scanOptions.copy(budget = AnalysisBudget(maxResources = 12))
            val context = AnalysisContext.create(options.budget).onProgress { event ->
                when (event) {
                    is AnalysisProgress.ResourceMeasured -> {
                        lastCount.set(event.count.toInt())
                        events += "measured ${event.identity.nativeId} (#${event.count}, ${event.elapsed.toMillis()} ms)"
                    }
                    is AnalysisProgress.PhaseStarted -> events += "phase started: ${event.phaseId}"
                    is AnalysisProgress.PhaseCompleted -> events += "phase completed: ${event.phaseId} (${event.count})"
                    is AnalysisProgress.Note -> events += "note: ${event.message}"
                }
            }
            val report = analyzer.analyzeGlobal(options, context)
            ProgressScanResult(
                events = synchronized(events) { events.toList() }.take(40),
                resourceCount = report.resourceCount,
                completeness = report.completeness.name,
                footprintBytes = report.totals.uniqueFootprint,
            )
        }
    }

    /**
     * Cancellation is cooperative: the listener cancels the context after the
     * fifth measured resource, and the next provider safe point throws
     * [StorageError.Cancelled]. No fake "cancelled" state is invented.
     */
    suspend fun cancellationDemo(): CancellationOutcome = scanMutex.withLock {
        withContext(io) {
            val started = System.nanoTime()
            val events = AtomicInteger(0)
            val cancelledAfter = AtomicInteger(-1)
            val context = AnalysisContext.create()
            context.onProgress { event ->
                if (event is AnalysisProgress.ResourceMeasured) {
                    events.incrementAndGet()
                    if (cancelledAfter.get() < 0 && event.count >= 5) {
                        cancelledAfter.set(event.count.toInt())
                        context.cancel()
                    }
                }
            }
            val outcome = runCatching { analyzer.analyzeGlobal(scanOptions, context) }
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            val cancelled = outcome.exceptionOrNull() is StorageError.Cancelled
            val message = when {
                cancelled -> "StorageError.Cancelled observed after ${cancelledAfter.get()} measured resources"
                outcome.isSuccess -> "the scan completed before cancellation was observed"
                else -> "unexpected ${outcome.exceptionOrNull()?.javaClass?.simpleName}: ${outcome.exceptionOrNull()?.message}"
            }
            CancellationOutcome(
                cancelled = cancelled,
                elapsedMs = elapsedMs,
                progressEvents = events.get(),
                message = message,
            )
        }
    }

    suspend fun accountingExperiment(): List<AccountingSnapshot> = scanMutex.withLock {
        withContext(io) {
            listOf(
                AccountingPolicies.COUNT_ONCE_GLOBALLY,
                AccountingPolicies.CANONICAL_OWNER,
                AccountingPolicies.EQUAL_SPLIT,
            ).map { policy ->
                val report = analyzer.analyzeGlobal(scanOptions.copy(accountingPolicy = policy))
                AccountingSnapshot(
                    policyId = report.accountingPolicy.policyId,
                    uniqueFootprint = report.totals.uniqueFootprint,
                    sharedOnly = report.totals.sharedOnly,
                    attributedShared = report.totals.attributedShared,
                    allocations = report.sharedAllocations,
                )
            }
        }
    }

    suspend fun dedupExperiment(): DedupSnapshot = scanMutex.withLock {
        withContext(io) {
            val report = analyzer.analyzeGlobal(scanOptions)
            // Provider summaries only enumerate unique resources, so a true
            // "sum of provider views" also has to add each overlap (the
            // duplicate reporting provider's measurement of an already
            // counted resource).
            val overlapBytes = ByteCount.sum(
                report.overlapMeasurements.mapNotNull { measurement ->
                    measurement.valueOf(MetricType.STORED_CONTENT_SIZE)
                        ?: measurement.measurements.values.firstOrNull { it.value != null }?.value
                },
            )
            DedupSnapshot(
                uniqueFootprint = report.totals.uniqueFootprint,
                providerViewBytes = report.totals.uniqueFootprint + overlapBytes,
                overlaps = report.overlapMeasurements,
                providerSummaries = report.providers,
            )
        }
    }

    suspend fun exclusionExperiment(): ExclusionSnapshot = scanMutex.withLock {
        withContext(io) {
            val before = analyzer.analyzeGlobal(scanOptions)
            val cachePath = File(environment.root, "cache").canonicalPath
            val after = analyzer.analyzeGlobal(
                scanOptions.copy(
                    excluded = { identity ->
                        val path = identity.systemId.value
                        path == cachePath ||
                            path.startsWith(cachePath + File.separator) ||
                            identity.nativeId.endsWith(".tmp") ||
                            identity.nativeId.endsWith(".log")
                    },
                ),
            )
            ExclusionSnapshot(
                beforeBytes = before.totals.uniqueFootprint,
                afterBytes = after.totals.uniqueFootprint,
                beforeCount = before.resourceCount,
                afterCount = after.resourceCount,
                exclusionDiagnostic = after.diagnostics.firstOrNull { it.code == "general.excluded_resources" }?.message,
            )
        }
    }

    suspend fun privacyExperiment(): PrivacySnapshot = scanMutex.withLock {
        withContext(io) {
            val unredacted = analyzer.analyzeGlobal(scanOptions)
            val redacted = analyzer.analyzeGlobal(scanOptions.copy(redactLabels = true))
            PrivacySnapshot(
                unredactedLabels = unredacted.overlapMeasurements.mapNotNull { it.label },
                redactedLabels = redacted.overlapMeasurements.mapNotNull { it.label },
            )
        }
    }

    suspend fun providerFilterExperiment(): ProviderFilterSnapshot = scanMutex.withLock {
        withContext(io) {
            val all = analyzer.analyzeGlobal(scanOptions)
            val filtered = analyzer.analyzeGlobal(
                scanOptions.copy(providerFilter = ProviderId(FilesystemProvider.providerIdValue)),
            )
            ProviderFilterSnapshot(
                allBytes = all.totals.uniqueFootprint,
                filteredBytes = filtered.totals.uniqueFootprint,
                allProviders = all.providers.map { it.providerId.value },
                filteredProviders = filtered.providers.map { it.providerId.value },
            )
        }
    }

    // ------------------------------------------------------------- hot monitoring

    fun setMonitoring(enabled: Boolean) {
        if (enabled) startMonitoring() else stopMonitoring()
    }

    @Synchronized
    private fun startMonitoring() {
        if (monitor != null) return
        val liveAnalyzer = buildLiveAnalyzer()
        val reactive = ReactiveAnalyzer(
            analyzer = liveAnalyzer,
            scope = appScope,
            policy = RefreshPolicy.OnChange(
                probe = { fingerprint.probe() },
                pollInterval = Duration.ofSeconds(3),
            ),
            options = scanOptions,
            dispatcher = io,
        )
        monitorAnalyzer = liveAnalyzer
        monitor = reactive
        _monitorStatus.value = MonitorStatus(enabled = true, phase = "Idle")
        reactive.start()

        monitorJob = appScope.launch {
            var turns = 0
            reactive.state.collect { state ->
                _monitorStatus.value = when (state) {
                    is RerunState.Idle -> _monitorStatus.value.copy(
                        enabled = true,
                        phase = "Idle",
                        error = null,
                    )
                    is RerunState.Running -> {
                        val previous = state.previous
                        _monitorStatus.value.copy(
                            enabled = true,
                            phase = "Running",
                            digest = previous?.digest ?: _monitorStatus.value.digest,
                            footprintBytes = previous?.value?.totals?.uniqueFootprint ?: _monitorStatus.value.footprintBytes,
                            resourceCount = previous?.value?.resourceCount ?: _monitorStatus.value.resourceCount,
                            lastCompletedAt = previous?.at ?: _monitorStatus.value.lastCompletedAt,
                            error = null,
                            turns = turns,
                        )
                    }
                    is RerunState.Completed -> {
                        turns += 1
                        MonitorStatus(
                            enabled = true,
                            phase = "Completed",
                            digest = state.digest,
                            footprintBytes = state.value.totals.uniqueFootprint,
                            resourceCount = state.value.resourceCount,
                            lastCompletedAt = state.at,
                            turns = turns,
                        )
                    }
                    is RerunState.Failed -> _monitorStatus.value.copy(
                        enabled = true,
                        phase = "Failed",
                        error = state.error.message ?: state.error.javaClass.simpleName,
                        lastCompletedAt = state.previous?.at ?: _monitorStatus.value.lastCompletedAt,
                        turns = turns,
                    )
                }
            }
        }
    }

    @Synchronized
    private fun stopMonitoring() {
        monitorJob?.cancel()
        monitorJob = null
        monitor?.close()
        monitor = null
        monitorAnalyzer?.close()
        monitorAnalyzer = null
        _monitorStatus.value = _monitorStatus.value.copy(enabled = false, phase = "Idle", error = null)
    }

    fun rowForStableKey(stableKey: String): ResourceRow? =
        _latest.value?.rows?.firstOrNull { it.identity.stableKey == stableKey }

    suspend fun clearSessions() = withContext(io) { scanSessionDao.clear() }

    // ------------------------------------------------------------------ helpers

    private val scanOptions: AnalysisOptions
        get() = AnalysisOptions(metrics = SCAN_METRICS)

    private fun explorerRows(report: GlobalReport): List<ResourceRow> {
        val discovered = LinkedHashMap<String, StorageResource>()
        for (provider in analyzer.providers()) {
            val context = AnalysisContext.create()
            runCatching {
                provider.discoverResources(null, context).forEach { resource ->
                    discovered.putIfAbsent(resource.identity.stableKey, resource)
                }
            }
        }
        return report.resourceSummaries.mapNotNull { summary ->
            val resource = discovered[summary.identity.stableKey] ?: return@mapNotNull null
            val bytes = summary.footPrintMetrics[MetricType.STORED_CONTENT_SIZE]
                ?: summary.footPrintMetrics.values.firstOrNull()
                ?: ByteCount.ZERO
            ResourceRow(
                identity = resource.identity,
                label = resource.label ?: resource.identity.nativeId,
                kind = resource.kind,
                category = resource.category,
                bytes = bytes,
                containerPath = containerPathOf(resource.identity),
            )
        }.sortedWith(compareByDescending<ResourceRow> { it.bytes.value }.thenBy { it.pathLabel })
    }

    private fun containerPathOf(identity: ResourceIdentity): String {
        if (identity.providerId.value != FilesystemProvider.providerIdValue) {
            return identity.systemId.value
        }
        val parent = runCatching { File(identity.systemId.value).canonicalFile }
            .getOrDefault(File(identity.systemId.value))
        val root = runCatching { environment.root.canonicalFile }.getOrDefault(environment.root)
        return when {
            parent == root -> "storage root"
            parent.path.startsWith(root.path + File.separator) ->
                parent.relativeTo(root).invariantSeparatorsPath
            parent == environment.databaseDir -> "app databases"
            else -> parent.name.ifEmpty { parent.path }
        }
    }

    private fun newFilesystemProvider(): FilesystemProvider = FilesystemProvider(
        roots = listOf(environment.rootPath, environment.databaseDir.canonicalPath),
        // Demo device cluster size: turns FILE_ALLOCATED_SIZE from UNAVAILABLE
        // into a CONVENTION-grade estimate (never claimed as EXACT).
        allocationUnitResolver = { 4096L },
        // Android NIO resolves no FileStore for app-private storage; StatFs is
        // the platform source for the same volume numbers.
        volumeStatsResolver = ::androidVolumeStats,
    )

    private fun buildLiveAnalyzer(): StorageAnalyzer = StorageAnalyzer(
        config = AnalyzerConfig(footprintMetric = MetricType.STORED_CONTENT_SIZE),
        providers = listOf(
            newFilesystemProvider(),
            ArchiveCatalogProvider(File(environment.root, "archives/legacy-backup.zip")),
            ShowcasePreferencesProvider { preferences.entries() },
        ),
    )

    private suspend fun recordSession(
        startedAtEpochMs: Long,
        durationMs: Long,
        footprintBytes: Long,
        resourceCount: Long,
        completeness: String,
        digest: String,
        trigger: String,
        providerCount: Int,
    ) {
        scanSessionDao.insert(
            ScanSessionEntity(
                startedAtEpochMs = startedAtEpochMs,
                durationMs = durationMs,
                footprintBytes = footprintBytes,
                resourceCount = resourceCount,
                completeness = completeness,
                digest = digest,
                trigger = trigger,
                providerCount = providerCount,
            ),
        )
    }

    private fun reportProviderCount(): Int = analyzer.providers().size

    private companion object {
        val SCAN_METRICS: Set<MetricType> = setOf(
            MetricType.STORED_CONTENT_SIZE,
            MetricType.FILE_LOGICAL_SIZE,
            MetricType.FILE_ALLOCATED_SIZE,
        )

        val DETAIL_METRICS: Set<MetricType> = setOf(
            MetricType.STORED_CONTENT_SIZE,
            MetricType.FILE_LOGICAL_SIZE,
            MetricType.FILE_ALLOCATED_SIZE,
            MetricType.ENCODED_CONTENT_SIZE,
            MetricType.TOTAL_CAPACITY,
            MetricType.FREE_CAPACITY,
        )

        val CAPACITY_METRICS: Set<MetricType> = setOf(
            MetricType.TOTAL_CAPACITY,
            MetricType.FREE_CAPACITY,
            MetricType.RESERVED_CAPACITY,
        )
    }
}
