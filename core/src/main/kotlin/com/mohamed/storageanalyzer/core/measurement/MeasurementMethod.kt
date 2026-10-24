package com.mohamed.storageanalyzer.core.measurement

/**
 * How a measurement was obtained. Methods are frozen identifiers that are
 * exposed in provenance so that reports can be audited ("where does this
 * number come from?"). Providers may add custom methods via [custom].
 */
class MeasurementMethod private constructor(val id: String) {

    init {
        require(id.isNotEmpty()) { "MeasurementMethod id must not be empty" }
    }

    override fun equals(other: Any?): Boolean = other is MeasurementMethod && other.id == id

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = id

    companion object {
        val FILE_LENGTH = MeasurementMethod("fs.file_length")
        val DIRECTORY_SCAN = MeasurementMethod("fs.directory_scan")
        val FILE_IDENTITIES_SCAN = MeasurementMethod("fs.file_keys_scan")

        // Filesystem allocation measurement without native-platform access:
        // rounding logical length up to the allocation-unit size supplied by the
        // host. Classified as CONVENTION, never as EXACT.
        val ROUND_TO_CLUSTERS = MeasurementMethod("fs.round_to_clusters")

        // Volume-level space stats (total / usable / unallocated).
        val VOLUME_SPACE_STATS = MeasurementMethod("fs.volume_space_stats")

        // Volume-level space stats supplied by the host when the JVM cannot
        // resolve a FileStore (e.g. Android scoped storage via StatFs).
        val HOST_VOLUME_STATS = MeasurementMethod("fs.host_volume_stats")

        // POSIX/unix attribute namespaces (st_blocks etc.) where available.
        val POSIX_ATTRIBUTES = MeasurementMethod("fs.posix_attributes")

        val FILE_KEYS = MeasurementMethod("fs.file_ident_keys")

        // SQLite PRAGMAs and file inspection.
        val PRAGMA_PAGE_SIZE = MeasurementMethod("sqlite.pragma_page_size")
        val PRAGMA_PAGE_COUNT = MeasurementMethod("sqlite.pragma_page_count")
        val PRAGMA_FREELIST_COUNT = MeasurementMethod("sqlite.pragma_freelist_count")
        val PRAGMA_INTEGRITY_CHECK = MeasurementMethod("sqlite.pragma_integrity_check")

        // dbstat virtual table aggregate sums over B-tree pages: EXACT for the
        // b-tree as a whole (but never per-record attribution).
        val DBSTAT_BTREE_SUM = MeasurementMethod("sqlite.dbstat_btree_sum")

        // Per-record encoded sizes computed with the SQLite record format
        // (serial types + varints). CONVENTION accuracy.
        val SQLITE_RECORD_FORMAT = MeasurementMethod("sqlite.record_format")

        // Generic sized-estimate arithmetic inside a provider's native API.
        val DATABASE_SCHEMA_QUERY = MeasurementMethod("db.schema_query")
        val SQLITE_RECORD_ROWS = MeasurementMethod("sqlite.record_rows")

        // Attribution policies applied on top of measurements.
        val POLICY_CANONICAL_OWNER = MeasurementMethod("policy.canonical_owner")
        val POLICY_EQUAL_SPLIT = MeasurementMethod("policy.equal_split")
        val POLICY_PROPORTIONAL = MeasurementMethod("policy.proportional")
        val POLICY_UNIQUE_GLOBAL_DEDUPLICATION = MeasurementMethod("policy.unique_global")

        // History / forecasting.
        val SNAPSHOT_DIFFERENCE = MeasurementMethod("history.snapshot_diff")
        val FORECAST_LINEAR_REGRESSION = MeasurementMethod("history.linear_regression")

        /** Provider-specific delta (cached incremental measurement identity). */
        val PROVIDER_METADATA = MeasurementMethod("provider.metadata")

        fun custom(id: String): MeasurementMethod {
            require(id.contains('.')) { "Custom MeasurementMethod ids should be namespaced like 'x_provider.method' (was '$id')" }
            require(BUILT_IN.none { it.id == id }) { "MeasurementMethod id '$id' collides with built-in method" }
            return MeasurementMethod(id)
        }

        private val BUILT_IN = listOf(
            FILE_LENGTH, DIRECTORY_SCAN, FILE_IDENTITIES_SCAN, ROUND_TO_CLUSTERS, VOLUME_SPACE_STATS,
            HOST_VOLUME_STATS, POSIX_ATTRIBUTES, FILE_KEYS, PRAGMA_PAGE_SIZE, PRAGMA_PAGE_COUNT, PRAGMA_FREELIST_COUNT,
            PRAGMA_INTEGRITY_CHECK, DBSTAT_BTREE_SUM, SQLITE_RECORD_FORMAT, DATABASE_SCHEMA_QUERY,
            SQLITE_RECORD_ROWS, POLICY_CANONICAL_OWNER, POLICY_EQUAL_SPLIT, POLICY_PROPORTIONAL,
            POLICY_UNIQUE_GLOBAL_DEDUPLICATION, SNAPSHOT_DIFFERENCE, FORECAST_LINEAR_REGRESSION,
            PROVIDER_METADATA
        )
    }
}
