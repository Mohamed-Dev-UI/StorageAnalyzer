package com.mohamed.storageanalyzer.core.entity

import com.mohamed.storageanalyzer.core.identity.ResourceIdentity
import com.mohamed.storageanalyzer.core.resource.ReferenceLink

/**
 * Kind of relationship between two entities, covering the supported
 * cardinalities.
 */
enum class RelationshipKind {
    ONE_TO_ONE,
    ONE_TO_MANY,
    MANY_TO_MANY,
    /** A file/value logically owned by the entity but stored outside its row. */
    OWNED_EXTERNAL,
    /** Unspecified/association (treated as RELATED for accounting). */
    ASSOCIATION,
}

/**
 * A declared relation from one entity descriptor to other storage resources.
 *
 * Resolvers are provider-agnostic SAM wrappers over [ReferenceLink]s, so both
 * declarative (e.g. SQLite foreign-key mapping) and dynamic resolvers can be
 * attached. Providers ship ready-made resolvers under their own packages
 * (e.g. `com.mohamed.storageanalyzer.sqlite.relations`).
 */
fun interface EntityRelationResolver {
    /** Returns the storage edges that make up this relationship from [source]. */
    fun resolve(source: ResourceIdentity): List<ReferenceLink>
}

data class EntityRelation(
    /** Short relation name (e.g. `"orders"`, `"avatarFile"`). */
    val name: String,

    val kind: RelationshipKind,

    /**
     * Lazily evaluated resolver producing the storage links for this relation.
     * Never loads the entire graph at registration time.
     */
    val resolver: EntityRelationResolver,

    /**
     * When true, resources reached through this relation are treated as
     * *shared* with other entities unless the provider says otherwise (used
     * for hard-linked files or many-to-many junctions shared by design).
     */
    val sharedByDesign: Boolean = false,

    /** Whether traversal should recurse through this relation. */
    val traversable: Boolean = true,
) {
    init {
        require(name.isNotBlank()) { "EntityRelation.name must not be blank" }
    }
}
