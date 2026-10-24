package com.mohamed.dev.ui.storageanalyzer.ui.format

import com.mohamed.storageanalyzer.core.identity.ResourceKind
import com.mohamed.storageanalyzer.core.measurement.ByteCount
import com.mohamed.storageanalyzer.core.measurement.MeasurementState
import com.mohamed.storageanalyzer.core.measurement.MetricType
import com.mohamed.storageanalyzer.core.measurement.StorageUnits
import com.mohamed.storageanalyzer.core.resource.StorageCategory
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Presentation-only formatting. Measurements stay canonical bytes in the data layer. */
object Formats {

    private val dateTime = DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.getDefault())
    private val timeOnly = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.getDefault())

    fun bytes(value: ByteCount, decimals: Int = 1): String =
        StorageUnits.formatIec(value, decimals)

    fun bytes(value: Long, decimals: Int = 1): String =
        StorageUnits.formatIec(ByteCount.of(value.coerceAtLeast(0L)), decimals)

    fun signedBytes(delta: Long, decimals: Int = 1): String = when {
        delta > 0L -> "+" + bytes(delta, decimals)
        delta < 0L -> "-" + bytes(-delta, decimals)
        else -> "0 B"
    }

    fun signedBytes(value: Double, decimals: Int = 1): String =
        signedBytes(value.toLong(), decimals)

    fun percent(fraction: Double?, decimals: Int = 1): String =
        if (fraction == null) "—" else String.format(Locale.getDefault(), "%.${decimals}f%%", fraction * 100)

    fun percent(fraction: Float?, decimals: Int = 1): String =
        percent(fraction?.toDouble(), decimals)

    fun dateTime(instant: Instant?): String =
        instant?.atZone(ZoneId.systemDefault())?.format(dateTime) ?: "—"

    fun time(instant: Instant?): String =
        instant?.atZone(ZoneId.systemDefault())?.format(timeOnly) ?: "—"

    fun duration(duration: Duration): String = durationMs(duration.toMillis())

    fun durationMs(millis: Long): String = when {
        millis < 1_000 -> "$millis ms"
        millis < 60_000 -> String.format(Locale.getDefault(), "%.1f s", millis / 1000.0)
        else -> "${millis / 60_000} min ${(millis % 60_000) / 1_000} s"
    }

    fun metric(metric: MetricType): String = when (metric.id) {
        MetricType.STORED_CONTENT_SIZE.id -> "Stored content"
        MetricType.FILE_LOGICAL_SIZE.id -> "Logical file size"
        MetricType.FILE_ALLOCATED_SIZE.id -> "Allocated on disk"
        MetricType.ENCODED_CONTENT_SIZE.id -> "Encoded content"
        MetricType.LOGICAL_CONTENT_SIZE.id -> "Logical content"
        MetricType.TOTAL_CAPACITY.id -> "Total capacity"
        MetricType.FREE_CAPACITY.id -> "Free capacity"
        MetricType.RESERVED_CAPACITY.id -> "Reserved capacity"
        else -> humanize(metric.id, keepNamespace = true)
    }

    fun category(category: StorageCategory): String = when (category) {
        StorageCategory.APPLICATION_DATABASE -> "App database"
        StorageCategory.DATABASE -> "Database"
        StorageCategory.DOCUMENT -> "Documents"
        StorageCategory.MEDIA -> "Media"
        StorageCategory.ARCHIVE -> "Archives"
        StorageCategory.BACKUP -> "Backups"
        StorageCategory.CACHE -> "Cache"
        StorageCategory.TEMPORARY -> "Temporary"
        StorageCategory.LOGS -> "Logs"
        StorageCategory.EXECUTABLE -> "Executables"
        StorageCategory.SOURCE_CODE -> "Source code"
        StorageCategory.OTHER -> "Other"
    }

    fun kind(kind: ResourceKind): String = when (kind) {
        ResourceKind.DATABASE -> "Database"
        ResourceKind.TABLE -> "Table"
        ResourceKind.RECORD -> "Record"
        ResourceKind.INDEX -> "Index"
        ResourceKind.DOCUMENT -> "Document"
        ResourceKind.KEY_VALUE_ENTRY -> "Key-value entry"
        ResourceKind.OBJECT -> "Object"
        ResourceKind.FILE -> "File"
        ResourceKind.DIRECTORY -> "Directory"
        ResourceKind.BINARY_PAYLOAD -> "Binary payload"
        ResourceKind.SERIALIZED_OBJECT -> "Serialized object"
        ResourceKind.CACHE_ENTRY -> "Cache entry"
        ResourceKind.BACKUP -> "Backup"
        ResourceKind.ARCHIVE -> "Archive"
        ResourceKind.EXTERNAL_RESOURCE -> "External resource"
        ResourceKind.CUSTOM -> "Custom"
    }

    fun state(state: MeasurementState): String = when (state) {
        MeasurementState.MEASURED -> "Measured"
        MeasurementState.UNAVAILABLE -> "Unavailable"
        MeasurementState.NOT_APPLICABLE -> "Not applicable"
        MeasurementState.NOT_YET_MEASURED -> "Not yet measured"
    }

    /** Turns `COUNT_ONCE_GLOBALLY`, `x_archive_catalog.length` into readable text. */
    fun humanize(id: String, keepNamespace: Boolean = false): String {
        val cleaned = if (keepNamespace) id else id.substringAfterLast('.')
        return cleaned
            .replace('_', ' ')
            .replace('.', ' ')
            .replace('-', ' ')
            .split(' ')
            .filter { it.isNotBlank() }
            .joinToString(" ") { token ->
                token.replaceFirstChar { char -> char.titlecase(Locale.getDefault()) }
            }
    }

    fun digest(value: String, length: Int = 12): String =
        if (value.length <= length) value else value.take(length) + "…"

    fun truncateMiddle(value: String, max: Int = 48): String =
        if (value.length <= max) value else value.take(max / 2 - 1) + "…" + value.takeLast(max / 2 - 1)

    fun signedPercent(fraction: Double?): String = when {
        fraction == null -> "—"
        fraction > 0 -> "+" + percent(fraction)
        else -> percent(fraction)
    }
}
