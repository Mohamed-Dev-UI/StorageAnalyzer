package com.mohamed.dev.ui.storageanalyzer.data.local

import com.mohamed.storageanalyzer.core.identity.ProviderId
import com.mohamed.storageanalyzer.core.measurement.ByteCount
import com.mohamed.storageanalyzer.core.measurement.MetricDimension
import com.mohamed.storageanalyzer.core.measurement.MetricType
import com.mohamed.storageanalyzer.core.resource.StorageCategory
import com.mohamed.storageanalyzer.core.snapshot.StorageSnapshot
import java.time.Instant
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Line-oriented snapshot serialization owned by the app.
 *
 * `:history` keeps its own format internal, and `FileSnapshotStore` relies on
 * JDK APIs that are not safe at this app's minSdk. Encoding the public
 * [StorageSnapshot] model here keeps Room persistence explicit and lets the
 * file be inspected with logcat/adb. Corruption fails loudly with the line
 * number, matching the library's SnapshotStore contract.
 */
@Singleton
class SnapshotCodec @Inject constructor() {

    fun encode(snapshot: StorageSnapshot): String = buildString {
        append(HEADER).append('\n')
        append("capturedAt=").append(snapshot.capturedAt).append('\n')
        append("footprintMetric=").append(encodeMetric(snapshot.footprintMetric)).append('\n')
        append("footprintTotal=").append(snapshot.footprintTotal.value).append('\n')
        append("resourceCount=").append(snapshot.resourceCount).append('\n')
        append("completeness=").append(snapshot.completenessLabel).append('\n')
        snapshot.byMetric.entries.sortedBy { it.key.id }.forEach { (metric, bytes) ->
            append("metric=").append(encodeMetric(metric)).append('|').append(bytes.value).append('\n')
        }
        snapshot.byProvider.entries.sortedBy { it.key.value }.forEach { (provider, bytes) ->
            append("provider=").append(encodeText(provider.value)).append('|').append(bytes.value).append('\n')
        }
        snapshot.byCategory.entries.sortedBy { it.key.name }.forEach { (category, bytes) ->
            append("category=").append(category.name).append('|').append(bytes.value).append('\n')
        }
        snapshot.byResource.entries.sortedBy { it.key }.forEach { (key, bytes) ->
            append("resource=").append(encodeText(key)).append('|').append(bytes.value).append('\n')
        }
    }

    fun decode(payload: String): StorageSnapshot {
        val lines = payload.lineSequence().toList()
        if (lines.isEmpty() || lines.first() != HEADER) {
            throw IllegalArgumentException("corrupt snapshot payload: missing '$HEADER' header")
        }

        var capturedAt: Instant? = null
        var footprintMetric: MetricType? = null
        var footprintTotal: ByteCount? = null
        var resourceCount: Long? = null
        var completeness: String? = null
        val byMetric = LinkedHashMap<MetricType, ByteCount>()
        val byProvider = LinkedHashMap<ProviderId, ByteCount>()
        val byCategory = LinkedHashMap<StorageCategory, ByteCount>()
        val byResource = LinkedHashMap<String, ByteCount>()

        for (index in 1 until lines.size) {
            val line = lines[index]
            if (line.isBlank()) continue
            val lineNumber = index + 1
            try {
                val separator = line.indexOf('=')
                require(separator > 0) { "expected key=value" }
                val key = line.substring(0, separator)
                val value = line.substring(separator + 1)
                when (key) {
                    "capturedAt" -> capturedAt = Instant.parse(value)
                    "footprintMetric" -> footprintMetric = decodeMetric(value)
                    "footprintTotal" -> footprintTotal = ByteCount(value.toLong())
                    "resourceCount" -> resourceCount = value.toLong()
                    "completeness" -> completeness = value
                    "metric" -> {
                        // The encoded metric itself is `id|dimension`, so split
                        // on the last separator to leave the payload intact.
                        val separator = value.lastIndexOf('|')
                        require(separator > 0) { "metric line needs metric|bytes" }
                        byMetric[decodeMetric(value.substring(0, separator))] =
                            ByteCount(value.substring(separator + 1).toLong())
                    }
                    "provider" -> {
                        val parts = value.split('|')
                        require(parts.size == 2) { "provider line needs provider|bytes" }
                        byProvider[ProviderId(decodeText(parts[0]))] = ByteCount(parts[1].toLong())
                    }
                    "category" -> {
                        val parts = value.split('|')
                        require(parts.size == 2) { "category line needs category|bytes" }
                        byCategory[StorageCategory.valueOf(parts[0])] = ByteCount(parts[1].toLong())
                    }
                    "resource" -> {
                        val parts = value.split('|')
                        require(parts.size == 2) { "resource line needs key|bytes" }
                        byResource[decodeText(parts[0])] = ByteCount(parts[1].toLong())
                    }
                    else -> error("unknown field '$key'")
                }
            } catch (e: Exception) {
                throw IllegalArgumentException("corrupt snapshot payload at line $lineNumber: ${e.message}", e)
            }
        }

        return StorageSnapshot(
            capturedAt = capturedAt ?: throw IllegalArgumentException("corrupt snapshot payload: capturedAt missing"),
            footprintMetric = footprintMetric ?: throw IllegalArgumentException("corrupt snapshot payload: footprintMetric missing"),
            footprintTotal = footprintTotal ?: throw IllegalArgumentException("corrupt snapshot payload: footprintTotal missing"),
            byMetric = byMetric,
            byProvider = byProvider,
            byCategory = byCategory,
            byResource = byResource,
            resourceCount = resourceCount ?: throw IllegalArgumentException("corrupt snapshot payload: resourceCount missing"),
            completenessLabel = completeness ?: throw IllegalArgumentException("corrupt snapshot payload: completeness missing"),
        )
    }

    private fun encodeMetric(metric: MetricType): String =
        encodeText(metric.id) + "|" + metric.dimension.name

    private fun decodeMetric(encoded: String): MetricType {
        val parts = encoded.split('|')
        require(parts.size == 2) { "metric field needs encodedId|dimension" }
        val id = decodeText(parts[0])
        val dimension = MetricDimension.valueOf(parts[1])
        return MetricType.fromId(id) ?: MetricType.custom(id, dimension)
    }

    private fun encodeText(text: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray(Charsets.UTF_8))

    private fun decodeText(encoded: String): String {
        val padded = encoded + "=".repeat((4 - encoded.length % 4) % 4)
        return String(Base64.getUrlDecoder().decode(padded), Charsets.UTF_8)
    }

    private companion object {
        const val HEADER = "storageanalyzer.snapshot/1"
    }
}
