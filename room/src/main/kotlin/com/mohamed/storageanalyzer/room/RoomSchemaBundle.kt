package com.mohamed.storageanalyzer.room

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.contentOrNull
import java.nio.file.Files
import java.nio.file.Path

/**
 * One column declaration of a Room schema bundle entity. Kept for consumers
 * that want field details; descriptor generation itself needs only the
 * [RoomEntitySpec] level view.
 */
public data class RoomColumnSpec(
    public val columnName: String,
    public val affinity: String?,
    public val notNull: Boolean,
    public val primaryKeyOrdinal: Int?,
    public val defaultValue: String?,
)

/**
 * One logical Room entity as declared by a room schema bundle (or derived
 * from the live catalog when no bundle is supplied).
 */
public data class RoomEntitySpec(
    public val tableName: String,
    /** e.g. `"User"`; null when the bundle does not carry a class name. */
    public val entityClassSimpleName: String?,
    public val withoutRowid: Boolean,
    /** Declared primary-key columns, in pk order (empty when unknown). */
    public val primaryKeyColumns: List<String>,
    public val columnNames: List<String>,
    /** Index names explicitly declared by the bundle (empty from the live catalog). */
    public val indexNames: List<String>,
    public val createSql: String?,
) {

    /** Room bookkeeping tables that exist in every database; they are not entities. */
    public val isFramework: Boolean
        get() = tableName == FRAMEWORK_TABLE_MASTER || tableName == FRAMEWORK_TABLE_METADATA

    public companion object {
        public const val FRAMEWORK_TABLE_MASTER: String = "room_master_table"
        public const val FRAMEWORK_TABLE_METADATA: String = "android_metadata"
        public const val MASTER_IDENTITY_COLUMN: String = "identity_hash"
    }
}

/**
 * A parsed Room schema bundle (`<DatabaseClass>/<n>.json`, produced when
 * Room's `exportSchema = true` is set — the default for every Room project).
 *
 * The parse is lenient: unknown keys are ignored, deviations that matter are
 * errors. Required entity keys: `tableName` and (per field) `columnName`.
 * The identity-related knowledge of this class is exactly what the analytics
 * needs; it intentionally knows nothing about @Entity annotations (which are
 * NOT retention-visible at runtime) and has zero androidx dependencies.
 */
public data class RoomSchemaBundle(
    public val version: Int,
    public val identityHash: String?,
    public val entities: List<RoomEntitySpec>,
    public val sourcePath: String? = null,
) {

    public fun entityNamed(tableName: String): RoomEntitySpec? =
        entities.firstOrNull { it.tableName == tableName }

    public companion object {

        /** Parses a schema bundle file at [path] (file name may carry the version, `<n>.json`). */
        public fun parse(path: Path): RoomSchemaBundle {
            val text = Files.readString(path, Charsets.UTF_8)
            val fileNameVersion = path.fileName?.toString()?.removeSuffix(".json")?.toIntOrNull()
            return parse(text, fileNameVersion = fileNameVersion).copy(sourcePath = path.toString())
        }

        /**
         * Parses a schema bundle from its JSON text. [fileNameVersion] is the
         * fallback version hint from the file name when the JSON lacks one.
         */
        public fun parse(json: String, fileNameVersion: Int? = null): RoomSchemaBundle {
            val root = Json.parseToJsonElement(json)
            val rootObj = root as? JsonObject
                ?: throw IllegalArgumentException("room schema bundle must be a JSON object")
            // Modern Room exports wrap the data in a `database` object; older
            // shapes put the keys at the root. Accept both.
            val db = (rootObj["database"] as? JsonObject) ?: rootObj

            val version = db.intAt("version")
                ?: rootObj.intAt("version")
                ?: fileNameVersion
                ?: throw IllegalArgumentException(
                    "room schema bundle has no schema version (no 'version' key and no '<n>.json' file name)",
                )

            val identityHash = db.strAt("identityHash") ?: rootObj.strAt("identityHash")

            val entitiesElement = (db["entities"] as? JsonArray)
                ?: (rootObj["entities"] as? JsonArray)
                ?: JsonArray(emptyList())
            val entities = entitiesElement.mapIndexed { idx, element ->
                val obj = element as? JsonObject
                    ?: throw IllegalArgumentException("schema bundle entity #$idx is not an object")
                try {
                    entityOf(obj)
                } catch (e: IllegalArgumentException) {
                    throw IllegalArgumentException("schema bundle entity #$idx: ${e.message}", e)
                }
            }
            return RoomSchemaBundle(version = version, identityHash = identityHash, entities = entities)
        }

        private fun entityOf(obj: JsonObject): RoomEntitySpec {
            val tableName = obj.strAt("tableName")
                ?: throw IllegalArgumentException("missing required key 'tableName'")

            var pkColumns: List<String> = (obj["primaryKey"] as? JsonArray)?.mapNotNull { el ->
                (el as? JsonPrimitive)?.contentOrNull
            } ?: emptyList()

            val columns = ArrayList<RoomColumnSpec>()
            (obj["fields"] as? JsonArray)?.forEach { field ->
                val fieldObj = field as? JsonObject
                    ?: throw IllegalArgumentException("field of table '$tableName' is not an object")
                val columnName = fieldObj.strAt("columnName")
                    ?: throw IllegalArgumentException("field of table '$tableName' lacks 'columnName'")
                columns += RoomColumnSpec(
                    columnName = columnName,
                    affinity = fieldObj.strAt("affinity"),
                    notNull = fieldObj.boolAt("notNull") ?: false,
                    primaryKeyOrdinal = fieldObj.intAt("primaryKeyOrdinal"),
                    defaultValue = fieldObj.strAt("defaultValue"),
                )
            }
            // Explicit ordinals are more authoritative than the pk array order
            // (they come from the field declarations themselves).
            val ordinalColumns = columns
                .filter { it.primaryKeyOrdinal != null }
                .sortedBy { it.primaryKeyOrdinal }
                .map { it.columnName }
            if (ordinalColumns.isNotEmpty()) pkColumns = ordinalColumns

            val createSql = obj.strAt("createSql")
            val indexNames = (obj["indices"] as? JsonArray)?.mapNotNull { el ->
                (el as? JsonObject)?.strAt("name")
            } ?: emptyList()

            val className = obj.strAt("className") ?: obj.strAt("entityClassName")

            return RoomEntitySpec(
                tableName = tableName,
                entityClassSimpleName = className?.substringAfterLast('.')?.takeIf { it.isNotBlank() },
                withoutRowid = createSql?.uppercase()?.contains("WITHOUT ROWID") == true,
                primaryKeyColumns = pkColumns,
                columnNames = columns.map { it.columnName },
                indexNames = indexNames,
                createSql = createSql,
            )
        }

        private fun JsonObject.strAt(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
        private fun JsonObject.intAt(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
        private fun JsonObject.boolAt(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
    }
}
