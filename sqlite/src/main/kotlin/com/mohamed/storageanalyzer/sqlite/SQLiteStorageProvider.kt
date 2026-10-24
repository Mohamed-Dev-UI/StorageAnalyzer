package com.mohamed.storageanalyzer.sqlite

import com.mohamed.storageanalyzer.core.capability.Capabilities
import com.mohamed.storageanalyzer.core.config.AnalysisContext
import com.mohamed.storageanalyzer.core.entity.spi.AggregatedField
import com.mohamed.storageanalyzer.core.entity.spi.CollectionKey
import com.mohamed.storageanalyzer.core.entity.spi.EntityResourceResolver
import com.mohamed.storageanalyzer.core.entity.spi.RecordAnalyticsEngine
import com.mohamed.storageanalyzer.core.identity.IdentityStability
import com.mohamed.storageanalyzer.core.identity.ProviderId
import com.mohamed.storageanalyzer.core.identity.ResourceIdentity
import com.mohamed.storageanalyzer.core.identity.ResourceKind
import com.mohamed.storageanalyzer.core.identity.StorageSystemId
import com.mohamed.storageanalyzer.core.identity.PathIdentity
import com.mohamed.storageanalyzer.core.measurement.ByteCount
import com.mohamed.storageanalyzer.core.measurement.MeasurementAccuracy
import com.mohamed.storageanalyzer.core.measurement.MeasurementMethod
import com.mohamed.storageanalyzer.core.measurement.MeasurementState
import com.mohamed.storageanalyzer.core.measurement.MeasurementProvenance
import com.mohamed.storageanalyzer.core.measurement.MetricType
import com.mohamed.storageanalyzer.core.measurement.SingleMeasurement
import com.mohamed.storageanalyzer.core.provider.StorageProvider
import com.mohamed.storageanalyzer.core.provider.StorageSystemRef
import com.mohamed.storageanalyzer.core.resource.OwnerRef
import com.mohamed.storageanalyzer.core.result.DiagnosticSeverity
import com.mohamed.storageanalyzer.core.result.RecordBreakdown
import com.mohamed.storageanalyzer.core.result.StorageDiagnostic
import com.mohamed.storageanalyzer.core.result.StorageMeasurement
import com.mohamed.storageanalyzer.core.capability.StorageCapability
import com.mohamed.storageanalyzer.core.resource.ReferenceLink
import com.mohamed.storageanalyzer.core.resource.RelationType
import com.mohamed.storageanalyzer.core.resource.StorageCategory
import com.mohamed.storageanalyzer.core.resource.StorageResource
import com.mohamed.storageanalyzer.databaseapi.DatabaseFileInfo
import com.mohamed.storageanalyzer.databaseapi.DatabaseFileRole
import com.mohamed.storageanalyzer.databaseapi.DatabasePageStats
import com.mohamed.storageanalyzer.databaseapi.IndexDescriptor
import com.mohamed.storageanalyzer.databaseapi.TableDescriptor
import java.io.File
import java.time.Instant

/** Provenance limitation tokens (documented in docs/MEASUREMENT_ACCURACY.md). */
private const val LIMIT_WAL_SIDECARS = "sqlite.excludes.wal-sidecars"
private const val LIMIT_ESTIMATE_OVERHEAD = "sqlite.estimate.excludes-overhead"

/**
 * Read-only storage analytics provider for SQLite databases.
 *
 * Systems are the databases registered via [SQLiteDatabaseSpec]s. Every
 * analysis run opens  its connections lazily, in read-only mode, and shares
 * one catalog/dbstat snapshot per run (cache keyed by the run's context).
 *
 * Discovery emits:
 * - one [StorageResource] of kind [ResourceKind.DATABASE] per system;
 * - [ResourceKind.TABLE] and [ResourceKind.INDEX] resources marked as
 *   `decomposes = db` (their bytes are inside the db's files — they never
 *   inflate global totals, only metric views);
 * - sidecar files (`-wal`, `-shm`, `-journal`) as [ResourceKind.FILE]
 *   resources carrying `alsoDiscoverableBy` aliases in the filesystem
 *   identity scheme ([PathIdentity]).
 *
 * Measurement is per resource identity; unavailable metrics carry reasons.
 */
class SQLiteStorageProvider(
    specs: List<SQLiteDatabaseSpec> = emptyList(),
) : StorageProvider {

    companion object {
        const val providerIdValue = "sqlite"

        /** Descriptor hint carrying the backing table name. */
        const val HINT_TABLE = "sqlite.table"
    }

    override val providerId: ProviderId = ProviderId(providerIdValue)

    // ------------------------------------------------------------ systems

    private class SystemState(val spec: SQLiteDatabaseSpec, val systemId: StorageSystemId) {
        var openError: String? = null
        var connection: SQLiteConnection? = null
        var capabilities: SQLiteCapabilities? = null
        var introspection: SQLiteIntrospection? = null
        var schemaCache: Pair<AnalysisContext, SchemaSnapshot>? = null
    }

    private class SchemaSnapshot(
        val tables: List<TableDescriptor>,
        val indexes: List<IndexDescriptor>,
        val rowidAliases: Map<String, String?>,
        val dbstat: Map<String, Long>?,
        val pageStats: DatabasePageStats?,
        val files: List<DatabaseFileInfo>,
        val backend: String,
        /** False when the base queries failed (e.g. corrupt file, locked db). */
        val catalogReadable: Boolean = true,
    ) {
        /** Primary-key columns of [tableName] ordered by declared pk ordinal. */
        fun pkColumnsOf(
            tableName: String,
            cols: List<com.mohamed.storageanalyzer.databaseapi.ColumnInfo>,
        ): List<com.mohamed.storageanalyzer.databaseapi.ColumnInfo> =
            cols.filter { it.isPrimaryKeyPart }.sortedBy { it.primaryKeyOrdinal }
    }

    private val systemList = ArrayList<SystemState>()
    private val systemsById = LinkedHashMap<StorageSystemId, SystemState>()

    init {
        specs.forEach { addSystem(it) }
    }

    /** Registers one database for analysis; duplicate paths collapse to one system. */
    @Synchronized
    fun addSystem(spec: SQLiteDatabaseSpec): StorageSystemId {
        val ordinal = if (spec.path == ":memory:") systemList.count { it.spec.path == ":memory:" } else 0
        val id = SQLiteResourceIds.systemIdOf(spec, ordinal)
        systemsById[id]?.let { return it.systemId }
        val state = SystemState(spec, id)
        systemList.add(state)
        systemsById[id] = state
        return id
    }

    /** All registered database systems. */
    fun systems(): List<StorageSystemId> = systemList.map { it.systemId }

    /** The backing spec for diagnostics/labels. */
    fun specOf(systemId: StorageSystemId): SQLiteDatabaseSpec? = systemsById[systemId]?.spec

    private fun stateOf(systemId: StorageSystemId): SystemState? = systemsById[systemId]

    private fun ensureOpen(s: SystemState): Boolean {
        s.connection?.let { return true }
        s.openError?.let { return false }
        return try {
            val conn = SQLiteConnection.open(s.spec)
            val caps = SQLiteCapabilities.probe(conn)
            s.connection = conn
            s.capabilities = caps
            s.introspection = SQLiteIntrospection(s.spec, conn, caps)
            true
        } catch (t: Throwable) {
            s.openError = t.message ?: t.javaClass.simpleName
            false
        }
    }

    private fun schemaOf(s: SystemState, ctx: AnalysisContext): SchemaSnapshot? {
        s.schemaCache?.let { (ownerCtx, snap) -> if (ownerCtx === ctx) return snap }
        if (!ensureOpen(s)) return null
        val intro = s.introspection ?: return null
        val conn = s.connection ?: return null
        val tables = runCatching { intro.tables().toList() }.getOrDefault(emptyList())
        val indexes = runCatching { intro.indexes().toList() }.getOrDefault(emptyList())
        val aliases = runCatching {
            tables.associate { it.tableName to intro.rowidAliasOf(it.tableName) }
        }.getOrDefault(emptyMap())
        val backend = runCatching { backendOf(conn) }.getOrDefault("schema-unreadable")
        val dbstat = intro.dbstatSums()
        val stats = runCatching { intro.pageStats() }.getOrNull()
        val files = runCatching { intro.files() }.getOrDefault(emptyList())
        val catalogReadable = tables.isNotEmpty() || stats != null || dbstat != null
        val snap = SchemaSnapshot(
            tables = tables,
            indexes = indexes,
            rowidAliases = aliases,
            dbstat = dbstat,
            pageStats = stats,
            files = files,
            backend = backend,
            catalogReadable = catalogReadable,
        )
        // Only cache when not cancelled mid-load to avoid pinning partials.
        if (!ctx.isCancelled()) s.schemaCache = ctx to snap
        return snap
    }

    private fun backendOf(conn: SQLiteConnection): String =
        "sqlite-jdbc / SQLite " + (conn.scalarString("SELECT sqlite_version()") ?: "unknown")

    // --------------------------------------------------- provider identity

    override fun describe(): String =
        "SQLite storage provider (read-only; per-system backend versions are " +
            "reported in each measurement's provenance)"

    override fun capabilities(): Capabilities = Capabilities(
        supported = setOf(
            StorageCapability.DB_FILES,
            StorageCapability.DB_PAGE_ACCOUNTING,
            StorageCapability.DB_RECORD_SIZE,
            StorageCapability.DB_FIELD_BREAKDOWN,
            StorageCapability.DB_WAL_FILES,
            StorageCapability.DB_SCHEMA_METADATA,
            StorageCapability.DB_CONSISTENCY_SNAPSHOT,
            StorageCapability.ENTITY_LEVEL_ANALYTICS,
            StorageCapability.RELATION_TRAVERSAL,
        ),
        unsupportedReasons = mapOf(
            StorageCapability.DB_DBSTAT to "dbstat availability is a build property of each SQLite binary; probe capabilitiesFor(systemId)",
        ),
    )

    override fun capabilitiesFor(systemId: StorageSystemId): Capabilities {
        val s = stateOf(systemId) ?: return Capabilities(emptySet(), emptyMap())
        if (!ensureOpen(s)) {
            return Capabilities(emptySet(), mapOf(StorageCapability.DB_FILES to ("database cannot be opened: " + s.openError)))
        }
        val caps = s.capabilities ?: return Capabilities(emptySet(), emptyMap())
        val supported = buildSet {
            add(StorageCapability.DB_FILES)
            add(StorageCapability.DB_PAGE_ACCOUNTING)
            add(StorageCapability.DB_RECORD_SIZE)
            add(StorageCapability.DB_FIELD_BREAKDOWN)
            add(StorageCapability.DB_WAL_FILES)
            add(StorageCapability.DB_SCHEMA_METADATA)
            add(StorageCapability.DB_CONSISTENCY_SNAPSHOT)
            add(StorageCapability.ENTITY_LEVEL_ANALYTICS)
            add(StorageCapability.RELATION_TRAVERSAL)
            if (caps.dbstatAvailable) {
                add(StorageCapability.DB_DBSTAT)
                add(StorageCapability.DB_TABLE_STORAGE)
                add(StorageCapability.DB_INDEX_STORAGE)
            }
            if (caps.integrityCheckAvailable) add(StorageCapability.DB_CONSISTENCY_SNAPSHOT)
        }
        val unsupported = buildMap {
            if (!caps.dbstatAvailable) {
                put(StorageCapability.DB_TABLE_STORAGE, "dbstat vtab not available in this SQLite build; table storage is estimated by record sums")
                put(StorageCapability.DB_INDEX_STORAGE, "dbstat vtab not available in this SQLite build; index storage unmeasurable")
                put(StorageCapability.DB_DBSTAT, "dbstat vtab not available in this SQLite build")
            }
        }
        return Capabilities(supported, unsupported)
    }

    override fun discoverSystems(): List<StorageSystemRef> = systemList.map { s ->
        StorageSystemRef(
            systemId = s.systemId,
            label = labelOf(s),
            kind = ResourceKind.DATABASE,
            capabilities = capabilitiesFor(s.systemId),
        )
    }

    private fun labelOf(s: SystemState): String = s.spec.label ?: when {
        s.spec.path == ":memory:" -> "in-memory database"
        else -> File(s.spec.path).name
    }

    // ------------------------------------------------------------ discovery

    override fun discoverResources(
        systemId: StorageSystemId?,
        ctx: AnalysisContext,
    ): Sequence<StorageResource> = sequence {
        val selected = systemId?.let { listOfNotNull(stateOf(it)) } ?: systemList
        for (s in selected) {
            ctx.ensureActive()
            yield(databaseResource(s))
            val snap = schemaOf(s, ctx)
            if (snap == null) {
                ctx.diagnostic(
                    StorageDiagnostic(
                        code = "sqlite.open_failed",
                        severity = DiagnosticSeverity.ERROR,
                        message = "database '${s.spec.path}' could not be opened: ${s.openError}",
                        identity = databaseIdentityOf(s),
                        providerId = providerId,
                        suggestedAction = "check file existence, permissions and that the path is a SQLite database",
                    ),
                )
            } else {
                val stability = SQLiteResourceIds.stabilityOf(s.spec)
                val ns = SQLiteResourceIds.namespaceOf(s.spec)
                for (t in snap.tables) {
                    yield(
                        StorageResource(
                            identity = SQLiteResourceIds.table(s.systemId, ns, t.tableName, stability),
                            providerId = providerId,
                            systemId = s.systemId,
                            label = t.tableName,
                            kind = ResourceKind.TABLE,
                            category = StorageCategory.DATABASE,
                            decomposes = databaseIdentityOf(s),
                        ),
                    )
                }
                for (ix in snap.indexes) {
                    yield(
                        StorageResource(
                            identity = SQLiteResourceIds.index(s.systemId, ns, ix.indexName, stability),
                            providerId = providerId,
                            systemId = s.systemId,
                            label = ix.indexName,
                            kind = ResourceKind.INDEX,
                            category = StorageCategory.DATABASE,
                            decomposes = databaseIdentityOf(s),
                        ),
                    )
                }
            }
            for (sidecar in sidecarResourcesOf(s)) yield(sidecar)
        }
    }

    private fun databaseIdentityOf(s: SystemState): ResourceIdentity =
        SQLiteResourceIds.database(s.systemId, SQLiteResourceIds.namespaceOf(s.spec), SQLiteResourceIds.stabilityOf(s.spec))

    private fun databaseResource(s: SystemState): StorageResource {
        val identity = databaseIdentityOf(s)
        val aliases = if (s.spec.path == ":memory:" || s.spec.path.startsWith("jdbc:") || s.spec.path.startsWith("file:")) {
            emptySet()
        } else {
            val f = File(s.spec.path)
            if (f.isFile) setOf(PathIdentity.forPath(s.spec.path)) else emptySet()
        }
        return StorageResource(
            identity = identity,
            providerId = providerId,
            systemId = s.systemId,
            label = labelOf(s),
            kind = ResourceKind.DATABASE,
            category = StorageCategory.DATABASE,
            alsoDiscoverableBy = aliases,
        )
    }

    /** Sidecar files of one database (WAL/SHM/journal) discovered by convention. */
    private fun sidecarResourcesOf(s: SystemState): List<StorageResource> {
        if (s.spec.path == ":memory:" || s.spec.path.startsWith("jdbc:") || s.spec.path.startsWith("file:")) return emptyList()
        val main = File(s.spec.path)
        if (!main.isFile) return emptyList()
        val out = ArrayList<StorageResource>(3)
        for ((suffix, role) in listOf(
            "-wal" to DatabaseFileRole.WAL,
            "-shm" to DatabaseFileRole.SHM,
            "-journal" to DatabaseFileRole.JOURNAL,
        )) {
            val file = File(main.path + suffix)
            if (!file.isFile) continue
            val nativeId = main.name + suffix
            val identity = ResourceIdentity(
                providerId = providerId,
                systemId = s.systemId,
                kind = ResourceKind.FILE,
                nativeId = nativeId,
                namespace = "sidecar-" + role.name.lowercase(),
                stability = IdentityStability.EPHEMERAL, // sidecars can be reclaimed/moved by checkpoints
            )
            out.add(
                StorageResource(
                    identity = identity,
                    providerId = providerId,
                    systemId = s.systemId,
                    label = nativeId,
                    kind = ResourceKind.FILE,
                    category = StorageCategory.DATABASE,
                    alsoDiscoverableBy = setOf(PathIdentity.forChild(main.parentFile ?: File("."), nativeId)),
                ),
            )
        }
        return out
    }

    // ------------------------------------------------------------ measurement

    override fun defaultMetricTypes(): Set<MetricType> = defaultMetricTypesSet

    private val defaultMetricTypesSet: Set<MetricType> = setOf(
        MetricType.STORED_CONTENT_SIZE,
        MetricType.FILE_LOGICAL_SIZE,
        MetricType.DATABASE_PAGE_CAPACITY,
        MetricType.DATABASE_FREELIST_SIZE,
        MetricType.DATABASE_METADATA_STORAGE,
        MetricType.DATABASE_TABLE_STORAGE,
        MetricType.DATABASE_INDEX_STORAGE,
        MetricType.DATABASE_RECORD_SIZE,
    )

    private fun prov(
        now: Instant,
        backend: String,
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
        backend = backend,
        limitations = limitations,
        warnings = warnings,
        note = note,
    )

    private fun absent(metric: MetricType, now: Instant, backend: String, reason: String): SingleMeasurement =
        SingleMeasurement(
            metric = metric,
            value = null,
            state = MeasurementState.UNAVAILABLE,
            provenance = prov(now, backend, MeasurementMethod.PROVIDER_METADATA, MeasurementAccuracy.UNAVAILABLE, note = null),
            reason = reason,
        )

    private fun notApplicable(metric: MetricType, now: Instant, backend: String, reason: String): SingleMeasurement =
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
        val s = stateOf(resource.systemId)
            ?: return unknownSystemMeasurement(resource, requested, now)
        val snap = schemaOf(s, ctx)
        val backend = snap?.backend ?: ("unopened (" + (s.openError ?: "no snapshot") + ")")

        var diagnostics = ArrayList<StorageDiagnostic>()
        val recordResult = if (resource.kind == ResourceKind.RECORD) {
            recordMeasurements(s, resource, requested, snap, now, backend, diagnostics, ctx)
        } else {
            null
        }
        val measurements: Map<MetricType, SingleMeasurement> = when (resource.kind) {
            ResourceKind.DATABASE -> dbMeasurements(s, requested, snap, now, backend, diagnostics)
            ResourceKind.TABLE -> tableMeasurements(s, resource, requested, snap, now, backend, diagnostics, ctx)
            ResourceKind.INDEX -> indexMeasurements(s, resource, requested, snap, now, backend, diagnostics)
            ResourceKind.RECORD -> recordResult!!.first
            ResourceKind.FILE -> sidecarMeasurements(s, resource, requested, now, backend)
            else -> requested.associateWith { m -> notApplicable(m, now, backend, "kind ${resource.kind} is not measured by the SQLite provider") }
        }
        return StorageMeasurement(
            identity = resource,
            kind = resource.kind,
            category = categoryOf(resource.kind),
            label = labelFor(resource, s),
            measurements = measurements,
            recordBreakdown = recordResult?.second,
            diagnostics = diagnostics,
            completeness = when {
                snap == null -> com.mohamed.storageanalyzer.core.result.Completeness.UNKNOWN
                !snap.catalogReadable -> com.mohamed.storageanalyzer.core.result.Completeness.UNKNOWN
                else -> com.mohamed.storageanalyzer.core.result.Completeness.COMPLETE
            },
            measuredAt = now,
        )
    }

    private fun categoryOf(kind: ResourceKind): StorageCategory = StorageCategory.DATABASE

    private fun labelFor(resource: ResourceIdentity, s: SystemState): String? = when (resource.kind) {
        ResourceKind.DATABASE -> SQLiteResourceIds.decodeComponent(SQLiteResourceIds.namespaceOf(s.spec)).takeIf { it != SQLiteResourceIds.NAMESPACE_MEMORY } ?: "in-memory database"
        else -> null
    }

    private fun unknownSystemMeasurement(
        resource: ResourceIdentity,
        requested: Set<MetricType>,
        now: Instant,
    ): StorageMeasurement = StorageMeasurement(
        identity = resource,
        kind = resource.kind,
        category = categoryOf(resource.kind),
        measurements = requested.associateWith { m ->
            absent(m, now, "not registered", "system '${resource.systemId.value}' is not registered with the SQLite provider")
        },
        completeness = com.mohamed.storageanalyzer.core.result.Completeness.UNKNOWN,
        measuredAt = now,
    )

    // ---- database -------------------------------------------------------

    /** Sum of internal `sqlite_*` b-trees (catalog, sequence, stat tables). */
    private fun metadataBytesOf(dbstat: Map<String, Long>): Long = dbstat.entries
        .filter { (name, _) ->
            name == "sqlite_schema" || name == "sqlite_master" ||
                (name.startsWith("sqlite_") && !name.startsWith("sqlite_autoindex"))
        }
        .sumOf { it.value }

    private fun dbMeasurements(
        s: SystemState,
        requested: Set<MetricType>,
        snap: SchemaSnapshot?,
        now: Instant,
        backend: String,
        diagnostics: MutableList<StorageDiagnostic>,
    ): Map<MetricType, SingleMeasurement> {
        val openReason = if (snap == null) "database not openable: ${s.openError ?: "no snapshot"}" else null
        val stats = snap?.pageStats
        val dbstat = snap?.dbstat
        val out = LinkedHashMap<MetricType, SingleMeasurement>()
        for (metric in requested) {
            out[metric] = when (metric) {
                MetricType.STORED_CONTENT_SIZE, MetricType.FILE_LOGICAL_SIZE ->
                    if (openReason != null) absent(metric, now, backend, openReason) else mainContentSize(s, snap, metric, now, backend)
                MetricType.DATABASE_PAGE_CAPACITY -> stats
                    ?.let { SingleMeasurement.measured(metric, it.pageCapacityBytes.value, prov(now, backend, MeasurementMethod.PRAGMA_PAGE_COUNT, MeasurementAccuracy.EXACT)) }
                    ?: absent(metric, now, backend, openReason ?: "page PRAGMAs unavailable on this backend")
                MetricType.DATABASE_FREELIST_SIZE -> stats
                    ?.let { SingleMeasurement.measured(metric, it.freelistBytes.value, prov(now, backend, MeasurementMethod.PRAGMA_FREELIST_COUNT, MeasurementAccuracy.EXACT)) }
                    ?: absent(metric, now, backend, openReason ?: "page PRAGMAs unavailable on this backend")
                MetricType.DATABASE_METADATA_STORAGE -> {
                    val meta = dbstat?.let { metadataBytesOf(it) }
                    if (meta != null) SingleMeasurement.measured(metric, meta, prov(now, backend, MeasurementMethod.DBSTAT_BTREE_SUM, MeasurementAccuracy.EXACT))
                    else absent(metric, now, backend, openReason ?: "dbstat vtab not available in this SQLite build")
                }
                MetricType.DATABASE_RECORD_SIZE ->
                    notApplicable(metric, now, backend, "a database's record sizes are reported per table/record; request them there")
                MetricType.TEMPORARY_STORAGE ->
                    notApplicable(metric, now, backend, "temporary schemas live in separate temp databases; attach them as systems")
                else -> notApplicable(metric, now, backend, "metric '${metric.id}' is not exposed by the SQLite provider for databases")
            }
        }
        // Page reconciliation diagnostic (only when numbers came from dbstat+PRAGMAs).
        if (dbstat != null && stats != null) {
            val dbstatTotal = dbstat.values.sumOf { it }
            val capacity = stats.pageSize.let { Math.multiplyExact(it, stats.pageCount) }
            val freelist = stats.freelistPages * stats.pageSize
            val accounted = Math.addExact(dbstatTotal, freelist)
            val residual = capacity - accounted
            val severity = if (residual < 0) DiagnosticSeverity.ERROR else DiagnosticSeverity.INFO
            diagnostics += StorageDiagnostic(
                code = "sqlite.page_reconciliation",
                severity = severity,
                message = "capacity=$capacity, dbstat-btrees=$dbstatTotal, freelist=$freelist, residual=$residual " +
                    "(residual covers pointer-map/lock-byte pages and reserved regions)",
                identity = databaseIdentityOf(s),
                providerId = providerId,
            )
        }
        return out
    }

    private fun mainContentSize(
        s: SystemState,
        snap: SchemaSnapshot?,
        metric: MetricType,
        now: Instant,
        backend: String,
    ): SingleMeasurement {
        val spec = s.spec
        if (spec.path == ":memory:") {
            val stats = snap?.pageStats
                ?: return absent(metric, now, backend, "in-memory database without page stats")
            return SingleMeasurement.measured(
                metric,
                stats.pageCapacityBytes.value,
                prov(now, backend, MeasurementMethod.PRAGMA_PAGE_COUNT, MeasurementAccuracy.EXACT, note = "in-memory database; current allocation = pages in use"),
            )
        }
        if (spec.path.startsWith("jdbc:") || spec.path.startsWith("file:")) {
            return absent(metric, now, backend, "URI-backed database; sizes depend on the system's real storage layer")
        }
        val f = File(spec.path)
        if (!f.isFile) return absent(metric, now, backend, "database file is missing (removed after discovery?)")
        val wal = snap?.files?.any { it.role == DatabaseFileRole.WAL } ?: File(spec.path + "-wal").isFile
        val limitations = if (wal) setOf(LIMIT_WAL_SIDECARS) else emptySet()
        return SingleMeasurement.measured(
            metric,
            f.length(),
            prov(now, backend, MeasurementMethod.FILE_LENGTH, MeasurementAccuracy.EXACT, limitations = limitations),
        )
    }

    // ---- table ----------------------------------------------------------

    private fun tableMeasurements(
        s: SystemState,
        resource: ResourceIdentity,
        requested: Set<MetricType>,
        snap: SchemaSnapshot?,
        now: Instant,
        backend: String,
        diagnostics: MutableList<StorageDiagnostic>,
        ctx: AnalysisContext,
    ): Map<MetricType, SingleMeasurement> {
        val openReason = if (snap == null) "database not openable: ${s.openError ?: "no snapshot"}" else null
        val table = snap?.tables?.let { tables ->
            SQLiteResourceIds.decodeTableName(resource.nativeId)?.let { name -> tables.firstOrNull { it.tableName == name } }
        }
        if (table == null && snap != null) {
            diagnostics += StorageDiagnostic(
                code = "sqlite.resource_disappeared",
                severity = DiagnosticSeverity.WARNING,
                message = "table ${resource.nativeId} disappeared between discovery and measurement",
                identity = resource,
                providerId = providerId,
            )
        }
        val missing = openReason ?: (if (table == null) "table not present in the catalog" else null)
        if (missing != null) {
            return requested.associateWith { m -> absent(m, now, backend, missing) }
        }
        val t = table!!
        val out = LinkedHashMap<MetricType, SingleMeasurement>()
        for (metric in requested) {
            out[metric] = when (metric) {
                MetricType.DATABASE_TABLE_STORAGE -> when {
                    t.kind == com.mohamed.storageanalyzer.databaseapi.TableKind.VIRTUAL_TABLE ->
                        notApplicable(metric, now, backend, "virtual table storage lives in shadow tables enumerated as regular tables")
                    else -> tableStorage(s, resource, t, snap, metric, now, backend)
                }
                MetricType.DATABASE_RECORD_SIZE -> when {
                    t.kind == com.mohamed.storageanalyzer.databaseapi.TableKind.VIRTUAL_TABLE ->
                        notApplicable(metric, now, backend, "virtual table rows are not analyzed; measure their shadow tables")
                    else -> tableRecordTotal(s, resource, t, snap, now, backend)
                }
                else -> notApplicable(metric, now, backend, "metric '${metric.id}' is not exposed by the SQLite provider for tables")
            }
        }
        return out
    }

    private fun tableStorage(
        s: SystemState,
        resource: ResourceIdentity,
        table: TableDescriptor,
        snap: SchemaSnapshot?,
        metric: MetricType,
        now: Instant,
        backend: String,
    ): SingleMeasurement {
        val dbstat = snap?.dbstat
        val exact = dbstat?.get(table.tableName)
        if (exact != null) {
            return SingleMeasurement.measured(metric, exact, prov(now, backend, MeasurementMethod.DBSTAT_BTREE_SUM, MeasurementAccuracy.EXACT))
        }
        // Estimate via the record-format aggregate (honest ESTIMATED result).
        val outcome = runCatching { aggregateRecordTotal(s, table, snap) }
        val total = outcome.getOrNull()
            ?: return absent(metric, now, backend, "table storage requires dbstat; record-format estimate also failed: ${outcome.exceptionOrNull()?.message ?: "no result"}")
        return SingleMeasurement(
            metric = metric,
            value = ByteCount.of(total),
            state = MeasurementState.MEASURED,
            provenance = prov(
                now,
                backend,
                MeasurementMethod.SQLITE_RECORD_FORMAT,
                MeasurementAccuracy.ESTIMATED,
                limitations = setOf(SQLiteRecordFormat.LIMIT_EXCLUDES_CELL_POINTER, SQLiteRecordFormat.LIMIT_EXCLUDES_PAGE_ALLOCATION, LIMIT_ESTIMATE_OVERHEAD),
                warnings = setOf("sqlite.estimate.no-dbstat"),
            ),
        )
    }

    private fun tableRecordTotal(
        s: SystemState,
        resource: ResourceIdentity,
        table: TableDescriptor,
        snap: SchemaSnapshot?,
        now: Instant,
        backend: String,
    ): SingleMeasurement {
        val outcome = runCatching { aggregateRecordTotal(s, table, snap) }
        val total = outcome.getOrNull()
            ?: return absent(
                MetricType.DATABASE_RECORD_SIZE,
                now,
                backend,
                "record-size scan failed on this backend: ${outcome.exceptionOrNull()?.message ?: "no result"}",
            )
        return SingleMeasurement(
            metric = MetricType.DATABASE_RECORD_SIZE,
            value = ByteCount.of(total),
            state = MeasurementState.MEASURED,
            provenance = SQLiteRecordFormat.provenance(now, backend),
        )
    }

    /** SUM of the convention row-size expression over the table. */
    private fun aggregateRecordTotal(
        s: SystemState,
        table: TableDescriptor,
        snap: SchemaSnapshot?,
    ): Long? {
        val conn = s.connection ?: return null
        val alias = snap?.rowidAliases?.get(table.tableName)
        val cols = table.columns.map { SQLiteRecordFormat.ColumnSpec(it.name, it.name == alias) }
        val expr = SQLiteRecordFormat.rowSizeExpression(table.tableName, cols, table.withoutRowid)
        val sql = "SELECT COALESCE(SUM(${expr}), 0) FROM ${quote(table.tableName)}"
        return conn.query(sql) { c -> if (c.next()) c.long(1) else null }
    }

    private fun quote(name: String): String = "\"" + name.replace("\"", "\"\"") + "\""

    // ---- index ----------------------------------------------------------

    private fun indexMeasurements(
        s: SystemState,
        resource: ResourceIdentity,
        requested: Set<MetricType>,
        snap: SchemaSnapshot?,
        now: Instant,
        backend: String,
        diagnostics: MutableList<StorageDiagnostic>,
    ): Map<MetricType, SingleMeasurement> {
        val openReason = if (snap == null) "database not openable: ${s.openError ?: "no snapshot"}" else null
        val indexName = SQLiteResourceIds.decodeIndexName(resource.nativeId)
        val present = snap?.indexes?.any { it.indexName == indexName } ?: false
        if (snap != null && !present) {
            diagnostics += StorageDiagnostic(
                code = "sqlite.resource_disappeared",
                severity = DiagnosticSeverity.WARNING,
                message = "index ${resource.nativeId} disappeared between discovery and measurement",
                identity = resource,
                providerId = providerId,
            )
        }
        val missing = openReason ?: (if (!present) "index not present in the catalog" else null)
        return requested.associateWith { metric ->
            when (metric) {
                MetricType.DATABASE_INDEX_STORAGE -> {
                    val bytes = snap?.dbstat?.get(indexName)
                    when {
                        missing != null -> absent(metric, now, backend, missing)
                        bytes != null -> SingleMeasurement.measured(metric, bytes, prov(now, backend, MeasurementMethod.DBSTAT_BTREE_SUM, MeasurementAccuracy.EXACT))
                        else -> absent(metric, now, backend, "index storage requires the dbstat vtab (unavailable in this SQLite build)")
                    }
                }
                else -> notApplicable(metric, now, backend, "metric '${metric.id}' is not exposed by the SQLite provider for indexes")
            }
        }
    }

    // ---- sidecar files --------------------------------------------------

    private fun sidecarMeasurements(
        s: SystemState,
        resource: ResourceIdentity,
        requested: Set<MetricType>,
        now: Instant,
        backend: String,
    ): Map<MetricType, SingleMeasurement> {
        val file = sidecarFileOf(s, resource)
        return requested.associateWith { metric ->
            when (metric) {
                MetricType.STORED_CONTENT_SIZE, MetricType.FILE_LOGICAL_SIZE -> {
                    val f = file
                    when {
                        !f?.isFile!! -> absent(metric, now, backend, "sidecar file is gone (checkpointed/closed since discovery)")
                        else -> SingleMeasurement.measured(
                            metric,
                            f.length(),
                            prov(
                                now,
                                backend,
                                MeasurementMethod.FILE_LENGTH,
                                MeasurementAccuracy.EXACT,
                                warnings = if (resource.namespace == "sidecar-wal") setOf("sqlite.wal-transient") else emptySet(),
                                note = if (resource.namespace == "sidecar-wal") "WAL size is transient; checkpoints reclaim it. No checkpoint was triggered." else null,
                            ),
                        )
                    }
                }
                else -> notApplicable(metric, now, backend, "metric '${metric.id}' is not exposed by the SQLite provider for sidecar files")
            }
        }
    }

    private fun sidecarFileOf(s: SystemState, resource: ResourceIdentity): File? {
        val main = File(s.spec.path)
        if (!main.isFile) return null
        return listOf("-wal", "-shm", "-journal")
            .map { File(main.path + it) }
            .firstOrNull { it.name == resource.nativeId }
    }

    // ---- records --------------------------------------------------------

    /**
     * Measures one record and its field breakdown. Returns the measurement
     * entries plus the encoded-size breakdown (also exposed through
     * [RecordAnalyticsEngine]).
     */
    private fun recordMeasurements(
        s: SystemState,
        resource: ResourceIdentity,
        requested: Set<MetricType>,
        snap: SchemaSnapshot?,
        now: Instant,
        backend: String,
        diagnostics: MutableList<StorageDiagnostic>,
        ctx: AnalysisContext,
    ): Pair<Map<MetricType, SingleMeasurement>, RecordBreakdown?> {
        val parsed = SQLiteResourceIds.parseRecordNative(resource.nativeId)
        if (parsed == null) {
            diagnostics += StorageDiagnostic(
                code = "sqlite.native_id_invalid",
                severity = DiagnosticSeverity.ERROR,
                message = "record native id '${resource.nativeId}' does not follow the documented shape",
                identity = resource,
                providerId = providerId,
            )
            return requested.associateWith { m ->
                absent(m, now, backend, "record native id '${resource.nativeId}' is not a parseable SQLite record identity")
            } to null
        }
        val (tableName, selector) = parsed
        val table = snap?.tables?.firstOrNull { it.tableName == tableName }
        if (snap == null || table == null) {
            val reason = if (snap == null) {
                "database not openable: ${s.openError ?: "no snapshot"}"
            } else {
                diagnostics += StorageDiagnostic(
                    code = "sqlite.resource_disappeared",
                    severity = DiagnosticSeverity.WARNING,
                    message = "table '$tableName' disappeared between discovery and record measurement",
                    identity = resource,
                    providerId = providerId,
                )
                "table '$tableName' is not present in the catalog"
            }
            return requested.associateWith { m -> absent(m, now, backend, reason) } to null
        }
        if (table.kind == com.mohamed.storageanalyzer.databaseapi.TableKind.VIRTUAL_TABLE) {
            return requested.associateWith { m ->
                notApplicable(m, now, backend, "rows of virtual tables are not analyzed; measure their shadow tables")
            } to null
        }
        val alias = snap.rowidAliases[tableName]
        val cols = table.columns
        val row = fetchRow(s, tableName, selector, cols, snap) { msg ->
            diagnostics += StorageDiagnostic(
                code = "sqlite.record_unresolved",
                severity = DiagnosticSeverity.WARNING,
                message = msg,
                identity = resource,
                providerId = providerId,
            )
        }
        if (row == null) {
            return requested.associateWith { m -> absent(m, now, backend, "record row not retrievable (missing or key mismatch)") } to null
        }

        // Classify each value per the record format. Fields that cannot be
        // classified keep the whole total honest (UNAVAILABLE), never zero.
        val sizes = ArrayList<SQLiteRecordFormat.ValueSize>(cols.size)
        val fieldMeasurements = ArrayList<com.mohamed.storageanalyzer.core.result.FieldMeasurement>(cols.size)
        val unclassified = ArrayList<Pair<String, String>>()
        val rowidLong = (selector as? SQLiteResourceIds.RowSelector.ByRowid)?.rowid
        for ((idx, col) in cols.withIndex()) {
            val valueClass = row.valueClassOf(idx)
            val isAlias = col.name == alias
            if (isAlias) {
                sizes += SQLiteRecordFormat.ValueSize(0, 0)
                fieldMeasurements += fieldOf(col, 0L, now, backend)
                continue
            }
            val vs = when (valueClass) {
                "null" -> SQLiteRecordFormat.ValueSize(0, 0)
                "integer" -> SQLiteRecordFormat.valueSize("integer", row.long(idx), 0, false)
                "real" -> SQLiteRecordFormat.valueSize("real", 0, 0, false)
                "text" -> SQLiteRecordFormat.valueSize("text", 0, (row.bytes(idx)?.size ?: 0).toLong(), false)
                "blob" -> SQLiteRecordFormat.valueSize("blob", 0, (row.bytes(idx)?.size ?: 0).toLong(), false)
                else -> null
            }
            if (vs == null) {
                unclassified += col.name to (valueClass ?: "unknown")
                fieldMeasurements += unmeasuredField(col.name, valueClass)
            } else {
                sizes += vs
                fieldMeasurements += fieldOf(col, vs.payloadBytes + vs.serialTypeVarintBytes, now, backend)
            }
        }
        if (unclassified.isNotEmpty()) {
            return requested.associateWith { m ->
                if (m == MetricType.DATABASE_RECORD_SIZE) {
                    absent(m, now, backend, "columns with an unknown value class: " + unclassified.joinToString { (n, t) -> "$n('$t')" })
                } else {
                    notApplicable(m, now, backend, "metric '${m.id}' is not exposed by the SQLite provider for records")
                }
            } to RecordBreakdown(
                fields = fieldMeasurements,
                overhead = null,
                unknown = null,
                measuredAt = now,
            )
        }
        val recordSize = SQLiteRecordFormat.recordSize(sizes, 0, table.withoutRowid, rowidLong ?: 0L)
        val serialSum = sizes.sumOf { it.serialTypeVarintBytes }
        val payloadSum = sizes.sumOf { it.payloadBytes }
        val headerSize = SQLiteRecordFormat.headerSizeOf(serialSum)
        val recordOnly = headerSize + payloadSum
        val overhead = SQLiteRecordFormat.varintLen(headerSize).toLong() +
            if (!table.withoutRowid) {
                SQLiteRecordFormat.varintLen(rowidLong ?: 0L).toLong() + SQLiteRecordFormat.varintLen(recordOnly).toLong()
            } else 0L
        val breakdown = RecordBreakdown(
            fields = fieldMeasurements,
            overhead = ByteCount.of(overhead),
            unknown = null,
            measuredAt = now,
        )
        val out = requested.associateWith { m ->
            when (m) {
                MetricType.DATABASE_RECORD_SIZE -> SingleMeasurement.measured(m, recordSize.total.value, SQLiteRecordFormat.provenance(now, backend))
                else -> notApplicable(m, now, backend, "metric '${m.id}' is not exposed by the SQLite provider for records")
            }
        }
        return out to breakdown
    }

    private fun fieldOf(
        col: com.mohamed.storageanalyzer.databaseapi.ColumnInfo,
        value: Long?,
        now: Instant,
        backend: String,
    ) = com.mohamed.storageanalyzer.core.result.FieldMeasurement(
        attributeName = col.name,
        metric = MetricType.DATABASE_RECORD_SIZE,
        value = value?.let { ByteCount.of(it) } ?: ByteCount.ZERO,
        state = MeasurementState.MEASURED,
        provenance = SQLiteRecordFormat.provenance(now, backend),
    )

    private fun unmeasuredField(name: String, valueClass: String?) =
        com.mohamed.storageanalyzer.core.entity.spi.unmeasuredField(
            name,
            MetricType.DATABASE_RECORD_SIZE,
            MeasurementProvenance.absent(providerIdValue, Instant.now(), MeasurementMethod.SQLITE_RECORD_FORMAT, reason = "unknown value class"),
            reason = "cannot classify value storage class '${valueClass ?: "unknown"}' in the SQLite record format",
        )

    /** Row accessor: `[class(col0), value0, class(col1), value1, ...]` pairs. */
    private class FetchedRow(val pairs: List<Pair<String?, Any?>>) {
        fun valueClassOf(idx: Int): String? = pairs[idx].first
        fun long(idx: Int): Long = when (val v = pairs[idx].second) {
            is Long -> v
            is Int -> v.toLong()
            is Number -> v.toLong()
            is String -> v.toLongOrNull() ?: 0L
            else -> 0L
        }
        fun bytes(idx: Int): ByteArray? = when (val v = pairs[idx].second) {
            is ByteArray -> v
            is String -> v.toByteArray(Charsets.UTF_8)
            else -> null
        }
    }

    private fun fetchRow(
        s: SystemState,
        tableName: String,
        selector: SQLiteResourceIds.RowSelector,
        cols: List<com.mohamed.storageanalyzer.databaseapi.ColumnInfo>,
        snap: SchemaSnapshot,
        onError: (String) -> Unit,
    ): FetchedRow? {
        val conn = s.connection ?: return null
        val projections = cols.joinToString(", ") { "typeof(${quote(it.name)}), ${quote(it.name)}" }
        val (where, bind) = when (selector) {
            is SQLiteResourceIds.RowSelector.ByRowid -> "rowid = ?" to listOf<Any?>(selector.rowid)
            is SQLiteResourceIds.RowSelector.ByPrimaryKey -> {
                val pkCols = snap.pkColumnsOf(tableName, cols)
                if (pkCols.size != selector.components.size) {
                    onError("record key has ${selector.components.size} components but table '$tableName' has ${pkCols.size} primary key columns")
                    return null
                }
                val cond = pkCols.joinToString(" AND ") { "${quote(it.name)} = ?" }
                cond to selector.components
            }
        }
        val sql = "SELECT $projections FROM ${quote(tableName)} WHERE $where"
        return try {
            val fetched = conn.query(sql, bind) { c ->
                if (c.next()) {
                    val pairs = ArrayList<Pair<String?, Any?>>(cols.size)
                    for (i in cols.indices) {
                        pairs.add(c.string(2 * i + 1) to c.objectOf(2 * i + 2))
                    }
                    FetchedRow(pairs)
                } else {
                    null
                }
            }
            if (fetched == null) {
                onError("row ($selector) not found in '$tableName'")
            }
            fetched
        } catch (e: java.sql.SQLException) {
            onError("failed fetching record row in '$tableName': ${e.message}")
            null
        }
    }

    // ---- aggregate per-column breakdown ----------------------------------

    internal fun aggregateFieldBreakdown(key: CollectionKey, ctx: AnalysisContext): List<AggregatedField> {
        val identity = key.resourceIdentity
        if (identity.providerId.value != providerIdValue ||
            identity.kind != ResourceKind.TABLE || !identity.nativeId.startsWith("t:")
        ) {
            return emptyList()
        }
        val s = stateOf(identity.systemId) ?: return emptyList()
        val snap = schemaOf(s, ctx) ?: return emptyList()
        val tableName = SQLiteResourceIds.decodeTableName(identity.nativeId) ?: return emptyList()
        val table = snap.tables.firstOrNull { it.tableName == tableName } ?: return emptyList()
        if (table.kind == com.mohamed.storageanalyzer.databaseapi.TableKind.VIRTUAL_TABLE) return emptyList()
        val conn = s.connection ?: return emptyList()
        val alias = snap.rowidAliases[tableName]
        val specs = table.columns.map { SQLiteRecordFormat.ColumnSpec(it.name, it.name == alias) }
        val projections = mutableListOf("COUNT(*)")
        for (col in specs) {
            val (payloadExpr, serialExpr) = SQLiteRecordFormat.columnComponents(tableName, col)
            projections += "COALESCE(SUM($payloadExpr), 0)"
            projections += "COALESCE(SUM($serialExpr), 0)"
            projections += if (col.isRowidAlias) "0" else {
                "COALESCE(SUM(CASE WHEN typeof(${quote(tableName)}.${quote(col.name)}) = 'null' THEN 0 ELSE 1 END), 0)"
            }
        }
        val sql = "SELECT ${projections.joinToString(", ")} FROM ${quote(tableName)}"
        val now = Instant.now()
        val fields = try {
            conn.query(sql) { c ->
                if (!c.next()) {
                    emptyList()
                } else {
                    val out = ArrayList<AggregatedField>(specs.size)
                    var pos = 2
                    for (col in specs) {
                        val payload = c.long(pos)
                        val serial = c.long(pos + 1)
                        val populated = c.long(pos + 2)
                        out += AggregatedField(
                            attributeName = col.name,
                            metric = MetricType.DATABASE_RECORD_SIZE,
                            totalAcrossRecords = ByteCount.of(payload + serial),
                            populatedRecords = populated,
                            provenance = SQLiteRecordFormat.provenance(now, snap.backend),
                        )
                        pos += 3
                    }
                    out
                }
            }
        } catch (e: java.sql.SQLException) {
            emptyList()
        } ?: emptyList()
        return fields
    }

    // ---- entity hooks & graph edges --------------------------------------

    override fun recordAnalytics(): RecordAnalyticsEngine = object : RecordAnalyticsEngine {
        override fun fieldBreakdown(resource: ResourceIdentity, ctx: AnalysisContext): RecordBreakdown? {
            if (resource.providerId.value != providerIdValue || resource.kind != ResourceKind.RECORD) return null
            val s = stateOf(resource.systemId) ?: return null
            if (!ensureOpen(s)) return null
            val snap = schemaOf(s, ctx) ?: return null
            val metrics = recordMeasurements(
                s, resource, setOf(MetricType.DATABASE_RECORD_SIZE), snap, Instant.now(), snap.backend,
                ArrayList(), ctx,
            )
            return metrics.second
        }

        override fun aggregateFieldBreakdown(collectionKey: CollectionKey, ctx: AnalysisContext): List<AggregatedField> =
            this@SQLiteStorageProvider.aggregateFieldBreakdown(collectionKey, ctx)
    }

    override fun entityResolver(): EntityResourceResolver = SQLiteEntityResolver(this)

    override fun related(resource: ResourceIdentity, ctx: AnalysisContext): List<ReferenceLink> {
        if (resource.providerId.value != providerIdValue || resource.kind != ResourceKind.DATABASE) return emptyList()
        val s = stateOf(resource.systemId) ?: return emptyList()
        val snap = schemaOf(s, ctx) ?: return emptyList()
        val stability = SQLiteResourceIds.stabilityOf(s.spec)
        val ns = SQLiteResourceIds.namespaceOf(s.spec)
        val out = ArrayList<ReferenceLink>(snap.tables.size + snap.indexes.size + snap.files.size)
        for (t in snap.tables) {
            out += ReferenceLink(providerId, SQLiteResourceIds.table(s.systemId, ns, t.tableName, stability), RelationType.CONTAINS, t.tableName)
        }
        for (i in snap.indexes) {
            out += ReferenceLink(providerId, SQLiteResourceIds.index(s.systemId, ns, i.indexName, stability), RelationType.CONTAINS, i.indexName)
        }
        for (f in snap.files) {
            if (f.role != DatabaseFileRole.MAIN) {
                out += ReferenceLink(providerId, f.identity, RelationType.RELATED, f.role.name.lowercase())
            }
        }
        return out
    }

    // ---- health -----------------------------------------------------------

    override fun healthCheck(ctx: AnalysisContext): List<StorageDiagnostic> {
        val out = ArrayList<StorageDiagnostic>()
        for (s in systemList) {
            ctx.ensureActive()
            val identity = databaseIdentityOf(s)
            val isRealFile = s.spec.path != ":memory:" && !s.spec.path.startsWith("jdbc:") && !s.spec.path.startsWith("file:")
            if (isRealFile && !File(s.spec.path).isFile) {
                out += StorageDiagnostic(
                    code = "sqlite.database_file_missing",
                    severity = DiagnosticSeverity.ERROR,
                    message = "database file '${s.spec.path}' does not exist",
                    identity = identity,
                    providerId = providerId,
                    suggestedAction = "remove the spec or restore the file; the provider never creates databases",
                )
                continue
            }
            if (!ensureOpen(s)) {
                out += StorageDiagnostic(
                    code = "sqlite.open_failed",
                    severity = DiagnosticSeverity.ERROR,
                    message = "database '${s.spec.path}' could not be opened: ${s.openError}",
                    identity = identity,
                    providerId = providerId,
                    suggestedAction = "verify the file is a valid SQLite database and readable by this process",
                )
                continue
            }
            val intro = s.introspection
            // Run integrity_check directly: a failed check must surface as an
            // error, not be swallowed by the capability short-circuit.
            val integrityResult = runCatching {
                s.connection!!.rows("PRAGMA integrity_check", maxRows = 100)
                    .mapNotNull { row -> row.values.filterIsInstance<String>().firstOrNull() }
            }
            val rows = integrityResult.getOrNull() ?: emptyList()
            when {
                integrityResult.isFailure -> out += StorageDiagnostic(
                    code = "sqlite.integrity_check_failed",
                    severity = DiagnosticSeverity.ERROR,
                    message = "integrity_check could not run: ${integrityResult.exceptionOrNull()?.message}",
                    identity = identity,
                    providerId = providerId,
                    suggestedAction = "the database file is likely corrupt or unreadable; this library never repairs",
                )
                rows.size == 1 && rows[0].equals("ok", ignoreCase = true) -> out += StorageDiagnostic(
                    code = "sqlite.integrity_ok",
                    severity = DiagnosticSeverity.INFO,
                    message = "integrity_check reported ok",
                    identity = identity,
                    providerId = providerId,
                )
                rows.isNotEmpty() -> out += StorageDiagnostic(
                    code = "sqlite.integrity_findings",
                    severity = DiagnosticSeverity.ERROR,
                    message = "integrity_check reported " + rows.size + " findings: " +
                        rows.take(5).joinToString("; ") { it.take(120) },
                    identity = identity,
                    providerId = providerId,
                    suggestedAction = "run PRAGMA integrity_check manually; this library never rewrites anything",
                )
                else -> {}
            }
            if (s.capabilities?.dbstatAvailable == false) {
                out += StorageDiagnostic(
                    code = "sqlite.dbstat_unavailable",
                    severity = DiagnosticSeverity.WARNING,
                    message = "this SQLite build lacks the dbstat virtual table",
                    identity = identity,
                    providerId = providerId,
                    suggestedAction = "use a build compiled with SQLITE_ENABLE_DBSTAT_VTAB for exact b-tree sums; otherwise sizes fall back to estimates",
                )
            }
        }
        return out
    }

    // ---- lifecycle ---------------------------------------------------------

    @Synchronized
    override fun close() {
        for (s in systemList) {
            runCatching { s.connection?.close() }
            s.connection = null
            s.capabilities = null
            s.introspection = null
            s.schemaCache = null
        }
    }

    // ---- entity helpers ---------------------------------------------------

    /** (namespace, stability) for SQL identity construction; null if unregistered. */
    internal fun sqlIdentityParams(systemId: StorageSystemId): Pair<String, IdentityStability>? {
        val s = stateOf(systemId) ?: return null
        return SQLiteResourceIds.namespaceOf(s.spec) to SQLiteResourceIds.stabilityOf(s.spec)
    }

    /** An already-opened connection for relation resolvers; null when closed. */
    internal fun openedConnection(systemId: StorageSystemId): SQLiteConnection? {
        val s = stateOf(systemId) ?: return null
        return if (ensureOpen(s)) s.connection else null
    }

    /** First registered system whose catalog contains [tableName]. */
    fun findSystemOfTable(tableName: String, ctx: AnalysisContext): StorageSystemId? {
        for (s in systemList) {
            ctx.ensureActive()
            val snap = schemaOf(s, ctx) ?: continue
            if (snap.tables.any { it.tableName == tableName }) return s.systemId
        }
        return null
    }

    /**
     * Streams row-level [StorageResource]s of one table. Rows stream lazily;
     * the query is a single ordered SELECT and cancellation is honored per
     * row. Returning the full resources keeps entity accounting types out of
     * the resolver.
     */
    fun streamRecordResources(
        systemId: StorageSystemId,
        tableName: String,
        ctx: AnalysisContext,
    ): Sequence<StorageResource> = sequence {
        val s = stateOf(systemId) ?: throw IllegalArgumentException("unknown SQLite system ${systemId.value}")
        val snap = schemaOf(s, ctx)
        if (snap == null) {
            ctx.diagnostic(
                StorageDiagnostic(
                    code = "sqlite.open_failed",
                    severity = DiagnosticSeverity.ERROR,
                    message = "system '${s.spec.path}' could not be opened while enumerating records: ${s.openError}",
                    providerId = providerId,
                ),
            )
            return@sequence
        }
        val table = snap.tables.firstOrNull { it.tableName == tableName }
        if (table == null || table.kind == com.mohamed.storageanalyzer.databaseapi.TableKind.VIRTUAL_TABLE) return@sequence
        val ns = SQLiteResourceIds.namespaceOf(s.spec)
        val stability = SQLiteResourceIds.stabilityOf(s.spec)
        val tableIdentity = SQLiteResourceIds.table(s.systemId, ns, tableName, stability)

        fun recordResource(identity: ResourceIdentity, label: String): StorageResource = StorageResource(
            identity = identity,
            providerId = providerId,
            systemId = s.systemId,
            label = label,
            kind = ResourceKind.RECORD,
            category = StorageCategory.DATABASE,
            decomposes = tableIdentity,
        )

        if (table.withoutRowid) {
            val pkCols = snap.pkColumnsOf(tableName, table.columns)
            val cols = pkCols.joinToString(", ") { quote(it.name) }
            val order = pkCols.joinToString(", ") { quote(it.name) }
            val sql = "SELECT $cols FROM ${quote(tableName)} ORDER BY $order"
            s.connection?.stream(sql) { c ->
                ctx.ensureActive()
                val comps = pkCols.indices.map { i -> c.string(i + 1) ?: "" }
                recordResource(
                    SQLiteResourceIds.recordByPrimaryKey(s.systemId, ns, tableName, comps, stability),
                    "$tableName#${comps.joinToString("|")}",
                )
            }?.forEach { yield(it) }
        } else {
            val sql = "SELECT rowid FROM ${quote(tableName)} ORDER BY rowid"
            s.connection?.stream(sql) { c ->
                ctx.ensureActive()
                val rowid = c.long(1)
                recordResource(
                    SQLiteResourceIds.recordByRowid(s.systemId, ns, tableName, rowid, stability),
                    "$tableName#$rowid",
                )
            }?.forEach { yield(it) }
        }
    }
}
