package com.mohamed.storageanalyzer.core.identity

import java.io.File

/**
 * The canonical filesystem resource identity scheme, used by the filesystem
 * provider and mirrored by other providers that touch physical files (e.g.
 * SQLite sidecars declare dedup aliases in this scheme).
 *
 * Scheme (documented for cross-provider stability):
 * - [ResourceIdentity.providerId] = `filesystem`
 * - [ResourceIdentity.systemId] = canonical absolute path of the resource's
 *   **parent directory** (mount-point-stable normalization)
 * - [ResourceIdentity.nativeId] = the base name of the file or directory
 * - [ResourceIdentity.namespace] = `null` for filesystem file resources
 *
 * Two providers that agree on this scheme report the *same* physical file
 * under one stable key, which is what lets the global inventory avoid double
 * counting a `.sqlite` container discovered both as file and as database.
 */
object PathIdentity {

    const val FILESYSTEM_PROVIDER_ID = "filesystem"

    /** Identity of a file (or directory when [kind] says so) at [path]. */
    fun forPath(path: String, kind: ResourceKind = ResourceKind.FILE): ResourceIdentity {
        val canonical = File(path).canonicalFile
        val parent = canonical.parent ?: "."
        return ResourceIdentity(
            providerId = ProviderId(FILESYSTEM_PROVIDER_ID),
            systemId = StorageSystemId(parent),
            kind = kind,
            nativeId = canonical.name,
        )
    }

    /** Identity of the file [dir]/[name]. */
    fun forChild(dir: File, name: String, kind: ResourceKind = ResourceKind.FILE): ResourceIdentity = ResourceIdentity(
        providerId = ProviderId(FILESYSTEM_PROVIDER_ID),
        systemId = StorageSystemId(dir.canonicalPath),
        kind = kind,
        nativeId = name,
    )
}
