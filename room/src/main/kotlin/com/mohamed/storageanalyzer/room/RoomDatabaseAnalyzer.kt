package com.mohamed.storageanalyzer.room

import com.mohamed.storageanalyzer.core.StorageAnalyzer
import com.mohamed.storageanalyzer.core.config.AnalysisBudget
import com.mohamed.storageanalyzer.core.config.AnalysisContext
import com.mohamed.storageanalyzer.core.config.AnalysisOptions
import com.mohamed.storageanalyzer.core.entity.EntityDescriptor
import com.mohamed.storageanalyzer.core.entity.spi.EntityResourceResolver
import com.mohamed.storageanalyzer.core.identity.PathIdentity
import com.mohamed.storageanalyzer.core.identity.ResourceIdentity
import com.mohamed.storageanalyzer.core.identity.ResourceKind
import com.mohamed.storageanalyzer.core.measurement.ByteCount
import com.mohamed.storageanalyzer.core.measurement.MetricType
import com.mohamed.storageanalyzer.core.result.DiagnosticSeverity
import com.mohamed.storageanalyzer.core.result.GlobalReport
import com.mohamed.storageanalyzer.core.result.StorageDiagnostic
import com.mohamed.storageanalyzer.filesystem.FilesystemProvider
import com.mohamed.storageanalyzer.sqlite.SQLiteDatabaseSpec
import com.mohamed.storageanalyzer.sqlite.SQLiteResourceIds
import com.mohamed.storageanalyzer.sqlite.SQLiteStorageProvider
import java.io.File
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CancellationException

/**
 * Options of the Room integration.
 */
public data class RoomAnalysisConfig(
    /**
     * Opt-in: per-record size scan per entity (the heaviest path).
     * Off by default; when enabled it is budget-capped by the run's
     * [AnalysisBudget] like every other scan.
     */
    public val includeRowScan: Boolean = false,

    /** Budget for the underlying global run (unless the caller supplies options). */
    public val budget: AnalysisBudget = AnalysisBudget.UNBOUNDED,

    /** Carries the full engine report inside every result. */
    public val includeGlobalReport: Boolean = true,

    /** Busy timeout for the internal read-only probe connection. */
    public val probeBusyTimeoutMs: Int = 1_000,
)

/**
 * Where an analyzed entity's declaration came from.
 */
public enum class RoomEntitySource {

    /** Declared by the parsed Room schema bundle. */
    SCHEMA_BUNDLE,

    /** Discovered in the live catalog (no bundle, or a bundle-table drift). */
    LIVE_CATALOG,
}

/**
 * Which physical sidecar convention a file belongs to.
 */
public enum class SidecarRole { WAL, SHM, JOURNAL, OTHER }

/**
 * One physical sidecar file of the database (WAL / `-shm` / rollback journal).
 */
public data class SidecarStorage(
    public val fileName: String,
    public val role: SidecarRole,
    public val bytes: ByteCount,
)

/**
 * Physical storage seen by one Room entity's backing table.
 *
 * Bytes are honest optionals: `null` means the engine could not measure the
 * metric in this run (never a silent zero).
 */
public data class RoomEntityStorage(
    public val entityTypeId: String,
    public val tableName: String,
    public val displayName: String,
    public val source: RoomEntitySource,
    /** `database_table_storage` of the backing table (null = unmeasured). */
    public val tableBytes: ByteCount?,
    /** Sum of the entity's declared indexes' `database_index_storage`. */
    public val indexesBytes: ByteCount?,
    /**
     * Row count via the probe's `SELECT COUNT(*)` (a count is not a byte
     * metric and is never attributed into totals).
     */
    public val recordCount: Long?,
    /** Opt-in [RoomAnalysisConfig.includeRowScan] aggregate: sum of record sizes. */
    public val rowsBytesSum: ByteCount?,
    /** Opt-in [RoomAnalysisConfig.includeRowScan]: largest single record. */
    public val maxRowBytes: ByteCount?,
)

/**
 * Aggregated, Room-shaped totals of one analysis.
 */
public data class RoomTotals(
    public val uniqueFootprint: ByteCount,
    public val dbFileBytes: ByteCount?,
    public val pageCapacity: ByteCount?,
    public val freelist: ByteCount?,
    public val sidecarsBytes: ByteCount,
)

/**
 * The single-shot (cold) result of a Room database analysis.
 *
 * `drift` reports Room-specific consistency findings against the supplied
 * schema bundle; `globalReport` carries the full engine report (so nothing
 * the engine can say is hidden by the Room-shaped view).
 */
public data class RoomAnalysis(
    public val dbPath: String,
    public val liveSchemaVersion: Int?,
    public val bundleSchemaVersion: Int?,
    public val identityHashLive: String?,
    public val identityHashBundle: String?,
    public val drift: List<StorageDiagnostic>,
    /** Entities sorted by table bytes (desc, unmeasured last). */
    public val entities: List<RoomEntityStorage>,
    public val sidecars: List<SidecarStorage>,
    public val totals: RoomTotals,
    public val globalReport: GlobalReport?,
    /**
     * Deterministic change key: the probe fingerprint when the database can
     * be fingerprinted, a content digest otherwise.
     */
    public val digest: String,
    public val generatedAt: Instant,
    public val durationMs: Long,
    public val probeFingerprint: String?,
) {

    /** Convenience lookup by entity type id. */
    public fun entity(entityTypeId: String): RoomEntityStorage? =
        entities.firstOrNull { it.entityTypeId == entityTypeId }
}

/**
 * One-shot (cold) Room database investigation.
 *
 * Builds the complete provider wiring a developer would otherwise repeat by
 * hand — filesystem provider for db + sidecars (dedup owner first), SQLite
 * provider, exclusion of unrelated files in the same directory, entity
 * descriptors from the Room schema bundle — and returns a [
 * RoomAnalysis] per call. Reused across calls by [RoomStorageMonitor].
 *
 * No androidx dependency anywhere; the SQLite JDBC driver is required at
 * runtime exactly like the base `:sqlite` provider.
 */
public class RoomDatabaseAnalyzer(
    private val spec: SQLiteDatabaseSpec,
    private val schemaBundle: RoomSchemaBundle? = null,
    private val config: RoomAnalysisConfig = RoomAnalysisConfig(),
) : AutoCloseable {

    private val fileBased: Boolean =
        spec.path != ":memory:" && !spec.path.startsWith("jdbc:") && !spec.path.startsWith("file:")

    private val dbFileName: String = if (fileBased) File(spec.path).canonicalFile.name else spec.path

    /** Scan root is the database's directory; the run-level exclusion keeps unrelated files out. */
    private val fsProvider: FilesystemProvider? = if (fileBased) {
        FilesystemProvider(roots = listOf(File(spec.path).canonicalFile.parent ?: "."))
    } else {
        null
    }

    private val sqliteProvider = SQLiteStorageProvider(listOf(spec))

    private val globalAnalyzer = StorageAnalyzer(providers = listOfNotNull(fsProvider, sqliteProvider))

    /** Shared read-only probe (also used by [RoomStorageMonitor] fingerprinting). */
    internal val probe = RoomDbProbe(spec, config.probeBusyTimeoutMs)

    /** Whether this database can be fingerprinted natively (file-backed only). */
    public val fingerprintCapable: Boolean = fileBased

    /** Cheap content fingerprint; null = unknown (monitor then re-runs on its own cadence). */
    public fun fingerprint(): String? = runCatching { probe.fingerprint() }.getOrNull()

    /** Options for a focused db + sidecar run (excludes unrelated files). */
    public fun defaultOptions(): AnalysisOptions = AnalysisOptions(
        metrics = emptySet(), // every provider's default metric set
        budget = config.budget,
        excluded = { identity -> fileBased && isForeignFsResource(identity) },
    )

    /**
     * Cold single-shot analysis. Each call opens fresh connections (per-run
     * convention of the provider), measures database + sidecars and returns
     * the Room-shaped result with drift diagnostics.
     */
    public fun analyzeOnce(
        options: AnalysisOptions = defaultOptions(),
        context: com.mohamed.storageanalyzer.core.config.DefaultAnalysisContext = AnalysisContext.create(options.budget),
    ): RoomAnalysis {
        val startNanos = System.nanoTime()
        val fingerprint = runCatching { probe.fingerprint() }.getOrNull()
        val liveVersion = runCatching { probe.userVersion() }.getOrNull()
        val liveIdentity = runCatching { probe.masterIdentityHash() }.getOrNull()
        val probeTables = runCatching { probe.tableNames() }.getOrDefault(emptyList())

        val global = globalAnalyzer.analyzeGlobal(options, context)
        val summaries = global.resourceSummaries

        val systemId = sqliteProvider.systems().firstOrNull()
        val sqlSummaries = if (systemId != null) {
            summaries.filter {
                it.identity.providerId.value == SQLiteStorageProvider.providerIdValue &&
                    it.identity.systemId == systemId
            }
        } else {
            emptyList()
        }
        val tablesByRaw = sqlSummaries
            .filter { it.identity.kind == ResourceKind.TABLE && it.identity.nativeId.startsWith("t:") }
            .mapNotNull { s -> SQLiteResourceIds.decodeTableName(s.identity.nativeId)?.let { it to s } }
            .toMap()
        val indexesByName = sqlSummaries
            .filter { it.identity.kind == ResourceKind.INDEX && it.identity.nativeId.startsWith("i:") }
            .mapNotNull { s -> SQLiteResourceIds.decodeIndexName(s.identity.nativeId)?.let { it to s } }
            .toMap()

        // ---- physical files: main db + sidecars (fs provider owns them) ----
        val dbFileSummary = if (fileBased) summaries.firstOrNull {
            it.identity.providerId.value == PathIdentity.FILESYSTEM_PROVIDER_ID &&
                it.identity.kind == ResourceKind.FILE &&
                it.identity.nativeId == dbFileName
        } else {
            null
        }
        val dbFileBytes = dbFileSummary?.let {
            it.footPrintMetrics[MetricType.FILE_LOGICAL_SIZE]
                ?: it.footPrintMetrics[MetricType.STORED_CONTENT_SIZE]
        }
        val sidecars = summaries
            .filter {
                it.identity.providerId.value == PathIdentity.FILESYSTEM_PROVIDER_ID &&
                    it.identity.kind == ResourceKind.FILE &&
                    it.identity.nativeId != dbFileName
            }
            .mapNotNull { s ->
                val role = sidecarRole(s.identity.nativeId, dbFileName) ?: return@mapNotNull null
                val bytes = s.footPrintMetrics[MetricType.FILE_LOGICAL_SIZE]
                    ?: s.footPrintMetrics[MetricType.STORED_CONTENT_SIZE]
                    ?: return@mapNotNull null
                SidecarStorage(fileName = s.identity.nativeId, role = role, bytes = bytes)
            }

        // ---- entity views: bundle entities first, then live-only extras ----
        val liveUserTables = probeTables.filterNot { it == RoomEntitySpec.FRAMEWORK_TABLE_MASTER || it == RoomEntitySpec.FRAMEWORK_TABLE_METADATA }
        val liveEntityNames = if (liveUserTables.isNotEmpty()) liveUserTables else tablesByRaw.keys.toList().sorted()
        val bundledNames = schemaBundle?.entities?.filterNot { it.isFramework }?.map { it.tableName }?.toSet() ?: emptySet()
        val liveExtras = if (schemaBundle == null) emptyList() else liveEntityNames.filterNot { it in bundledNames }

        val descriptors: Map<String, EntityDescriptor> = RoomDescriptors
            .entityDescriptors(schemaBundle, sqliteProvider, if (schemaBundle == null) liveEntityNames else liveExtras)
            .associateBy { it.entityTypeId }

        val entities = ArrayList<RoomEntityStorage>()
        val specViews: List<Pair<RoomEntitySpec, RoomEntitySource>> = if (schemaBundle == null) {
            liveEntityNames.map { name ->
                RoomEntitySpec(
                    tableName = name,
                    entityClassSimpleName = null,
                    withoutRowid = false,
                    primaryKeyColumns = emptyList(),
                    columnNames = emptyList(),
                    indexNames = emptyList(),
                    createSql = null,
                ) to RoomEntitySource.LIVE_CATALOG
            }
        } else {
            schemaBundle.entities.filterNot { it.isFramework }.map { it to RoomEntitySource.SCHEMA_BUNDLE } +
                liveExtras.map { extras -> syntheticSpec(extras) to RoomEntitySource.LIVE_CATALOG }
        }

        for ((entitySpec, source) in specViews) {
            val tableSummary = tablesByRaw[entitySpec.tableName]
            val tableBytes = tableSummary?.footPrintMetrics?.get(MetricType.DATABASE_TABLE_STORAGE)
            val indexesBytes = if (entitySpec.indexNames.isEmpty()) {
                ByteCount.ZERO
            } else {
                ByteCount.sum(
                    entitySpec.indexNames.mapNotNull { name ->
                        indexesByName[name]?.footPrintMetrics?.get(MetricType.DATABASE_INDEX_STORAGE)
                    },
                )
            }
            val recordCount = if (fileBased) runCatching { probe.rowCount(entitySpec.tableName) }.getOrNull() else null
            val typeId = RoomDescriptors.entityTypeIdFor(entitySpec)
            val scanned = if (config.includeRowScan) scanRows(descriptors[typeId], context) else null

            entities += RoomEntityStorage(
                entityTypeId = typeId,
                tableName = entitySpec.tableName,
                displayName = RoomDescriptors.displayNameFor(entitySpec),
                source = source,
                tableBytes = tableBytes,
                indexesBytes = indexesBytes,
                recordCount = recordCount,
                rowsBytesSum = scanned?.first,
                maxRowBytes = scanned?.second,
            )
        }
        entities.sortWith(
            compareByDescending<RoomEntityStorage> { it.tableBytes?.value ?: Long.MIN_VALUE }
                .thenBy { it.entityTypeId },
        )

        // ---- totals + drift ----
        val container = containerSqlMetrics(global)
        val totals = RoomTotals(
            uniqueFootprint = global.totals.uniqueFootprint,
            dbFileBytes = dbFileBytes,
            pageCapacity = container.first,
            freelist = container.second,
            sidecarsBytes = ByteCount.sum(sidecars.map { it.bytes }),
        )
        val drift = buildDrift(liveVersion, liveIdentity, liveUserTables)

        return RoomAnalysis(
            dbPath = spec.path,
            liveSchemaVersion = liveVersion,
            bundleSchemaVersion = schemaBundle?.version,
            identityHashLive = liveIdentity,
            identityHashBundle = schemaBundle?.identityHash,
            drift = drift,
            entities = entities,
            sidecars = sidecars,
            totals = totals,
            globalReport = if (config.includeGlobalReport) global else null,
            digest = fingerprint ?: contentDigest(liveVersion, liveIdentity, totals, drift.size, entities),
            generatedAt = Instant.now(),
            durationMs = Duration.ofNanos(System.nanoTime() - startNanos).toMillis(),
            probeFingerprint = fingerprint,
        )
    }

    /** Stops the probe connection and closes every internal provider. */
    override fun close() {
        probe.close()
        globalAnalyzer.close()
    }

    // ------------------------------------------------------------------ internals

    /**
     * Page capacity + freelist of the database container. When the filesystem
     * provider owns the deduplicated file view, the container's engine-only
     * metrics surface in [GlobalReport.overlapMeasurements] (never merged
     * into global totals); recover them from there — nothing double counts,
     * the engine-only metrics could not come from any other provider.
     */
    private fun containerSqlMetrics(global: GlobalReport): Pair<ByteCount?, ByteCount?> {
        var page = global.totals.byMetric[MetricType.DATABASE_PAGE_CAPACITY]
        var freelist = global.totals.byMetric[MetricType.DATABASE_FREELIST_SIZE]
        if (page != null && freelist != null) return page to freelist
        for (overlap in global.overlapMeasurements) {
            if (overlap.identity.providerId.value != SQLiteStorageProvider.providerIdValue) continue
            if (overlap.identity.kind != ResourceKind.DATABASE) continue
            if (page == null) page = overlap.valueOf(MetricType.DATABASE_PAGE_CAPACITY)?.let { ByteCount(it.value) }
            if (freelist == null) freelist = overlap.valueOf(MetricType.DATABASE_FREELIST_SIZE)?.let { ByteCount(it.value) }
        }
        return page to freelist
    }

    private fun syntheticSpec(tableName: String): RoomEntitySpec = RoomEntitySpec(
        tableName = tableName,
        entityClassSimpleName = null,
        withoutRowid = false,
        primaryKeyColumns = emptyList(),
        columnNames = emptyList(),
        indexNames = emptyList(),
        createSql = null,
    )

    private fun isForeignFsResource(identity: ResourceIdentity): Boolean {
        if (identity.providerId.value != PathIdentity.FILESYSTEM_PROVIDER_ID) return false
        if (identity.kind == ResourceKind.DIRECTORY) return true
        if (identity.kind != ResourceKind.FILE) return false
        val name = identity.nativeId
        return !(name == dbFileName || sidecarRole(name, dbFileName) != null)
    }

    /**
     * Opt-in per-record scan; returns `(sum, max)` or null. Honors
     * cancellation (a cancellation is never swallowed into "unmeasured").
     */
    private fun scanRows(
        descriptor: EntityDescriptor?,
        context: com.mohamed.storageanalyzer.core.config.DefaultAnalysisContext,
    ): Pair<ByteCount, ByteCount>? {
        if (descriptor == null) return null
        val resolver = sqliteProvider.entityResolver() ?: return null
        var total: Long = 0
        var max: Long = -1
        try {
            resolver.resourcesFor(descriptor, context).forEach { rowRes ->
                val m = sqliteProvider.measure(rowRes.identity, setOf(MetricType.DATABASE_RECORD_SIZE), context)
                val v = m.valueOf(MetricType.DATABASE_RECORD_SIZE)
                if (v != null) {
                    total += v.value
                    if (v.value > max) max = v.value
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return null // honest "unmeasured rows" for this entity
        }
        return if (max >= 0) ByteCount(total) to ByteCount(max) else null
    }

    private fun buildDrift(
        liveVersion: Int?,
        liveIdentity: String?,
        liveUserTables: List<String>,
    ): List<StorageDiagnostic> {
        val bundle = schemaBundle ?: return emptyList()
        val out = ArrayList<StorageDiagnostic>()

        if (liveIdentity == null) {
            out += StorageDiagnostic(
                code = DRIFT_MASTER_MISSING,
                severity = DiagnosticSeverity.INFO,
                message = "room_master_table has no readable identity_hash; identity comparison skipped",
            )
        } else if (bundle.identityHash != null && liveIdentity != bundle.identityHash) {
            out += StorageDiagnostic(
                code = DRIFT_IDENTITY_HASH,
                severity = DiagnosticSeverity.ERROR,
                message = "room_master_table identity_hash '$liveIdentity' does not match bundle '${bundle.identityHash}'",
                suggestedAction = "re-export the schema bundle (exportSchema) or regenerate it for this database version",
            )
        }

        if (bundle.identityHash != null && liveIdentity == null) {
            out += StorageDiagnostic(
                code = DRIFT_SCHEMA_VERSION,
                severity = DiagnosticSeverity.WARNING,
                message = "bundle schema version ${bundle.version} vs live user_version ${liveVersion ?: "?"}",
            )
        } else if (liveVersion != null && liveVersion != bundle.version) {
            out += StorageDiagnostic(
                code = DRIFT_SCHEMA_VERSION,
                severity = DiagnosticSeverity.WARNING,
                message = "bundle schema version ${bundle.version} vs live user_version $liveVersion",
                suggestedAction = "export the matching Room schema for this database version",
            )
        }

        if (liveUserTables.isNotEmpty()) {
            val bundled = bundle.entities.filterNot { it.isFramework }.map { it.tableName }.toSet()
            for (extra in liveUserTables.filterNot { it in bundled }.sorted()) {
                out += StorageDiagnostic(
                    code = DRIFT_EXTRA_TABLE,
                    severity = DiagnosticSeverity.WARNING,
                    message = "live table '$extra' is not in the schema bundle",
                    suggestedAction = "add the entity to the bundle source or refresh the export",
                )
            }
            for (missing in bundled.filterNot { it in liveUserTables }.sorted()) {
                out += StorageDiagnostic(
                    code = DRIFT_MISSING_TABLE,
                    severity = DiagnosticSeverity.ERROR,
                    message = "bundled entity '$missing' has no table in the live database",
                    suggestedAction = "apply pending migrations or refresh the bundle for this database",
                )
            }
        }
        // Stable, human-readable order.
        return out.sortedWith(compareBy({ it.code }, { it.message }))
    }

    private fun contentDigest(
        liveVersion: Int?,
        liveIdentity: String?,
        totals: RoomTotals,
        driftCount: Int,
        entities: List<RoomEntityStorage>,
    ): String = buildString {
        append("content=v1")
        append("|uv=").append(liveVersion ?: -1)
        append("|ih=").append(liveIdentity.orEmpty())
        append("|u=").append(totals.uniqueFootprint.value)
        append("|pc=").append(totals.pageCapacity?.value ?: -1L)
        append("|fx=").append(totals.freelist?.value ?: -1L)
        append("|sc=").append(totals.sidecarsBytes.value)
        append("|d=").append(driftCount)
        append("|e=")
        entities
            .sortedBy { it.entityTypeId }
            .joinTo(this, ";") { e ->
                e.entityTypeId + "," + (e.tableBytes?.value ?: -1L) + "," +
                    (e.indexesBytes?.value ?: -1L) + "," + (e.recordCount ?: -1L) + "," +
                    (e.rowsBytesSum?.value ?: -1L)
            }
    }

    public companion object {
        /** Drift diagnostic codes (stable contract). */
        public const val DRIFT_IDENTITY_HASH: String = "room.drift.identity_hash"
        public const val DRIFT_MASTER_MISSING: String = "room.drift.master_missing"
        public const val DRIFT_EXTRA_TABLE: String = "room.drift.extra_table"
        public const val DRIFT_MISSING_TABLE: String = "room.drift.missing_table"
        public const val DRIFT_SCHEMA_VERSION: String = "room.drift.schema_version"

        /** Classifies [name] inside the database's directory; null = unrelated file. */
        public fun sidecarRole(name: String, dbBaseName: String): SidecarRole? = when {
            name.startsWith("$dbBaseName-wal") -> SidecarRole.WAL
            name.startsWith("$dbBaseName-shm") -> SidecarRole.SHM
            name.startsWith("$dbBaseName-journal") -> SidecarRole.JOURNAL
            else -> null
        }
    }
}
