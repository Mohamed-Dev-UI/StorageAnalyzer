package com.mohamed.storageanalyzer.history

import com.mohamed.storageanalyzer.core.identity.ProviderId
import com.mohamed.storageanalyzer.core.measurement.ByteCount
import com.mohamed.storageanalyzer.core.measurement.MetricDimension
import com.mohamed.storageanalyzer.core.measurement.MetricType
import com.mohamed.storageanalyzer.core.resource.StorageCategory
import com.mohamed.storageanalyzer.core.snapshot.StorageSnapshot
import java.time.Instant

/**
 * Canonical text format for [StorageSnapshot] persistence.
 *
 * Line-oriented "scalar header first, then map entries" format with
 * percent-escaped map keys (keys are stableKeys or identifiers and may contain
 * any UTF-8 character, including `=` and digits):
 *
 * ```
 * #storageanalyzer-snapshot-v1
 * capturedAt=2026-10-24T12:00:00Z
 * footprintMetric=stored_content_size|STORED
 * footprintTotal=123
 * resourceCount=2
 * completenessLabel=COMPLETE
 * byMetric=stored_content_size|STORED=123
 * byResource=C%3A%2Fdb.sqlite%7Ct%3Ausers=80
 * ```
 *
 * Metrics are stored as `<id>|<dimension>` so provider-custom metrics
 * (`MetricType.custom`) round-trip exactly. The codec is strictly versioned
 * (`-v1`); unknown keys or malformed numbers fail immediately — stored
 * analytics data is never silently approximated or skipped.
 */
internal object SnapshotFormat {

    private const val HEADER = "#storageanalyzer-snapshot-v1"

    internal val headerLine: String get() = HEADER

    private val SAFE = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_.:- ".toSet()

    /** Percent-encodes everything outside [SAFE] as UTF-8 `%HH` (uppercase) bytes. */
    internal fun encodeKey(raw: String): String {
        if (raw.all { it in SAFE }) return raw
        val out = StringBuilder()
        for (ch in raw) {
            if (ch in SAFE) {
                out.append(ch)
            } else {
                for (b in ch.toString().encodeToByteArray()) {
                    out.append('%')
                    val two = (b.toInt() and 0xFF).toString(16)
                    if (two.length == 1) out.append('0')
                    out.append(two)
                }
            }
        }
        return out.toString()
    }

    /** Inverse of [encodeKey]; fails loudly on truncated or malformed escapes. */
    internal fun decodeKey(encoded: String): String {
        val out = StringBuilder()
        var i = 0
        val bytes = ArrayList<Byte>()
        while (i < encoded.length) {
            val ch = encoded[i]
            if (ch == '%') {
                if (i + 3 > encoded.length) {
                    throw IllegalArgumentException("truncated %-escape in snapshot key at index $i: '$encoded'")
                }
                val hex = encoded.substring(i + 1, i + 3)
                val b = hex.toIntOrNull(16)
                    ?: throw IllegalArgumentException("malformed %-escape '$hex' in snapshot key: '$encoded'")
                bytes.add(b.toByte())
                i += 3
            } else {
                if (bytes.isNotEmpty()) {
                    out.append(bytes.toByteArray().decodeToString())
                    bytes.clear()
                }
                out.append(ch)
                i++
            }
        }
        if (bytes.isNotEmpty()) out.append(bytes.toByteArray().decodeToString())
        return out.toString()
    }

    fun encode(snapshot: StorageSnapshot): String = buildString {
        append(HEADER).append('\n')
        append("capturedAt=").append(snapshot.capturedAt.toString()).append('\n')
        append("footprintMetric=").append(encodeMetric(snapshot.footprintMetric)).append('\n')
        append("footprintTotal=").append(snapshot.footprintTotal.value).append('\n')
        append("resourceCount=").append(snapshot.resourceCount).append('\n')
        append("completenessLabel=").append(encodeKey(snapshot.completenessLabel)).append('\n')
        snapshot.byMetric.forEach { (m, v) -> append("byMetric=").append(encodeMetric(m)).append('=').append(v.value).append('\n') }
        snapshot.byProvider.forEach { (p, v) -> append("byProvider=").append(encodeKey(p.value)).append('=').append(v.value).append('\n') }
        snapshot.byCategory.forEach { (c, v) -> append("byCategory=").append(encodeKey(c.name)).append('=').append(v.value).append('\n') }
        snapshot.byResource.forEach { (k, v) -> append("byResource=").append(encodeKey(k)).append('=').append(v.value).append('\n') }
    }

    fun decode(text: String): StorageSnapshot {
        val dataLines = text.split('\n')
            .map { it.trimEnd('\r') }
            .filter { it.isNotEmpty() }
        require(dataLines.isNotEmpty()) { "snapshot document is empty" }
        require(dataLines[0] == HEADER) {
            "unrecognized snapshot header '${dataLines[0]}' (expected '$HEADER'); refusing to guess the meaning of stored data"
        }

        var capturedAt: Instant? = null
        var footprintMetric: MetricType? = null
        var footprintTotal: Long? = null
        var resourceCount: Long? = null
        var completenessLabel: String? = null
        val byMetric = LinkedHashMap<MetricType, ByteCount>()
        val byProvider = LinkedHashMap<ProviderId, ByteCount>()
        val byCategory = LinkedHashMap<StorageCategory, ByteCount>()
        val byResource = LinkedHashMap<String, ByteCount>()

        for ((i, line) in dataLines.withIndex()) {
            if (i == 0) continue // header
            val eq = line.indexOf('=')
            require(eq > 0) { "malformed snapshot line ${i + 1}: '$line'" }
            val key = line.substring(0, eq)
            val value = line.substring(eq + 1)
            when (key) {
                "capturedAt" -> capturedAt = Instant.parse(value)
                "footprintMetric" -> footprintMetric = decodeMetric(value)
                "footprintTotal" -> footprintTotal = requiredNonNegativeLong(value, "footprintTotal")
                "resourceCount" -> resourceCount = requiredNonNegativeLong(value, "resourceCount")
                "completenessLabel" -> completenessLabel = decodeKey(value)
                "byMetric" -> {
                    val (name, bytes) = mapEntry(value, line)
                    byMetric[decodeMetric(name)] = ByteCount(bytes)
                }
                "byProvider" -> {
                    val (name, bytes) = mapEntry(value, line)
                    byProvider[ProviderId(decodeKey(name))] = ByteCount(bytes)
                }
                "byCategory" -> {
                    val (name, bytes) = mapEntry(value, line)
                    byCategory[decodeCategory(name)] = ByteCount(bytes)
                }
                "byResource" -> {
                    val (name, bytes) = mapEntry(value, line)
                    byResource[decodeKey(name)] = ByteCount(bytes)
                }
                else -> throw IllegalArgumentException("unknown snapshot field '$key' in line ${i + 1} (codec v1 is exact; the file may come from a newer version)")
            }
        }

        return StorageSnapshot(
            capturedAt = capturedAt ?: throw IllegalArgumentException("snapshot is missing 'capturedAt'"),
            footprintMetric = footprintMetric ?: throw IllegalArgumentException("snapshot is missing 'footprintMetric'"),
            footprintTotal = ByteCount(footprintTotal ?: throw IllegalArgumentException("snapshot is missing 'footprintTotal'")),
            byMetric = byMetric,
            byProvider = byProvider,
            byCategory = byCategory,
            byResource = byResource,
            resourceCount = resourceCount ?: throw IllegalArgumentException("snapshot is missing 'resourceCount'"),
            completenessLabel = completenessLabel ?: throw IllegalArgumentException("snapshot is missing 'completenessLabel'"),
        )
    }

    /**
     * Splits a `encodedKey=<long>` payload at its last '=' (encoded keys never
     * contain a literal '='; it always appears as `%3D`). [line] is quoted in
     * error messages.
     */
    private fun mapEntry(payload: String, line: String): Pair<String, Long> {
        val eq = payload.lastIndexOf('=')
        require(eq > 0) { "malformed map entry in snapshot line '$line'" }
        val keyPart = payload.substring(0, eq)
        val numPart = payload.substring(eq + 1)
        return keyPart to requiredNonNegativeLong(numPart, "snapshot map entry ('$line')")
    }

    private fun requiredNonNegativeLong(value: String, field: String): Long {
        val parsed = value.toLongOrNull()
            ?: throw IllegalArgumentException("snapshot field $field must be an integer (was '$value')")
        require(parsed >= 0) { "snapshot field $field must be non-negative (was $parsed)" }
        return parsed
    }

    /** Metric wire form: `<encoded id>|<dimension>`; parts are escaped, not the separator. */
    private fun encodeMetric(metric: MetricType): String = encodeKey(metric.id) + "|" + metric.dimension.name

    private fun decodeMetric(wire: String): MetricType {
        val bar = wire.lastIndexOf('|')
        require(bar > 0) { "malformed metric tag '$wire' (expected '<id>|<dimension>')" }
        val id = decodeKey(wire.substring(0, bar))
        val dimensionName = decodeKey(wire.substring(bar + 1))
        // Built-ins must match the frozen constant; custom ones are recreated.
        MetricType.fromId(id)?.let { builtin ->
            require(builtin.dimension.name == dimensionName) {
                "metric '$id' in snapshot declares dimension '$dimensionName' but the built-in constant is '${builtin.dimension.name}'"
            }
            return builtin
        }
        val dimension = MetricDimension.entries.firstOrNull { it.name == dimensionName }
            ?: throw IllegalArgumentException("unknown MetricDimension '$dimensionName' for metric '$id'")
        return try {
            MetricType.custom(id, dimension)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("invalid custom metric '$id' in snapshot: ${e.message}")
        }
    }

    private fun decodeCategory(name: String): StorageCategory {
        val decoded = decodeKey(name)
        return StorageCategory.entries.firstOrNull { it.name == decoded }
            ?: throw IllegalArgumentException("unknown StorageCategory '$decoded' in snapshot")
    }
}
