package com.mohamed.storageanalyzer.core.resource

/**
 * Consumer-facing storage bucket used for reporting breakdowns
 * (by document, media, cache, backup, ...). Providers supply a default
 * mapping; applications can override via the provider configuration.
 */
enum class StorageCategory {
    APPLICATION_DATABASE,
    DATABASE,
    DOCUMENT,
    MEDIA,
    ARCHIVE,
    BACKUP,
    CACHE,
    TEMPORARY,
    LOGS,
    EXECUTABLE,
    SOURCE_CODE,
    OTHER
}

/**
 * Application-defined reporting scope. Scopes let consumers group resources by
 * their own concepts (e.g. "feature A attachments", "tenant #4 database").
 * Providers may pre-assign a scope; consumers may also filter/group later —
 * see `com.mohamed.storageanalyzer.core.result.Groupings`.
 */
data class StorageScope(
    /** Stable scope id (used as grouping key). */
    val id: String,

    /** Human-readable label; may be redacted in reports. */
    val label: String? = null,
) {
    init {
        require(id.isNotBlank()) { "StorageScope.id must not be blank" }
    }
}
