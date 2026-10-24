package com.mohamed.storageanalyzer.filesystem

import com.mohamed.storageanalyzer.core.capability.Capabilities
import com.mohamed.storageanalyzer.core.capability.StorageCapability
import com.mohamed.storageanalyzer.core.config.AnalysisContext
import com.mohamed.storageanalyzer.core.config.BudgetedAnalysisContext
import com.mohamed.storageanalyzer.core.identity.IdentityStability
import com.mohamed.storageanalyzer.core.identity.PathIdentity
import com.mohamed.storageanalyzer.core.identity.ProviderId
import com.mohamed.storageanalyzer.core.identity.ResourceIdentity
import com.mohamed.storageanalyzer.core.identity.ResourceKind
import com.mohamed.storageanalyzer.core.identity.StorageSystemId
import com.mohamed.storageanalyzer.core.measurement.ByteCount
import com.mohamed.storageanalyzer.core.measurement.MeasurementAccuracy
import com.mohamed.storageanalyzer.core.measurement.MeasurementMethod
import com.mohamed.storageanalyzer.core.measurement.MeasurementProvenance
import com.mohamed.storageanalyzer.core.measurement.MeasurementState
import com.mohamed.storageanalyzer.core.measurement.MetricType
import com.mohamed.storageanalyzer.core.measurement.SingleMeasurement
import com.mohamed.storageanalyzer.core.provider.StorageProvider
import com.mohamed.storageanalyzer.core.provider.StorageSystemRef
import com.mohamed.storageanalyzer.core.resource.OwnerRef
import com.mohamed.storageanalyzer.core.resource.ReferenceLink
import com.mohamed.storageanalyzer.core.resource.RelationType
import com.mohamed.storageanalyzer.core.resource.StorageCategory
import com.mohamed.storageanalyzer.core.resource.StorageResource
import com.mohamed.storageanalyzer.core.result.Completeness
import com.mohamed.storageanalyzer.core.result.DiagnosticSeverity
import com.mohamed.storageanalyzer.core.result.StorageDiagnostic
import com.mohamed.storageanalyzer.core.result.StorageMeasurement
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.FileStore
import java.time.Instant
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

/**
 * Read-only filesystem analytics provider built on `java.nio` only — no
 * native platform calls, no engine dependencies.
 *
 * Systems are the configured scan roots. Resource identity follows the
 * canonical [PathIdentity] scheme (`filesystem` provider, canonical parent
 * directory as system, base name as native id) so that other providers that
 * touch physical files (e.g. the SQLite provider via dedup aliases) resolve to
 * the *same* stable key.
 *
 * Accounting (documented in docs/ACCOUNTING.md):
 * - **Files carry the bytes.** Every regular file reports its exact logical
 *   length as [MetricType.STORED_CONTENT_SIZE] / [MetricType.FILE_LOGICAL_SIZE].
 * - **Directories are structural.** They are discovered for traversal,
 *   ownership and grouping, and their own size metrics are declared
 *   NOT_APPLICABLE — the same bytes are never claimed twice. Folder totals are
 *   derived by applications/policies from file rows (see `Groupings`).
 * - The system's **root directory doubles as the volume facade**: it carries
 *   [MetricType.TOTAL_CAPACITY], [MetricType.FREE_CAPACITY] and (where the
 *   platform exposes it) [MetricType.RESERVED_CAPACITY]. A host-supplied
 *   [VolumeStats] resolver takes precedence over `java.nio`'s FileStore, which
 *   is what Android consumers use to bridge `StatFs`.
 * - **Hard links**: when the JVM exposes `fileKey` (POSIX/NTFS), a second path
 *   pointing at the same inode is reported with `decomposes = <first path>`
 *   so it is excluded from unique-footprint sums (the first path keeps the
 *   bytes) while remaining visible with its provenance warning.
 * - **Symlinks are never followed** (non-destructive, no cycles); they are
 *   detected and reported via diagnostics, not measured.
 */
class FilesystemProvider(
    /** Scan roots (absolute paths). Multiple roots are independent systems. */
    roots: List<String> = emptyList(),

    /**
     * Supplies the allocation-unit (cluster) size of a [FileStore] when the
     * host knows it. Without it, [MetricType.FILE_ALLOCATED_SIZE] is honestly
     * UNAVAILABLE — it is never guessed.
     */
    private val allocationUnitResolver: ((FileStore) -> Long?)? = null,

    /**
     * Supplies volume space numbers for a scan root, taking precedence over the
     * JVM's [FileStore]. Use it to bridge platform APIs (Android `StatFs`) or to
     * correct an unreliable store; returning `null` falls back to java.nio.
     */
    private val volumeStatsResolver: ((Path) -> VolumeStats?)? = null,
) : StorageProvider {

    companion object {
        const val providerIdValue = PathIdentity.FILESYSTEM_PROVIDER_ID

        /** Diagnostic code emitted per skipped symlink (throttled). */
        const val DIAG_SYMLINK_SKIPPED = "fs.symlink.skipped"

        /** Provenance warning attached to shared-inode duplicates. */
        const val WARN_SHARED_INODE = "fs.hardlink.shared-inode"

        /** Provenance limitation attached to host-supplied volume stats. */
        const val LIMIT_HOST_VOLUME_STATS = "fs.volume.host-supplied"
    }

    private class SystemState(val root: File) {
        val systemId = StorageSystemId(root.canonicalPath)
    }

    override val providerId: ProviderId = ProviderId(providerIdValue)

    private val systemList = ArrayList<SystemState>()
    private val systemsById = LinkedHashMap<String, SystemState>()

    init {
        roots.forEach { addRoot(it) }
    }

    /** Registers a scan root; duplicates collapse to one system. */
    fun addRoot(path: String): StorageSystemId {
        val canonical = File(path).canonicalFile
        systemsById[canonical.path]?.let { return it.systemId }
        val s = SystemState(canonical)
        systemList.add(s)
        return s.systemId
    }

    fun systems(): List<StorageSystemId> = systemList.map { it.systemId }

    private fun stateOf(systemId: StorageSystemId): SystemState? = systemList.firstOrNull { it.systemId == systemId }

    @Volatile
    private var symlinkSkipsReported = 0

    // ------------------------------------------------------------ identity

    override fun describe(): String =
        "JVM filesystem provider (java.nio; no native platform calls; allocation and volume stats need host-supplied values on platforms without FileStore)"

    override fun capabilities(): Capabilities {
        val allocatorPresent = allocationUnitResolver != null
        val supported = buildSet {
            add(StorageCapability.FILE_LENGTH)
            add(StorageCapability.VOLUME_SPACE_STATS)
            add(StorageCapability.SYMLINK_DETECTION)
            add(StorageCapability.ENTITY_LEVEL_ANALYTICS)
            add(StorageCapability.RELATION_TRAVERSAL)
            if (allocatorPresent) add(StorageCapability.FILE_ALLOCATED_SIZE)
            if (probeFileKey() != null) add(StorageCapability.HARD_LINK_DETECTION)
        }
        val unsupported = buildMap {
            if (!allocatorPresent) {
                put(
                    StorageCapability.FILE_ALLOCATED_SIZE,
                    "the JVM exposes no portable allocation-unit size; supply one via allocationUnitResolver to enable CONVENTION-grade rounding",
                )
            }
            if (probeFileKey() == null) {
                put(
                    StorageCapability.HARD_LINK_DETECTION,
                    "this platform/JDK does not expose fileKey (inode) attributes; hard links are counted per path",
                )
            }
            put(
                StorageCapability.SPARSE_FILE_DETECTION,
                "sparse layout requires native st_blocks access; only a heuristic suspicion can be raised locally",
            )
        }
        return Capabilities(supported, unsupported)
    }

    override fun capabilitiesFor(systemId: StorageSystemId): Capabilities {
        val s = stateOf(systemId) ?: return Capabilities(emptySet(), emptyMap())
        if (!s.root.exists()) {
            return Capabilities(emptySet(), mapOf(StorageCapability.FILE_LENGTH to "scan root does not exist"))
        }
        return capabilities()
    }

    private fun probeFileKey(): Any? {
        val probe = systemList.firstOrNull()?.root ?: File(".")
        val candidate = if (probe.isFile) probe else probe.listFiles()?.firstOrNull { it.isFile } ?: return null
        return runCatching {
            Files.readAttributes(candidate.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).fileKey()
        }.getOrNull()
    }

    override fun discoverSystems(): List<StorageSystemRef> = systemList.map { s ->
        StorageSystemRef(
            systemId = s.systemId,
            label = s.root.name.ifEmpty { s.root.path },
            kind = ResourceKind.DIRECTORY,
            capabilities = capabilitiesFor(s.systemId),
        )
    }

    // ------------------------------------------------------------ discovery

    override fun discoverResources(
        systemId: StorageSystemId?,
        ctx: AnalysisContext,
    ): Sequence<StorageResource> = sequence {
        val selected = systemId?.let { listOfNotNull(stateOf(it)) } ?: systemList
        for (s in selected) {
            if (!s.root.exists()) {
                ctx.diagnostic(
                    StorageDiagnostic(
                        code = "fs.root_missing",
                        severity = DiagnosticSeverity.ERROR,
                        message = "scan root '${s.root.path}' does not exist",
                        identity = null,
                        providerId = providerId,
                        suggestedAction = "remove the root or restore the path; the provider never creates directories",
                    ),
                )
                continue
            }
            ctx.ensureActive()
            yieldAll(walkSystem(s, ctx))
        }
    }

    /**
     * Iterative DFS. Each directory stream is fully drained into a transient
     * list and closed *before* any resource is yielded, so an early-abandoned
     * sequence leaks no open handles.
     */
    private fun walkSystem(s: SystemState, ctx: AnalysisContext): Sequence<StorageResource> = sequence {
        val pending = ArrayDeque<Path>()
        pending.addLast(s.root.toPath())
        while (pending.isNotEmpty()) {
            ctx.ensureActive()
            val dir = pending.removeLast()
            // Every directory in scope is a resource; the root doubles as the
            // volume facade for capacity metrics.
            yield(directoryResource(dir, s))
            val entries: List<Path> = try {
                Files.newDirectoryStream(dir).use { ds -> ds.asSequence().toList() }
            } catch (e: java.nio.file.FileSystemException) {
                ctx.diagnostic(
                    StorageDiagnostic(
                        code = "fs.dir_unreadable",
                        severity = DiagnosticSeverity.WARNING,
                        message = "directory '${dir}' is not readable: ${e.reason ?: e.message}",
                        identity = directoryIdentityOf(dir),
                        providerId = providerId,
                    ),
                )
                continue
            }

            val subdirs = ArrayList<Path>(entries.size)
            for (e in entries) {
                ctx.ensureActive()
                val isLink = Files.isSymbolicLink(e)
                if (isLink) {
                    if (symlinkSkipsReported < SYMLINK_DIAGNOSTIC_LIMIT) {
                        symlinkSkipsReported++
                        ctx.diagnostic(
                            StorageDiagnostic(
                                code = DIAG_SYMLINK_SKIPPED,
                                severity = DiagnosticSeverity.INFO,
                                message = "symbolic link '$e' detected; symlinks are never followed and their target storage is not attributed (the link itself is not a measured resource)",
                                providerId = providerId,
                            ),
                        )
                    }
                    continue
                }
                val attrs = runCatching {
                    Files.readAttributes(e, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                }.getOrNull()
                if (attrs == null) {
                    ctx.diagnostic(
                        StorageDiagnostic(
                            code = "fs.entry_unreadable",
                            severity = DiagnosticSeverity.WARNING,
                            message = "'$e' could not be stat'ed during scan",
                            providerId = providerId,
                        ),
                    )
                    continue
                }
                when {
                    // Directory resources are yielded when their entry is popped
                    // from the queue (see the top of the loop), so the scan root
                    // itself is emitted exactly once too.
                    attrs.isDirectory -> subdirs.add(e)
                    attrs.isRegularFile -> yield(fileResource(e, attrs, s))
                    else -> {
                        // pipes, sockets, devices, ...: visible and honestly unmeasured
                        ctx.diagnostic(
                            StorageDiagnostic(
                                code = "fs.special_file_skipped",
                                severity = DiagnosticSeverity.INFO,
                                message = "special filesystem entry '$e' (neither regular file nor directory) is not measurable via java.nio",
                                providerId = providerId,
                            ),
                        )
                    }
                }
            }
            // DFS with stable order: push in reverse so the first child is visited first.
            for (d in subdirs.asReversed()) pending.addLast(d)
        }
    }

    private fun directoryIdentityOf(path: Path): ResourceIdentity =
        PathIdentity.forChild(path.parent?.toFile() ?: File(".").canonicalFile, path.fileName?.toString() ?: path.toString(), ResourceKind.DIRECTORY)

    private fun directoryResource(path: Path, s: SystemState): StorageResource {
        val identity = directoryIdentityOf(path)
        val parentPath = path.parent
        val owner = OwnerRef(
            id = (parentPath ?: path).toAbsolutePath().toString(),
            kind = OwnerRef.OwnerKind.PATH,
        )
        return StorageResource(
            identity = identity,
            providerId = providerId,
            systemId = s.systemId,
            label = path.fileName.toString(),
            kind = ResourceKind.DIRECTORY,
            category = StorageCategory.OTHER,
            identityStability = IdentityStability.STABLE,
            owners = listOf(owner),
        )
    }

    private fun fileResource(
        path: Path,
        attrs: BasicFileAttributes,
        s: SystemState,
    ): StorageResource {
        val identity = PathIdentity.forChild(path.parent?.toFile() ?: File(".").canonicalFile, path.fileName.toString(), ResourceKind.FILE)
        val owners = listOf(
            OwnerRef(
                id = path.parent?.toAbsolutePath()?.toString() ?: s.systemId.value,
                kind = OwnerRef.OwnerKind.PATH,
            ),
        )
        val base = StorageResource(
            identity = identity,
            providerId = providerId,
            systemId = s.systemId,
            label = path.fileName.toString(),
            kind = ResourceKind.FILE,
            category = categoryOf(path.fileName.toString()),
            identityStability = IdentityStability.STABLE,
            owners = owners,
        )
        // Hard-link detection: a later path over the same inode is a dedup
        // view of the first-seen path (first keeps the bytes).
        val key = attrs.fileKey()
        if (key != null) {
            val canonical = sharedInodes.putIfAbsent(s.systemId.value + "\u0000" + key.toString(), identity)
            if (canonical != null && canonical != identity) {
                return base.copy(
                    decomposes = canonical,
                    owners = owners,
                )
            }
        }
        return base
    }

    private val sharedInodes = java.util.concurrent.ConcurrentHashMap<String, ResourceIdentity>()

    /** Throttle for per-symlink diagnostics. */
    private val SYMLINK_DIAGNOSTIC_LIMIT = 20

    // ------------------------------------------------------------ measurement

    override fun defaultMetricTypes(): Set<MetricType> = setOf(
        MetricType.STORED_CONTENT_SIZE,
        MetricType.FILE_LOGICAL_SIZE,
        MetricType.FILE_ALLOCATED_SIZE,
        MetricType.TOTAL_CAPACITY,
        MetricType.FREE_CAPACITY,
    )

    private fun prov(
        now: Instant,
        method: MeasurementMethod,
        accuracy: MeasurementAccuracy,
        limitations: Set<String> = emptySet(),
        warnings: Set<String> = emptySet(),
        note: String? = null,
    ) = MeasurementProvenance(
        method = method,
        accuracy = accuracy,
        measuredAt = now,
        providerId = providerIdValue,
        backend = "java.nio (JDK " + System.getProperty("java.version") + ")",
        limitations = limitations,
        warnings = warnings,
        note = note,
    )

    private fun absent(metric: MetricType, now: Instant, reason: String): SingleMeasurement =
        SingleMeasurement(
            metric = metric,
            value = null,
            state = MeasurementState.UNAVAILABLE,
            provenance = prov(now, MeasurementMethod.PROVIDER_METADATA, MeasurementAccuracy.UNAVAILABLE),
            reason = reason,
        )

    private fun notApplicable(metric: MetricType, now: Instant, reason: String): SingleMeasurement =
        SingleMeasurement(
            metric = metric,
            value = null,
            state = MeasurementState.NOT_APPLICABLE,
            provenance = MeasurementProvenance.absent(
                providerId = providerIdValue,
                measuredAt = now,
                method = MeasurementMethod.PROVIDER_METADATA,
                reason = reason,
            ),
            reason = reason,
        )

    override fun measure(
        resource: ResourceIdentity,
        metrics: Set<MetricType>,
        ctx: AnalysisContext,
    ): StorageMeasurement {
        ctx.ensureActive()
        val now = Instant.now()
        val requested = metrics.ifEmpty { defaultMetricTypes() }
        val path = runCatching { File(resource.systemId.value, resource.nativeId).toPath() }.getOrNull()
        val attrs = path?.let {
            runCatching { Files.readAttributes(it, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS) }.getOrNull()
        }
        if (path == null || attrs == null) {
            return StorageMeasurement(
                identity = resource,
                kind = resource.kind,
                category = categoryOf(resource.nativeId),
                measurements = requested.associateWith { m -> absent(m, now, "resource is gone or unreadable at measurement time") },
                completeness = Completeness.PARTIAL,
                measuredAt = now,
            )
        }
        val out = LinkedHashMap<MetricType, SingleMeasurement>()
        for (metric in requested) {
            out[metric] = when {
                attrs.isDirectory -> directoryMetric(metric, resource, path, now)
                attrs.isRegularFile -> fileMetric(metric, resource, path, attrs, now)
                else -> notApplicable(metric, now, "special filesystem entries are not measurable via java.nio")
            }
        }
        return StorageMeasurement(
            identity = resource,
            kind = resource.kind,
            category = categoryOf(resource.nativeId),
            measurements = out,
            completeness = Completeness.COMPLETE,
            measuredAt = now,
        )
    }

    private fun fileMetric(
        metric: MetricType,
        resource: ResourceIdentity,
        path: Path,
        attrs: BasicFileAttributes,
        now: Instant,
    ): SingleMeasurement = when (metric) {
        MetricType.STORED_CONTENT_SIZE, MetricType.FILE_LOGICAL_SIZE ->
            SingleMeasurement.measured(
                metric,
                attrs.size(),
                prov(now, MeasurementMethod.FILE_LENGTH, MeasurementAccuracy.EXACT),
            )
        MetricType.FILE_ALLOCATED_SIZE -> {
            val store = runCatching { Files.getFileStore(path) }.getOrNull()
            val unit = store?.let { allocationUnitResolver?.invoke(it) }
            when {
                unit == null || unit <= 0L -> absent(
                    metric,
                    now,
                    "allocated size needs the volume's allocation-unit size; the JVM does not expose it (supply it via provider config)",
                )
                else -> {
                    val rounded = ((attrs.size() + unit - 1) / unit) * unit
                    val sparse = rounded < attrs.size()
                    SingleMeasurement(
                        metric = metric,
                        value = ByteCount.of(rounded),
                        state = MeasurementState.MEASURED,
                        provenance = prov(
                            now,
                            MeasurementMethod.ROUND_TO_CLUSTERS,
                            MeasurementAccuracy.CONVENTION,
                            limitations = setOf("fs.allocated.rounds-up"),
                            warnings = if (sparse) setOf("fs.sparse-suspected") else emptySet(),
                            note = if (sparse) "rounded allocation below logical length suggests sparse storage" else null,
                        ),
                    )
                }
            }
        }
        MetricType.METADATA_STORAGE ->
            absent(metric, now, "extended attributes/xattrs exist but are not measurable via the portable java.nio provider")
        MetricType.TEMPORARY_STORAGE ->
            notApplicable(metric, now, "temporary-storage accounting applies to journals/WAL/tmp containers, not regular files")
        else -> notApplicable(metric, now, "metric '${metric.id}' is not exposed by the filesystem provider for files")
    }

    private fun directoryMetric(
        metric: MetricType,
        resource: ResourceIdentity,
        path: Path,
        now: Instant,
    ): SingleMeasurement = when (metric) {
        MetricType.TOTAL_CAPACITY, MetricType.FREE_CAPACITY, MetricType.RESERVED_CAPACITY -> {
            // Volume capacity is exposed on the scanned system's root directory
            // (the volume facade); nested directories are NOT_APPLICABLE.
            val isScannedRoot = runCatching { path.toRealPath().toString() }.getOrDefault(path.toString()) ==
                systemList.firstOrNull { it.systemId.value == path.toRealPath().toString() }?.root?.path
            if (isScannedRoot) volumeMetric(metric, path, now)
            else notApplicable(metric, now, "volume capacity is only reported on the scan root (volume facade)")
        }
        MetricType.STORED_CONTENT_SIZE, MetricType.FILE_LOGICAL_SIZE ->
            notApplicable(metric, now, "directories hold no independent payload; contents are measured per file to keep sums honest")
        MetricType.FILE_ALLOCATED_SIZE ->
            notApplicable(metric, now, "directory-entry metadata size is not exposed portably")
        else -> notApplicable(metric, now, "metric '${metric.id}' is not exposed by the filesystem provider for directories")
    }

    private fun volumeMetric(metric: MetricType, root: Path, now: Instant): SingleMeasurement {
        if (volumeStatsResolver != null) {
            val stats = try {
                volumeStatsResolver.invoke(root)
            } catch (e: Exception) {
                return absent(metric, now, "host volume resolver failed for '$root': ${e.message ?: e.javaClass.simpleName}")
            }
            if (stats != null) return hostVolumeMetric(metric, stats, now)
        }
        val store = runCatching { Files.getFileStore(root) }.getOrNull()
            ?: return absent(metric, now, "volume statistics require a resolvable FileStore for '$root' or a host volume resolver")
        return try {
            val value = when (metric) {
                MetricType.TOTAL_CAPACITY -> store.totalSpace
                MetricType.FREE_CAPACITY -> store.usableSpace
                MetricType.RESERVED_CAPACITY -> {
                    val reserved = store.totalSpace - store.unallocatedSpace
                    if (reserved < 0L) return absent(metric, now, "platform did not expose a distinct reserved capacity")
                    reserved
                }
                else -> return notApplicable(metric, now, "not a volume metric")
            }
            SingleMeasurement.measured(metric, value, prov(now, MeasurementMethod.VOLUME_SPACE_STATS, MeasurementAccuracy.EXACT))
        } catch (e: java.io.IOException) {
            absent(metric, now, "volume statistics failed: ${e.message}")
        }
    }

    private fun hostVolumeMetric(metric: MetricType, stats: VolumeStats, now: Instant): SingleMeasurement {
        val inconsistent = stats.totalBytes < 0L || stats.freeBytes < 0L ||
            stats.freeBytes > stats.totalBytes || (stats.reservedBytes != null && stats.reservedBytes < 0L)
        if (inconsistent) {
            return absent(
                metric,
                now,
                "host volume stats are inconsistent (total=${stats.totalBytes}, free=${stats.freeBytes}, reserved=${stats.reservedBytes})",
            )
        }
        val value = when (metric) {
            MetricType.TOTAL_CAPACITY -> stats.totalBytes
            MetricType.FREE_CAPACITY -> stats.freeBytes
            MetricType.RESERVED_CAPACITY -> stats.reservedBytes
                ?: return absent(metric, now, "host volume stats do not include reserved capacity")
            else -> return notApplicable(metric, now, "not a volume metric")
        }
        return SingleMeasurement.measured(
            metric,
            value,
            prov(
                now,
                MeasurementMethod.HOST_VOLUME_STATS,
                MeasurementAccuracy.EXACT,
                limitations = setOf(LIMIT_HOST_VOLUME_STATS),
                note = "volume snapshot supplied by the host adapter; java.nio FileStore was not used",
            ),
        )
    }

    // ------------------------------------------------------------ graph edges

    override fun related(resource: ResourceIdentity, ctx: AnalysisContext): List<ReferenceLink> {
        if (resource.kind != ResourceKind.DIRECTORY) return emptyList()
        val dir = runCatching { File(resource.systemId.value, resource.nativeId).toPath() }.getOrNull() ?: return emptyList()
        if (!Files.isDirectory(dir)) return emptyList()
        val budgeted = ctx as? BudgetedAnalysisContext
        val out = ArrayList<ReferenceLink>()
        try {
            Files.newDirectoryStream(dir).use { ds ->
                for (child in ds) {
                    ctx.ensureActive()
                    if (budgeted != null && !budgeted.consumeEdge()) break
                    if (Files.isSymbolicLink(child)) continue // skipped links are not resources
                    val kind = if (Files.isDirectory(child)) ResourceKind.DIRECTORY else ResourceKind.FILE
                    out += ReferenceLink(
                        fromProviderId = providerId,
                        toIdentity = PathIdentity.forChild(child.parent.toFile(), child.fileName.toString(), kind),
                        relation = RelationType.CONTAINS,
                        label = child.fileName.toString(),
                    )
                }
            }
        } catch (e: java.nio.file.FileSystemException) {
            // The directory may have vanished between discovery and traversal.
        }
        return out
    }

    // ------------------------------------------------------------ health

    override fun healthCheck(ctx: AnalysisContext): List<StorageDiagnostic> {
        val out = ArrayList<StorageDiagnostic>()
        for (s in systemList) {
            ctx.ensureActive()
            val identity = PathIdentity.forPath(s.root.path, ResourceKind.DIRECTORY)
            if (!s.root.exists()) {
                out += StorageDiagnostic(
                    code = "fs.root_missing",
                    severity = DiagnosticSeverity.ERROR,
                    message = "scan root '${s.root.path}' does not exist",
                    identity = identity,
                    providerId = providerId,
                    suggestedAction = "remove the root from provider configuration or restore the path",
                )
                continue
            }
            if (!s.root.canRead()) {
                out += StorageDiagnostic(
                    code = "fs.root_unreadable",
                    severity = DiagnosticSeverity.ERROR,
                    message = "scan root '${s.root.path}' is not readable by this process",
                    identity = identity,
                    providerId = providerId,
                    suggestedAction = "grant read permission; the provider never changes permissions",
                )
                continue
            }
            val store = runCatching { Files.getFileStore(s.root.toPath()) }.getOrNull()
            if (store == null) {
                val resolver = volumeStatsResolver
                if (resolver == null) {
                    out += StorageDiagnostic(
                        code = "fs.filestore_unresolved",
                        severity = DiagnosticSeverity.WARNING,
                        message = "FileStore for '${s.root.path}' could not be resolved and no host volume resolver is configured; volume statistics will be unavailable",
                        identity = identity,
                        providerId = providerId,
                    )
                } else {
                    val resolved = runCatching { resolver.invoke(s.root.toPath()) }
                    val failure = resolved.exceptionOrNull()
                    if (failure != null) {
                        out += StorageDiagnostic(
                            code = "fs.volume_resolver_failed",
                            severity = DiagnosticSeverity.WARNING,
                            message = "host volume resolver failed for '${s.root.path}': ${failure.message ?: failure.javaClass.simpleName}",
                            identity = identity,
                            providerId = providerId,
                        )
                    } else if (resolved.getOrNull() == null) {
                        out += StorageDiagnostic(
                            code = "fs.filestore_unresolved",
                            severity = DiagnosticSeverity.WARNING,
                            message = "FileStore for '${s.root.path}' could not be resolved and the host volume resolver returned no stats; volume statistics will be unavailable",
                            identity = identity,
                            providerId = providerId,
                        )
                    }
                }
            } else if (allocationUnitResolver == null) {
                out += StorageDiagnostic(
                    code = "fs.allocation_unit_unavailable",
                    severity = DiagnosticSeverity.INFO,
                    message = "no allocation-unit resolver configured; allocated sizes are reported as unavailable (never guessed)",
                    identity = identity,
                    providerId = providerId,
                )
            }
        }
        return out
    }

    override fun close() {
        // Nothing persistent: discovery opens no retained handles.
    }

    // ------------------------------------------------------------ helpers

    private fun categoryOf(name: String): StorageCategory {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "log", "log.1", "txt.log" -> StorageCategory.LOGS
            "pdf", "doc", "docx", "odt", "md", "rtf" -> StorageCategory.DOCUMENT
            "png", "jpg", "jpeg", "gif", "bmp", "webp", "mp4", "mov", "avi", "mkv", "mp3", "wav", "flac" -> StorageCategory.MEDIA
            "zip", "gz", "bz2", "xz", "7z", "tar", "tgz", "jar" -> StorageCategory.ARCHIVE
            "exe", "dll", "so", "dylib", "apk", "bin" -> StorageCategory.EXECUTABLE
            "kt", "java", "ts", "tsx", "py", "go", "rs", "c", "h", "cpp", "hpp", "cs", "rb" -> StorageCategory.SOURCE_CODE
            "bak", "backup", "sqlite3.bak" -> StorageCategory.BACKUP
            "tmp", "temp" -> StorageCategory.TEMPORARY
            "db", "sqlite", "sqlite3", "db3", "h2", "mv.db", "mvdb" -> StorageCategory.DATABASE
            "part", "partial", "crdownload", "cache" -> StorageCategory.CACHE
            else -> StorageCategory.OTHER
        }
    }
}
