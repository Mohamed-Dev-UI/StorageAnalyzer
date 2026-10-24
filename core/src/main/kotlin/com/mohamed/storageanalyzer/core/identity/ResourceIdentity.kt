package com.mohamed.storageanalyzer.core.identity

/**
 * How trustworthy the identity of a resource is for use as a stable,
 * repeatable identifier (deduplication, history, reconciliation).
 */
enum class IdentityStability {
    /**
     * The identity is stable and repeatable across measurements (e.g. an
     * absolute path, a primary key, an object key). Safe for deduplication.
     */
    STABLE,

    /**
     * The identity is only stable within a single execution (e.g. derived from
     * a transient handle or a not-yet-flushed WAL segment). Deduplication is
     * performed, but history comparisons must not treat this resource as
     * persistent.
     */
    EPHEMERAL,

    /**
     * The provider cannot make a statement about stability (e.g. a native id
     * that is a regenerated surrogate). Deduplication still happens by the
     * stable key, but consumers must validate before trusting persistence.
     */
    UNKNOWN
}

/**
 * Global identity of a measurable storage resource.
 *
 * A resource identity combines:
 * - the [providerId] that can measure it,
 * - the [systemId] of the concrete storage system instance,
 * - the conceptual [kind],
 * - a provider-defined [nativeId] (may encode composite keys, e.g.
 *   `"orders:42"` for a table plus rowid),
 * - an optional [namespace] (e.g. schema name, mount point, bucket prefix),
 * - an optional [generation] (e.g. snapshot generation, object version).
 *
 * **Never assume that a path, filename, primary key, or URI alone is globally
 * unique** — it is only unique in combination with provider + system + kind +
 * namespace. The canonical cross-context comparison key is [stableKey], which
 * percent-encodes every component, making it collision-free even for ids that
 * contain the separator character.
 */
data class ResourceIdentity(
    val providerId: ProviderId,
    val systemId: StorageSystemId,
    val kind: ResourceKind,
    val nativeId: String,
    val namespace: String? = null,
    val generation: String? = null,
    val stability: IdentityStability = IdentityStability.STABLE,
) {

    init {
        require(nativeId.isNotEmpty()) { "nativeId must not be empty: nativeId='$nativeId'" }
        require(!nativeId.contains('\u0000')) { "nativeId must not contain NUL characters" }
        namespace?.let { require(!it.contains('\u0000')) { "namespace must not contain NUL characters" } }
        generation?.let { require(!it.contains('\u0000')) { "generation must not contain NUL characters" } }
    }

    /**
     * Deterministic, collision-safe identity key used for global
     * deduplication, shared-resource accounting and history comparisons.
     *
     * Encoding: each component is UTF-8 percent-encoded (lowercase hex), then
     * joined with `/`. An absent optional component contributes an empty
     * field. Two components never share a separator inside their encoded
     * representation, therefore the mapping `identity -> stableKey` is
     * injective: two different identities **always** produce two different
     * keys.
     */
    val stableKey: String by lazy(LazyThreadSafetyMode.PUBLICATION) {
        listOf(
            encode(providerId.value),
            encode(systemId.value),
            encode(kind.name),
            encode(namespace.orEmpty()),
            encode(nativeId),
            encode(generation.orEmpty()),
        ).joinToString(SEPARATOR)
    }

    /**
     * Fingerprint usable for *staleness heuristics* (never as proof of
     * content equality); carried as the [stability] constructor property.
     */

    override fun toString(): String = stableKey

    companion object {
        private const val SEPARATOR = "/"

        internal fun encode(raw: String): String {
            val sb = StringBuilder(raw.length)
            for (b in raw.toByteArray(Charsets.UTF_8)) {
                val c = b.toInt().toChar()
                if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in "-_.~") {
                    sb.append(c)
                } else {
                    sb.append('%')
                        .append(Character.toUpperCase(Character.forDigit(b.toInt() shr 4 and 0xF, 16)))
                        .append(Character.toUpperCase(Character.forDigit(b.toInt() and 0xF, 16)))
                }
            }
            return sb.toString()
        }
    }
}
