package com.mohamed.storageanalyzer.core.entity

import com.mohamed.storageanalyzer.core.identity.IdentityStability
import com.mohamed.storageanalyzer.core.identity.ProviderId
import com.mohamed.storageanalyzer.core.identity.ResourceIdentity
import com.mohamed.storageanalyzer.core.identity.ResourceKind
import com.mohamed.storageanalyzer.core.identity.StorageSystemId

/**
 * Application-level key of a concrete entity instance (e.g. a business id.
 * Responsible for mapping to native resource identities is the
 * [EntityDescriptor]).
 */
@JvmInline
value class EntityInstanceKey(val value: String) {
    override fun toString(): String = value
}

/**
 * Declares how a concept-level entity (application object) is stored, so that
 * the analyzer can attribute storage to it.
 *
 * Entities are *views over storage*, not storage themselves: a descriptor
 * maps an entity id (+ key) to the storage resources that back it. The library
 * then uses provider-supplied measurement and relation information to produce
 * per-entity, per-field and aggregated analytics.
 *
 * Ordinary entities do **not** require custom sizing implementations: when a
 * provider implements `RecordAnalyticsEngine` and the descriptor maps to a
 * provider-known resource (e.g. a table row), the encoding-level breakdown
 * comes from the provider. Custom sizing strategies remain available via
 * custom relation resolvers, custom providers, or custom record engines.
 */
data class EntityDescriptor(
    /** Stable, application-unique id, e.g. `"myapp.User"` (never a DB table name assumption). */
    val entityTypeId: String,

    /** Provider that hosts the entity's primary resources. */
    val providerId: ProviderId,

    /** Storage system (e.g. database file) when fixed; `null` = any system of the provider. */
    val systemId: StorageSystemId? = null,

    /** Resource kind of the entity's primary storage (e.g. [ResourceKind.RECORD]). */
    val kind: ResourceKind,

    /** Human-readable display name (may be omitted for privacy-sensitive naming). */
    val displayName: String? = null,

    /** Maps an instance key to the native id of the primary storage resource. */
    val nativeIdOf: (EntityInstanceKey) -> String,

    /** Declared relationships to other storage resources. */
    val relations: List<EntityRelation> = emptyList(),

    /**
     * Provider-specific hints (e.g. `sqlite.table` = backing table name) used
     * by provider implementions of
     * [com.mohamed.storageanalyzer.core.entity.spi.EntityResourceResolver]
     * to enumerate instances without leaking provider types into the core.
     * Keys must stay documented and stable per provider.
     */
    val hints: Map<String, String> = emptyMap(),

    /** Identity stability declaration forwarded to resources of this descriptor. */
    val identityStability: IdentityStability = IdentityStability.STABLE,
) {
    init {
        require(entityTypeId.isNotBlank()) { "entityTypeId must not be blank" }
    }

    /**
     * The identity of the primary storage resource backing one entity
     * instance, as visible to the analyzer.
     */
    fun identityOf(
        propertyKey: EntityInstanceKey,
        providerId: ProviderId = this.providerId,
        systemId: StorageSystemId? = this.systemId,
    ): ResourceIdentity {
        val sys = requireResolvedSystem(providerId, systemId)
        return ResourceIdentity(
            providerId = providerId,
            systemId = sys,
            kind = kind,
            nativeId = nativeIdOf(propertyKey),
            namespace = entityTypeId,
            stability = identityStability,
        )
    }

    private fun requireResolvedSystem(
        providerId: ProviderId = this.providerId,
        systemId: StorageSystemId? = this.systemId,
    ) = systemId ?: throw IllegalArgumentException(
        "EntityDescriptor '$entityTypeId' has no fixed systemId; supply one for identity resolution",
    )
}
