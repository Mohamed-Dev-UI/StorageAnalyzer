package com.mohamed.storageanalyzer.core.resource

import com.mohamed.storageanalyzer.core.identity.IdentityStability
import com.mohamed.storageanalyzer.core.identity.ProviderId
import com.mohamed.storageanalyzer.core.identity.ResourceIdentity
import com.mohamed.storageanalyzer.core.identity.ResourceKind
import com.mohamed.storageanalyzer.core.identity.StorageSystemId

/**
 * A concrete, discoverable storage resource: its identity plus the metadata a
 * provider needs to describe it (kind, category, ownership hints).
 *
 * `StorageResource` is what providers emit during discovery; measurements are
 * taken separately from the [identity] so that inventory and measurement are
 * independently cancellable and budgetable.
 */
data class StorageResource(
    val identity: ResourceIdentity,
    val providerId: ProviderId,
    val systemId: StorageSystemId,

    /** Human-readable label used in reports. May be omitted for privacy. */
    val label: String? = null,

    val kind: ResourceKind,
    val category: StorageCategory,

    /** Identity stability declaration (see [IdentityStability]). */
    val identityStability: IdentityStability = IdentityStability.UNKNOWN,

    /**
     * Ownership hints for shared-resource accounting (see the accounting
     * guide): owners of this resource as declared by the provider. Empty for
     * un-attributed/unowned resources — this is information, not an error.
     */
    val owners: List<OwnerRef> = emptyList(),

    /**
     * Alternative identities under which *another* provider might also
     * discover this resource (e.g. a SQLite provider declaring the backing
     * file's filesystem identity). The global inventory uses these to avoid
     * double counting a resource discovered through two providers.
     */
    val alsoDiscoverableBy: Set<ResourceIdentity> = emptySet(),

    /**
     * When non-null, this resource is a **decomposition view** of another
     * (container) resource: its bytes are part of the container's footprint,
     * not additional physical storage (e.g. a table of a database file, an
     * index of a table b-tree).
     *
     * Global footprint aggregation excludes decompositions from the unique
     * totals — summing them would double count the container — but their
     * per-metric values still appear in the metric breakdowns so per-table or
     * per-index views remain visible.
     */
    val decomposes: ResourceIdentity? = null,
)

/**
 * A claimed owner of a resource — an entity, a filesystem path/scope, or
 * something the provider attributes ownership to for accounting purposes.
 */
data class OwnerRef(
    val id: String,
    val kind: OwnerKind,
    /** Entity type id when [kind] == [OwnerKind.ENTITY], else null. */
    val entityTypeId: String? = null,
) : Comparable<OwnerRef> {

    init {
        require(id.isNotBlank()) { "OwnerRef.id must not be blank" }
        require(kind == OwnerKind.ENTITY || entityTypeId == null) { "entityTypeId may only be set for OwnerKind.ENTITY" }
        if (kind == OwnerKind.ENTITY) {
            require(!entityTypeId.isNullOrBlank()) { "OwnerRef of kind ENTITY requires an entityTypeId" }
        }
    }

    override fun compareTo(other: OwnerRef): Int = id.compareTo(other.id)

    enum class OwnerKind { ENTITY, PATH, SCOPE, UNKNOWN }
}
