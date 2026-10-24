package com.mohamed.dev.ui.storageanalyzer.data.local

import com.mohamed.storageanalyzer.core.identity.ProviderId
import com.mohamed.storageanalyzer.core.measurement.ByteCount
import com.mohamed.storageanalyzer.core.measurement.MetricDimension
import com.mohamed.storageanalyzer.core.measurement.MetricType
import com.mohamed.storageanalyzer.core.resource.StorageCategory
import com.mohamed.storageanalyzer.core.snapshot.StorageSnapshot
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The app-owned codec is the only thing between the library's public
 * [StorageSnapshot] and Room, so round-tripping and loud corruption reporting
 * are both part of the showcase's contract.
 */
class SnapshotCodecTest {

    private val codec = SnapshotCodec()

    private val capturedAt: Instant = Instant.parse("2026-10-24T10:15:30Z")

    private val customMetric = MetricType.custom("x_showcase.payload", MetricDimension.ENCODED)

    private fun snapshot(): StorageSnapshot = StorageSnapshot(
        capturedAt = capturedAt,
        footprintMetric = MetricType.STORED_CONTENT_SIZE,
        footprintTotal = ByteCount.of(4096),
        byMetric = linkedMapOf(
            MetricType.STORED_CONTENT_SIZE to ByteCount.of(4096),
            customMetric to ByteCount.of(12),
        ),
        byProvider = linkedMapOf(
            ProviderId("filesystem") to ByteCount.of(3000),
            ProviderId("x_showcase_prefs") to ByteCount.of(1096),
        ),
        byCategory = linkedMapOf(
            StorageCategory.DOCUMENT to ByteCount.of(2048),
            StorageCategory.CACHE to ByteCount.of(2048),
        ),
        byResource = linkedMapOf(
            "filesystem|demo/report.txt|file" to ByteCount.of(2048),
            "unicode-\u00fc|a=b" to ByteCount.of(2048),
        ),
        resourceCount = 7,
        completenessLabel = "COMPLETE",
    )

    @Test
    fun `round trip preserves every field including custom metrics and unicode keys`() {
        val original = snapshot()

        val decoded = codec.decode(codec.encode(original))

        assertEquals(original, decoded)
        assertEquals(customMetric, decoded.byMetric.keys.first { it.id == customMetric.id })
    }

    @Test
    fun `missing header fails with the header name`() {
        val error = assertFailsWith<IllegalArgumentException> {
            codec.decode("capturedAt=2026-10-24T10:15:30Z")
        }

        assertTrue(error.message.orEmpty().contains("storageanalyzer.snapshot/1"))
    }

    @Test
    fun `corrupt line reports its one-based line number`() {
        val error = assertFailsWith<IllegalArgumentException> {
            codec.decode(
                """
                storageanalyzer.snapshot/1
                capturedAt=not-an-instant
                """.trimIndent(),
            )
        }

        assertTrue(error.message.orEmpty().contains("line 2"), error.message)
    }

    @Test
    fun `unknown fields and incomplete payloads are rejected, never defaulted`() {
        val unknownField = assertFailsWith<IllegalArgumentException> {
            codec.decode(
                """
                storageanalyzer.snapshot/1
                capturedAt=2026-10-24T10:15:30Z
                footprintMetric=c3RvcmVkX2NvbnRlbnRfc2l6ZQ|STORED
                surprise=1
                """.trimIndent(),
            )
        }
        assertTrue(unknownField.message.orEmpty().contains("line 4"), unknownField.message)
        assertTrue(unknownField.message.orEmpty().contains("surprise"), unknownField.message)

        val incomplete = assertFailsWith<IllegalArgumentException> {
            codec.decode("storageanalyzer.snapshot/1\n")
        }
        assertTrue(incomplete.message.orEmpty().contains("capturedAt missing"), incomplete.message)
    }
}
