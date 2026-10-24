package com.mohamed.storageanalyzer.sqlite

import com.mohamed.storageanalyzer.core.config.AnalysisContext
import com.mohamed.storageanalyzer.core.entity.EntityDescriptor
import com.mohamed.storageanalyzer.core.entity.EntityInstanceKey
import com.mohamed.storageanalyzer.core.entity.EntityRelation
import com.mohamed.storageanalyzer.core.entity.RelationshipKind
import com.mohamed.storageanalyzer.core.entity.spi.EntityResourceResolver
import com.mohamed.storageanalyzer.core.identity.IdentityStability
import com.mohamed.storageanalyzer.core.identity.ResourceIdentity
import com.mohamed.storageanalyzer.core.identity.ResourceKind
import com.mohamed.storageanalyzer.core.identity.StorageSystemId
import com.mohamed.storageanalyzer.core.resource.ReferenceLink
import com.mohamed.storageanalyzer.core.resource.RelationType
import com.mohamed.storageanalyzer.core.resource.StorageCategory
import com.mohamed.storageanalyzer.core.resource.StorageResource
import com.mohamed.storageanalyzer.core.result.DiagnosticSeverity
import com.mohamed.storageanalyzer.core.result.StorageDiagnostic

/**
 * Streams the record resources backing rows of one table (entity layer).
 *
 * The descriptor must carry the `sqlite.table` hint (see
 * [SQLiteStorageProvider.HINT_TABLE]). When `descriptor.systemId` is set the
 * resolver uses that system; otherwise it scans the provider's systems for
 * the first one containing that table. Row enumeration is a plain streaming
 * `SELECT ... ORDER BY ...` that honors context cancellation.
 */
internal class SQLiteEntityResolver(private val provider: SQLiteStorageProvider) : EntityResourceResolver {

    override fun resourcesFor(descriptor: EntityDescriptor, ctx: AnalysisContext): Sequence<StorageResource> {
        val tableName = descriptor.hints[SQLiteStorageProvider.HINT_TABLE]
        if (tableName == null) {
            ctx.diagnostic(
                StorageDiagnostic(
                    code = "sqlite.descriptor_hint_missing",
                    severity = DiagnosticSeverity.WARNING,
                    message = "descriptor '${descriptor.entityTypeId}' lacks hint '${SQLiteStorageProvider.HINT_TABLE}'; no records enumerated",
                    providerId = provider.providerId,
                ),
            )
            return emptySequence()
        }
        val systemId = descriptor.systemId
            ?: provider.findSystemOfTable(tableName, ctx)
            ?: run {
                ctx.diagnostic(
                    StorageDiagnostic(
                        code = "sqlite.descriptor_table_missing",
                        severity = DiagnosticSeverity.WARNING,
                        message = "no registered SQLite system contains table '$tableName' for descriptor '${descriptor.entityTypeId}'",
                        providerId = provider.providerId,
                    ),
                )
                return emptySequence()
            }
        return provider.streamRecordResources(systemId, tableName, ctx)
    }
}

// ---------------------------------------------------------------- factories

/**
 * Ready-made, provider-bound relation factories for the SQLite record graph.
 * All resolvers execute read-only SELECTs against rows addressed by
 * their rowid and depend only on the source identity (same system implied).
 */
object SQLiteRelations {

    /**
     * Rows of [childTable] whose column [childColumn] equals the source row's
     * rowid — the reverse direction of a foreign key (parent → children).
     */
    fun reverseForeignKey(
        provider: SQLiteStorageProvider,
        name: String,
        kind: RelationshipKind = RelationshipKind.ONE_TO_MANY,
        childTable: String,
        childColumn: String,
    ): EntityRelation = EntityRelation(
        name = name,
        kind = kind,
        resolver = { source ->
            val sys = params(provider, source) ?: return@EntityRelation emptyList()
            val parsed = SQLiteResourceIds.parseRecordNative(source.nativeId) ?: return@EntityRelation emptyList()
            val (table, selector) = parsed
            if (selector !is SQLiteResourceIds.RowSelector.ByRowid) return@EntityRelation emptyList()
            val conn = provider.openedConnection(source.systemId) ?: return@EntityRelation emptyList()
            val rowids = try {
                conn.query(
                    "SELECT rowid FROM ${quote(childTable)} WHERE ${quote(childColumn)} = ?",
                    listOf<Any?>(selector.rowid),
                ) { c ->
                    val list = ArrayList<Long>()
                    while (c.next()) list.add(c.long(1))
                    list
                }
            } catch (e: java.sql.SQLException) {
                null
            } ?: emptyList<Long>()
            rowids.map { rowid ->
                ReferenceLink(
                    fromProviderId = provider.providerId,
                    toIdentity = SQLiteResourceIds.recordByRowid(source.systemId, sys.first, childTable, rowid, sys.second),
                    relation = RelationType.RELATED,
                    label = "$childTable.$childColumn = ${table}#${selector.rowid}",
                )
            }
        },
    )

    /**
     * Many-to-many traversal via a junction table:
     * `source → junction.selfColumn = source.rowid → otherTable.rowid`.
     */
    fun junction(
        provider: SQLiteStorageProvider,
        name: String,
        junctionTable: String,
        selfColumn: String,
        otherTable: String,
        otherColumn: String,
    ): EntityRelation = EntityRelation(
        name = name,
        kind = RelationshipKind.MANY_TO_MANY,
        sharedByDesign = true,
        resolver = { source ->
            val sys = params(provider, source) ?: return@EntityRelation emptyList()
            val parsed = SQLiteResourceIds.parseRecordNative(source.nativeId) ?: return@EntityRelation emptyList()
            if (parsed.second !is SQLiteResourceIds.RowSelector.ByRowid) return@EntityRelation emptyList()
            val selector = parsed.second as SQLiteResourceIds.RowSelector.ByRowid
            val conn = provider.openedConnection(source.systemId) ?: return@EntityRelation emptyList()
            val sql =
                "SELECT o.rowid FROM ${quote(junctionTable)} AS j JOIN ${quote(otherTable)} AS o " +
                    "ON j.${quote(otherColumn)} = o.rowid WHERE j.${quote(selfColumn)} = ?"
            val rowids = try {
                conn.query(sql, listOf<Any?>(selector.rowid)) { c ->
                    val list = ArrayList<Long>()
                    while (c.next()) list.add(c.long(1))
                    list
                }
            } catch (e: java.sql.SQLException) {
                null
            } ?: emptyList<Long>()
            rowids.map { rowid ->
                ReferenceLink(
                    fromProviderId = provider.providerId,
                    toIdentity = SQLiteResourceIds.recordByRowid(source.systemId, sys.first, otherTable, rowid, sys.second),
                    relation = RelationType.RELATED,
                    label = "$junctionTable($selfColumn,$otherColumn) → $otherTable#$rowid",
                )
            }
        },
    )

    /** Rows of [otherTable] matching `otherColumn = pk(sourceRecord)` → rowid table children. */
    fun childRecordsByRowid(
        provider: SQLiteStorageProvider,
        name: String,
        otherTable: String,
    ): EntityRelation = EntityRelation(
        name = name,
        kind = RelationshipKind.ASSOCIATION,
        resolver = { source ->
            val sys = params(provider, source) ?: return@EntityRelation emptyList()
            val parsed = SQLiteResourceIds.parseRecordNative(source.nativeId) ?: return@EntityRelation emptyList()
            val selector = parsed.second
            if (selector !is SQLiteResourceIds.RowSelector.ByRowid) return@EntityRelation emptyList()
            listOf(
                ReferenceLink(
                    fromProviderId = provider.providerId,
                    toIdentity = SQLiteResourceIds.recordByRowid(
                        source.systemId, sys.first, otherTable, selector.rowid, sys.second,
                    ),
                    relation = RelationType.RELATED,
                    label = name,
                ),
            )
        },
    )

    private fun params(
        provider: SQLiteStorageProvider,
        source: ResourceIdentity,
    ): Pair<String, IdentityStability>? = provider.sqlIdentityParams(source.systemId)

    private fun quote(name: String): String = "\"" + name.replace("\"", "\"\"") + "\""
}

// -------------------------------------------------------- descriptor helper

/**
 * Creates an [EntityDescriptor] for the records of one SQLite table. The
 * native id format matches the provider's records exactly (rowid addressing),
 * so per-row measurements resolve without any adapter.
 *
 * WITHOUT-ROWID tables need explicit pk addressing; use
 * [entityDescriptorForPkTable] for those.
 */
fun SQLiteStorageProvider.entityDescriptorForTable(
    entityTypeId: String,
    systemId: StorageSystemId,
    tableName: String,
    displayName: String? = null,
): EntityDescriptor? {
    val params = sqlIdentityParams(systemId) ?: return null
    val (namespace, stability) = params
    return EntityDescriptor(
        entityTypeId = entityTypeId,
        providerId = providerId,
        systemId = systemId,
        kind = ResourceKind.RECORD,
        displayName = displayName ?: tableName,
        nativeIdOf = { key: EntityInstanceKey ->
            val rowid = key.value.toLongOrNull()
                ?: throw IllegalArgumentException("rowid key '${key.value}' is not numeric for table '$tableName'")
            SQLiteResourceIds.recordByRowid(systemId, namespace, tableName, rowid, stability).nativeId
        },
        identityStability = stability,
        hints = mapOf(SQLiteStorageProvider.HINT_TABLE to tableName),
    )
}

/**
 * Entity descriptor for WITHOUT-ROWID tables; primary-key components are
 * resolved against the table's declared primary key order (obtained from the
 * catalog when the system is registered and openable).
 */
fun SQLiteStorageProvider.entityDescriptorForPkTable(
    entityTypeId: String,
    systemId: StorageSystemId,
    tableName: String,
    pkColumnCount: Int,
    displayName: String? = null,
): EntityDescriptor? {
    val params = sqlIdentityParams(systemId) ?: return null
    val (namespace, stability) = params
    return EntityDescriptor(
        entityTypeId = entityTypeId,
        providerId = providerId,
        systemId = systemId,
        kind = ResourceKind.RECORD,
        displayName = displayName ?: tableName,
        nativeIdOf = { key: EntityInstanceKey ->
            val parts = key.value.split('\u001F')
            require(parts.size == pkColumnCount) {
                "pk key for '$tableName' must have $pkColumnCount components separated by \\u001F"
            }
            SQLiteResourceIds.recordByPrimaryKey(systemId, namespace, tableName, parts, stability).nativeId
        },
        identityStability = stability,
        hints = mapOf(SQLiteStorageProvider.HINT_TABLE to tableName),
    )
}
