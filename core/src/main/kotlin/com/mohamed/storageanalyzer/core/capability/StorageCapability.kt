package com.mohamed.storageanalyzer.core.capability

/**
 * A discoverable, documented measurement capability of a provider/backend.
 *
 * Presence/absence is detected at runtime and reported; absence is always
 * communicated with a reason (see [Capabilities.unavailableReasonOf]).
 */
data class StorageCapability(val id: String, val description: String) {

    override fun toString(): String = id

    companion object {
        val FILE_LENGTH = StorageCapability("fs.file_length", "Exact logical file length")
        val FILE_ALLOCATED_SIZE = StorageCapability("fs.allocated_size", "Allocated on-disk size")
        val SPARSE_FILE_DETECTION = StorageCapability("fs.sparse_files", "Sparse file detection")
        val HARD_LINK_DETECTION = StorageCapability("fs.hard_links", "Hard link / duplicate detection")
        val VOLUME_SPACE_STATS = StorageCapability("fs.volume_stats", "Volume-level space statistics")
        val SYMLINK_DETECTION = StorageCapability("fs.symbols", "Symbolic link detection")

        val DB_FILES = StorageCapability("db.files", "Database file measurement")
        val DB_PAGE_ACCOUNTING = StorageCapability("db.page_accounting", "Page-granular accounting (page size/count/freelist)")
        val DB_TABLE_STORAGE = StorageCapability("db.table_storage", "Per-table B-tree/segment storage")
        val DB_INDEX_STORAGE = StorageCapability("db.index_storage", "Per-index storage")
        val DB_RECORD_SIZE = StorageCapability("db.record_size", "Per-record encoded size")
        val DB_FIELD_BREAKDOWN = StorageCapability("db.field_breakdown", "Per-field record breakdown")
        val DB_WAL_FILES = StorageCapability("db.wal_files", "WAL/journal file discovery and measurement")
        val DB_SCHEMA_METADATA = StorageCapability("db.schema_metadata", "Schema metadata (tables, columns, indexes)")
        val DB_CONSISTENCY_SNAPSHOT = StorageCapability("db.snapshot_reads", "Consistent snapshot reads (single read transaction)")
        val DB_DBSTAT = StorageCapability("db.dbstat", "Exact B-tree page sums via dbstat")

        val ENTITY_LEVEL_ANALYTICS = StorageCapability("entity.resources", "Entity-level resource enumeration")
        val RELATION_TRAVERSAL = StorageCapability("entity.relations", "Lazy relation traversal")
        val KEY_VALUE_ANALYTICS = StorageCapability("kv.entries", "Key-value entry analytics")
        val OBJECT_STORAGE_ANALYTICS = StorageCapability("object.store", "Object-store analytics")
        val SERIALIZED_SIZE = StorageCapability("format.encoded", "Encoded/serialized size measurement")
    }
}

/**
 * A snapshot of supported/unsupported capabilities for a concrete system.
 */
data class Capabilities(
    val supported: Set<StorageCapability>,
    /** Capability unsupported by this backend → reason (transportable, auditable). */
    val unsupportedReasons: Map<StorageCapability, String>,
) {
    fun supports(capability: StorageCapability): Boolean = capability in supported

    fun unsupportedReasonOf(capability: StorageCapability): String? = unsupportedReasons[capability]

    companion object {
        /** All built-in capability constants (for diagnostics/matrix generation). */
        val ALL_CAPABILITIES: Set<StorageCapability> = setOf(
            StorageCapability.FILE_LENGTH, StorageCapability.FILE_ALLOCATED_SIZE,
            StorageCapability.SPARSE_FILE_DETECTION, StorageCapability.HARD_LINK_DETECTION,
            StorageCapability.VOLUME_SPACE_STATS, StorageCapability.SYMLINK_DETECTION,
            StorageCapability.DB_FILES, StorageCapability.DB_PAGE_ACCOUNTING,
            StorageCapability.DB_TABLE_STORAGE, StorageCapability.DB_INDEX_STORAGE,
            StorageCapability.DB_RECORD_SIZE, StorageCapability.DB_FIELD_BREAKDOWN,
            StorageCapability.DB_WAL_FILES, StorageCapability.DB_SCHEMA_METADATA,
            StorageCapability.DB_CONSISTENCY_SNAPSHOT, StorageCapability.DB_DBSTAT,
            StorageCapability.ENTITY_LEVEL_ANALYTICS, StorageCapability.RELATION_TRAVERSAL,
            StorageCapability.KEY_VALUE_ANALYTICS, StorageCapability.OBJECT_STORAGE_ANALYTICS,
            StorageCapability.SERIALIZED_SIZE
        )
    }
}
