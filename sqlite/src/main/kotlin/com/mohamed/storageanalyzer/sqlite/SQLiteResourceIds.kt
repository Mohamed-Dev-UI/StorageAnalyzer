package com.mohamed.storageanalyzer.sqlite

import com.mohamed.storageanalyzer.core.identity.IdentityStability
import com.mohamed.storageanalyzer.core.identity.ProviderId
import com.mohamed.storageanalyzer.core.identity.ResourceIdentity
import com.mohamed.storageanalyzer.core.identity.ResourceKind
import com.mohamed.storageanalyzer.core.identity.StorageSystemId
import java.io.File

/**
 * Native-id codecs and identity factories of the SQLite provider.
 *
 * Identity scheme (kept stable across releases):
 * - `systemId`: canonical path of the **parent directory** of the database
 *   file (matching the filesystem provider scheme for cross-provider dedup),
 *   or `memory#<n>` for `:memory:` databases, or the opaque JDBC URI.
 * - `namespace`: for SQL-level resources (db/table/index/record) the encoded
 *   base file name of the database, so two databases in one directory never
 *   collide. Sidecar file resources instead use namespace `sidecar-<role>`
 *   with the file base name as nativeId.
 * - `nativeId` prefixes: `db`, `t:<table>`, `i:<name>`,
 *   `r:<table>:<rowid>` / `r:<table>:p:<pk>`.
 *
 * Encoders keep `[A-Za-z0-9_.~-]` and percent-encode everything else
 * (uppercase hex), including the separator `:` — decoding is unambiguous.
 */
object SQLiteResourceIds {

    const val NAMESPACE_MEMORY = "memory"

    const val nativeDatabase = "db"

    // ------------------------------------------------------------- encoding

    /** Percent-encodes a component so `:` and `%` never appear literally. */
    fun encodeComponent(raw: String): String {
        val sb = StringBuilder(raw.length)
        for (b in raw.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt().toChar()
            if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_' || c == '.' || c == '-' || c == '~') {
                sb.append(c)
            } else {
                sb.append('%').append(HEX[(b.toInt() shr 4) and 0xF]).append(HEX[b.toInt() and 0xF])
            }
        }
        return sb.toString()
    }

    private val HEX = "0123456789ABCDEF".toCharArray()

    /** Inverse of [encodeComponent]. */
    fun decodeComponent(enc: String): String {
        if (!enc.contains('%')) return enc
        val bytes = ByteArray(enc.length)
        var n = 0
        var i = 0
        while (i < enc.length) {
            val c = enc[i]
            if (c == '%' && i + 2 < enc.length) {
                val hi = Character.digit(enc[i + 1], 16)
                val lo = Character.digit(enc[i + 2], 16)
                if (hi >= 0 && lo >= 0) {
                    bytes[n++] = ((hi shl 4) or lo).toByte()
                    i += 3
                    continue
                }
            }
            bytes[n++] = c.code.toByte()
            i++
        }
        return String(bytes, 0, n, Charsets.UTF_8)
    }

    // ------------------------------------------------------ system identity

    /**
     * The system id of one database. File databases key on the canonical
     * parent directory (cross-provider dedup scheme); `:memory:` systems get
     * an opaquely-numbered pseudo system (EPHEMERAL); JDBC URIs key on
     * themselves.
     */
    fun systemIdOf(spec: SQLiteDatabaseSpec, memoryOrdinal: Int): StorageSystemId = when {
        spec.path == ":memory:" -> StorageSystemId("memory#$memoryOrdinal")
        spec.path.startsWith("jdbc:") || spec.path.startsWith("file:") -> StorageSystemId(spec.path)
        else -> {
            val canonicalFile = File(spec.path).canonicalFile
            val parent = canonicalFile.parent ?: "."
            StorageSystemId(parent)
        }
    }

    /** Namespace shared by all SQL-level identities of one database. */
    fun namespaceOf(spec: SQLiteDatabaseSpec): String = when {
        spec.path == ":memory:" -> NAMESPACE_MEMORY
        spec.path.startsWith("jdbc:") || spec.path.startsWith("file:") -> encodeComponent(spec.path)
        else -> encodeComponent(File(spec.path).name)
    }

    /** Stability of resources in that system (memory-backed = EPHEMERAL). */
    fun stabilityOf(spec: SQLiteDatabaseSpec): IdentityStability =
        if (spec.path == ":memory:") IdentityStability.EPHEMERAL else IdentityStability.STABLE

    // ----------------------------------------------------- identity factories

    private fun sqlIdentity(
        systemId: StorageSystemId,
        namespace: String,
        kind: ResourceKind,
        nativeId: String,
        stability: IdentityStability,
    ): ResourceIdentity = ResourceIdentity(
        providerId = ProviderId(SQLiteStorageProvider.providerIdValue),
        systemId = systemId,
        kind = kind,
        namespace = namespace,
        nativeId = nativeId,
        stability = stability,
    )

    fun database(systemId: StorageSystemId, namespace: String, stability: IdentityStability): ResourceIdentity =
        sqlIdentity(systemId, namespace, ResourceKind.DATABASE, nativeDatabase, stability)

    fun table(systemId: StorageSystemId, namespace: String, tableName: String, stability: IdentityStability): ResourceIdentity =
        sqlIdentity(systemId, namespace, ResourceKind.TABLE, "t:" + encodeComponent(tableName), stability)

    fun index(systemId: StorageSystemId, namespace: String, indexName: String, stability: IdentityStability): ResourceIdentity =
        sqlIdentity(systemId, namespace, ResourceKind.INDEX, "i:" + encodeComponent(indexName), stability)

    /** Record of a rowid-table row. */
    fun recordByRowid(
        systemId: StorageSystemId,
        namespace: String,
        tableName: String,
        rowid: Long,
        stability: IdentityStability,
    ): ResourceIdentity =
        sqlIdentity(systemId, namespace, ResourceKind.RECORD, "r:" + encodeComponent(tableName) + ":" + rowid, stability)

    /**
     * Record of a WITHOUT ROWID row. The id embeds the component count
     * (`p:<n>:<key>`, key = components joined with 0x1F) so separators inside
     * values cannot corrupt the split on decode.
     */
    fun recordByPrimaryKey(
        systemId: StorageSystemId,
        namespace: String,
        tableName: String,
        pkComponents: List<String>,
        stability: IdentityStability,
    ): ResourceIdentity {
        val key = pkComponents.joinToString("\u001F")
        return sqlIdentity(
            systemId, namespace, ResourceKind.RECORD,
            "r:" + encodeComponent(tableName) + ":p:" + pkComponents.size + ":" + encodeComponent(key),
            stability,
        )
    }

    // ------------------------------------------------------------ decoding

    /** How one row is addressed inside a table. */
    sealed class RowSelector {
        /** Rowid-table row (rowids may be negative). */
        data class ByRowid(val rowid: Long) : RowSelector()

        /** WITHOUT-ROWID row; primary-key components in declared order. */
        data class ByPrimaryKey(val components: List<String>) : RowSelector()
    }

    /**
     * Parses an `r:` record nativeId into `(tableName, selector)`. WITHOUT
     * ROWID ids carry the component count before the encoded joined key
     * (`p:<n>:<key>`), so separator characters inside key values cannot break
     * the split. `null` for shapes belonging to other resources.
     */
    fun parseRecordNative(nativeId: String): Pair<String, RowSelector>? {
        if (!nativeId.startsWith("r:")) return null
        val body = nativeId.substring(2)
        // The encoded table part never contains ':' (encoding removes it).
        val sep = body.indexOf(':')
        if (sep <= 0) return null
        val table = decodeComponent(body.substring(0, sep))
        val tail = body.substring(sep + 1)
        return when {
            tail.startsWith("p:") -> {
                val rest = tail.substring(2)
                val countSep = rest.indexOf(':')
                if (countSep <= 0) return null
                val count = rest.substring(0, countSep).toIntOrNull() ?: return null
                if (count <= 0) return null
                val components = decodeComponent(rest.substring(countSep + 1)).split("\u001F")
                if (components.size != count) return null
                table to RowSelector.ByPrimaryKey(components)
            }
            else -> tail.toLongOrNull()?.let { table to RowSelector.ByRowid(it) }
        }
    }

    /** Table name from a `t:` table nativeId; null for foreign shapes. */
    fun decodeTableName(nativeId: String): String? =
        if (nativeId.startsWith("t:")) decodeComponent(nativeId.substring(2)) else null

    /** Index name from an `i:` index nativeId; null for foreign shapes. */
    fun decodeIndexName(nativeId: String): String? =
        if (nativeId.startsWith("i:")) decodeComponent(nativeId.substring(2)) else null

    /** True for table identities of this provider. */
    val ResourceIdentity.isSqliteTable: Boolean get() = kind == ResourceKind.TABLE && nativeId.startsWith("t:")

    val ResourceIdentity.isSqliteIndex: Boolean get() = kind == ResourceKind.INDEX && nativeId.startsWith("i:")

    val ResourceIdentity.isSqliteRecord: Boolean get() = kind == ResourceKind.RECORD && nativeId.startsWith("r:")

    /** SQLite provider id as a [ProviderId]. */
    fun sqliteProvider(): ProviderId = ProviderId(SQLiteStorageProvider.providerIdValue)
}
