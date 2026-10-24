package com.mohamed.storageanalyzer.core.identity

/**
 * Stable identifier of a [com.mohamed.storageanalyzer.core.provider.StorageProvider]
 * implementation, e.g. `"filesystem"` or `"sqlite"`.
 *
 * Provider ids are used inside [ResourceIdentity.stableKey] and therefore must be
 * stable across library versions. Third-party providers should use a reversed
 * domain name (e.g. `"com.example.objectstore"`) to avoid collisions with
 * built-in providers.
 */
@JvmInline
value class ProviderId(val value: String) {

    init {
        require(value.isNotBlank()) { "ProviderId must be non-blank" }
        require(':' !in value && '/' !in value && '\\' !in value) {
            "ProviderId '$value' must not contain path separators or ':'"
        }
    }

    override fun toString(): String = value
}

/**
 * Identity of a concrete storage system instance within one provider, e.g. the
 * canonical path of a database file, the absolute path of a filesystem root, or
 * the URI of an object-store bucket.
 *
 * The [value] must normalize to the same string for the *same* underlying
 * system so that cross-provider deduplication (see the reconciliation guide) is
 * possible. Provider implementations must document their normalization rules.
 */
@JvmInline
value class StorageSystemId(val value: String) {

    init {
        require(value.isNotBlank()) { "StorageSystemId must be non-blank" }
        require('\u0000' !in value) { "StorageSystemId must not contain NUL characters" }
    }

    override fun toString(): String = value
}
