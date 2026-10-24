package com.mohamed.storageanalyzer.core.resource

import com.mohamed.storageanalyzer.core.identity.ProviderId
import com.mohamed.storageanalyzer.core.identity.ResourceIdentity

/**
 * Directed memory-light edge from one resource to another, emitted lazily by
 * providers during graph traversal. Edges never carry payloads — identity only.
 */
data class ReferenceLink(
    val fromProviderId: ProviderId,
    val toIdentity: ResourceIdentity,
    val relation: RelationType,
    /** Short human label (e.g. column name) used for diagnostics; may be null. */
    val label: String? = null,
)

/**
 * Meaning of a link between resources.
 */
enum class RelationType {
    /** `from` directly contains `to` (table row → BLOB value, dir → file). */
    CONTAINS,

    /** `from` references `to` without containment (and may share it). */
    REFERENCES,

    /** Structural relationship for query purposes only; NOT used for storage accounting. */
    RELATED,

    /** `from` is an alias/in-place duplicate of `to` (hard link). */
    ALIAS,

    /** Owner-of relation for entity-level accounting. */
    RELATES_TO
}
