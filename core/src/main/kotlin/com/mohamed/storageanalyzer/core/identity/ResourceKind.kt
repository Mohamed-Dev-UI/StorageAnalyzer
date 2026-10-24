package com.mohamed.storageanalyzer.core.identity

/**
 * Conceptual category of a storage resource. Kinds are categories, not
 * mandatory subclasses: the same physical object can legitimately be reported
 * as more than one kind by different providers (e.g. a `.sqlite` file is a
 * [FILE] for the filesystem provider and backs a [DATABASE] for the SQLite
 * provider).
 *
 * No kind is assumed to have a particular measurement behavior; measurement
 * capabilities are declared separately per provider.
 */
enum class ResourceKind {
    /** A database or an attachable storage unit of one. */
    DATABASE,

    /** A table, collection or equivalent logical container of records. */
    TABLE,

    /** A single record / document / row. */
    RECORD,

    /** A database index or equivalent auxiliary ordered structure. */
    INDEX,

    /** A self-contained document (e.g. PDF, Markdown). */
    DOCUMENT,

    /** A single entry of a key-value store. */
    KEY_VALUE_ENTRY,

    /** A single object of a (remote) object store. */
    OBJECT,

    /** A file on some filesystem. */
    FILE,

    /** A directory / folder. */
    DIRECTORY,

    /** An attached binary payload (e.g. a BLOB column value). */
    BINARY_PAYLOAD,

    /** A serialized object representation. */
    SERIALIZED_OBJECT,

    /** A cache entry. */
    CACHE_ENTRY,

    /** A backup or backup archive. */
    BACKUP,

    /** A generic archive (zip, tar, container image layer ...). */
    ARCHIVE,

    /** A reference to a resource that belongs to another system. */
    EXTERNAL_RESOURCE,

    /** Anything a provider-specific analyzer reports that fits no category. */
    CUSTOM
}
