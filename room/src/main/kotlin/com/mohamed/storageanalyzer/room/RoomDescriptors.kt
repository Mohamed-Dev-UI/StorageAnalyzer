package com.mohamed.storageanalyzer.room

import com.mohamed.storageanalyzer.core.entity.EntityDescriptor
import com.mohamed.storageanalyzer.sqlite.SQLiteStorageProvider
import com.mohamed.storageanalyzer.sqlite.entityDescriptorForPkTable
import com.mohamed.storageanalyzer.sqlite.entityDescriptorForTable

/**
 * Maps Room entities to the library's [EntityDescriptor]s.
 *
 * This is the "no repetitive code" pivot: instead of hand-writing one
 * `entityDescriptorForTable` call per Room entity, descriptors are derived
 * from the schema bundle (or the live catalog) using the SQLite provider's
 * own descriptor helpers, so identity formats and stability never diverge.
 */
public object RoomDescriptors {

    /**
     * Stable entity type id for one entity: `room.<ClassName>` when a class
     * name is known, `room.<tableName>` otherwise.
     */
    public fun entityTypeIdFor(entity: RoomEntitySpec): String {
        val base = entity.entityClassSimpleName ?: entity.tableName
        return "room.$base"
    }

    /** Label used for report display: class short name when known, table name otherwise. */
    public fun displayNameFor(entity: RoomEntitySpec): String =
        entity.entityClassSimpleName ?: entity.tableName

    /**
     * Builds entity descriptors for every analyzable table.
     *
     * - With a [schemaBundle]: each non-framework bundle entity becomes a
     *   descriptor (rowid tables via [entityDescriptorForTable], WITHOUT
     *   ROWID tables via [entityDescriptorForPkTable]).
     * - Tables that exist live but not in the bundle are appended
     *   ([RoomEntitySource.LIVE_CATALOG] view — useful for drift inspection).
     * - With `schemaBundle = null`: [liveTableNames] drives generation
     *   (labels = table names); pass the live catalog here.
     */
    public fun entityDescriptors(
        schemaBundle: RoomSchemaBundle?,
        provider: SQLiteStorageProvider,
        liveTableNames: List<String> = emptyList(),
    ): List<EntityDescriptor> {
        val systemId = provider.systems().firstOrNull() ?: return emptyList()

        val specs = ArrayList<RoomEntitySpec>()
        val inBundle = HashSet<String>()
        if (schemaBundle != null) {
            for (entity in schemaBundle.entities) {
                if (entity.isFramework) continue
                inBundle += entity.tableName
                specs += entity
            }
        }
        for (name in liveTableNames) {
            if (name == RoomEntitySpec.FRAMEWORK_TABLE_MASTER || name == RoomEntitySpec.FRAMEWORK_TABLE_METADATA) continue
            if (name in inBundle) continue
            specs += RoomEntitySpec(
                tableName = name,
                entityClassSimpleName = null,
                withoutRowid = false,
                primaryKeyColumns = emptyList(),
                columnNames = emptyList(),
                indexNames = emptyList(),
                createSql = null,
            )
        }

        return specs.mapNotNull { entity ->
            val pkCount = entity.primaryKeyColumns.size
            if (entity.withoutRowid && pkCount > 0) {
                provider.entityDescriptorForPkTable(
                    entityTypeIdFor(entity), systemId, entity.tableName, pkCount,
                    displayNameFor(entity),
                )
            } else {
                provider.entityDescriptorForTable(
                    entityTypeIdFor(entity), systemId, entity.tableName,
                    displayNameFor(entity),
                )
            }
        }
    }
}
