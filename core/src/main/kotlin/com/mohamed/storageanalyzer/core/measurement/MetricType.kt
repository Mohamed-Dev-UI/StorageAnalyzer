package com.mohamed.storageanalyzer.core.measurement

/**
 * The *dimension* a metric belongs to. Distinct dimensions must never be
 * conflated: "the sum of file lengths is not the physical disk allocation, and
 * database page capacity is not the amount of live application content."
 */
enum class MetricDimension {
    /** Intrinsic informational size of content (uncompressed, unencoded). */
    LOGICAL,

    /** Size of an encoded / serialized representation. */
    ENCODED,

    /** Size after compression (HDD, WAL-compact fits here semantically too). */
    COMPRESSED,

    /** Size of encrypted bytes including integrity/padding where exposed. */
    ENCRYPTED,

    /** Size of the stored payload representation (e.g. record payload bytes). */
    STORED,

    /** Bytes reserved on disk by the storage engine (allocated granularity). */
    ALLOCATED,

    /** Capacity of a container (pages × page size, volume, quota). */
    CAPACITY,

    /** Free / usable bytes inside an allocation. */
    FREE,

    /** Bookkeeping output of attribution policies. */
    ATTRIBUTION
}

/**
 * The exact *kind* of size being reported.
 *
 * Built-in metric constants are frozen identifiers — safe to match over.
 * Providers may declare additional metrics via [custom] with lowercase ids;
 * custom metrics must be namespaced (e.g. `x_myprovider.fsyncs`) to avoid
 * collisions with future built-in constants.
 */
data class MetricType(val id: String, val dimension: MetricDimension) {

    init {
        require(id.isNotEmpty()) { "MetricType id must not be empty" }
        require(id.all { it == '_' || it == '.' || it == '-' || it in 'a'..'z' || it in '0'..'9' }) {
            "MetricType id '$id' must be lowercase snake/dot notation"
        }
    }

    override fun toString(): String = id

    companion object {
        /** Uncompressed logical size of the content. */
        val LOGICAL_CONTENT_SIZE = MetricType("logical_content_size", MetricDimension.LOGICAL)

        /** Raw source body size before layering (e.g. an HTTP/object body). */
        val RAW_SOURCE_SIZE = MetricType("raw_source_size", MetricDimension.LOGICAL)

        /** Encoded / serialized size (e.g. UTF-8 bytes of a JSON document). */
        val ENCODED_CONTENT_SIZE = MetricType("encoded_content_size", MetricDimension.ENCODED)

        /** Size after compression where the provider exposes a compressed view. */
        val COMPRESSED_CONTENT_SIZE = MetricType("compressed_content_size", MetricDimension.COMPRESSED)

        /** Encrypted payload size including nonce/tag/padding where exposed. */
        val ENCRYPTED_CONTENT_SIZE = MetricType("encrypted_content_size", MetricDimension.ENCRYPTED)

        /** Size of the stored representation (e.g. file length, final payload). */
        val STORED_CONTENT_SIZE = MetricType("stored_content_size", MetricDimension.STORED)

        /** Encoded per-record storage measured according to the record format. */
        val DATABASE_RECORD_SIZE = MetricType("database_record_size", MetricDimension.STORED)

        /** Container capacity of a database (page_count × page_size). */
        val DATABASE_PAGE_CAPACITY = MetricType("database_page_capacity", MetricDimension.CAPACITY)

        /** B-tree / segment storage of one table. */
        val DATABASE_TABLE_STORAGE = MetricType("database_table_storage", MetricDimension.ALLOCATED)

        /** B-tree / segment storage of one index. */
        val DATABASE_INDEX_STORAGE = MetricType("database_index_storage", MetricDimension.ALLOCATED)

        /** Schema / metadata storage (e.g. sqlite_schema page usage). */
        val DATABASE_METADATA_STORAGE = MetricType("database_metadata_storage", MetricDimension.ALLOCATED)

        /** Freelist (unused allocated) space inside a database file. */
        val DATABASE_FREELIST_SIZE = MetricType("database_freelist_size", MetricDimension.FREE)

        /** Logical length of a file on a filesystem. */
        val FILE_LOGICAL_SIZE = MetricType("file_logical_size", MetricDimension.STORED)

        /** Allocated filesystem size (cluster granularity) where measurable. */
        val FILE_ALLOCATED_SIZE = MetricType("file_allocated_size", MetricDimension.ALLOCATED)

        /** Filesystem metadata size (e.g. extended attributes) where measurable. */
        val METADATA_STORAGE = MetricType("metadata_storage", MetricDimension.ALLOCATED)

        /** Reserved capacity of the volume / provider-side quota reservation. */
        val RESERVED_CAPACITY = MetricType("reserved_capacity", MetricDimension.CAPACITY)

        /** Total physical capacity of the volume / quota. */
        val TOTAL_CAPACITY = MetricType("total_capacity", MetricDimension.CAPACITY)

        /** Usable free space of the volume / quota. */
        val FREE_CAPACITY = MetricType("free_capacity", MetricDimension.FREE)

        /** Provider-reported remote usage (e.g. bucket usage, iSCSI LUN size). */
        val REMOTE_USAGE = MetricType("remote_usage", MetricDimension.ALLOCATED)

        /** Temporary/ephemeral storage (WAL, journal, tmp files)... */
        val TEMPORARY_STORAGE = MetricType("temporary_storage", MetricDimension.ALLOCATED)

        /** Backup/archive storage that a provider exposes directly. */
        val ARCHIVE_STORAGE = MetricType("archive_storage", MetricDimension.STORED)

        /**
         * Creates a provider-specific metric. Ids must be lowercase and should
         * carry a provider prefix (e.g. `x_sqlite.ptrmap_pages`).
         */
        fun custom(id: String, dimension: MetricDimension): MetricType =
            requireBuiltInNotColliding(id).let { MetricType(id, dimension) }

        private fun requireBuiltInNotColliding(id: String): Boolean {
            require(!BUILT_IN_IDS.contains(id)) { "MetricType id '$id' collides with a built-in metric" }
            return true
        }

        /** Registry used to look up metrics by stable id (serialization, reports). */
        val ALL: List<MetricType> = listOf(
            LOGICAL_CONTENT_SIZE, RAW_SOURCE_SIZE, ENCODED_CONTENT_SIZE, COMPRESSED_CONTENT_SIZE,
            ENCRYPTED_CONTENT_SIZE, STORED_CONTENT_SIZE, DATABASE_RECORD_SIZE, DATABASE_PAGE_CAPACITY,
            DATABASE_TABLE_STORAGE, DATABASE_INDEX_STORAGE, DATABASE_METADATA_STORAGE,
            DATABASE_FREELIST_SIZE, FILE_LOGICAL_SIZE, FILE_ALLOCATED_SIZE, METADATA_STORAGE,
            RESERVED_CAPACITY, TOTAL_CAPACITY, FREE_CAPACITY, REMOTE_USAGE, TEMPORARY_STORAGE,
            ARCHIVE_STORAGE
        )

        private val BUILT_IN_IDS: Set<String> = ALL.map { it.id }.toSet()

        /** Looks up a built-in metric by id, `null` for unknown/custom ids. */
        fun fromId(id: String): MetricType? = ALL.firstOrNull { it.id == id }
    }
}
