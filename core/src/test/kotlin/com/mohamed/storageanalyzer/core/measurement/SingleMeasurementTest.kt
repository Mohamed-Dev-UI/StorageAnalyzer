package com.mohamed.storageanalyzer.core.measurement

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SingleMeasurementTest {

    private val now = Instant.parse("2026-01-01T00:00:00Z")
    private fun prov(accuracy: MeasurementAccuracy = MeasurementAccuracy.EXACT) = MeasurementProvenance(
        method = MeasurementMethod.FILE_LENGTH,
        accuracy = accuracy,
        measuredAt = now,
        providerId = "fs",
    )

    @Test
    fun `a MEASURED entry must carry a value and a real accuracy`() {
        assertFailsWith<IllegalArgumentException> {
            SingleMeasurement(MetricType.FILE_LOGICAL_SIZE, null, MeasurementState.MEASURED, prov())
        }
        assertFailsWith<IllegalArgumentException> {
            SingleMeasurement(
                MetricType.FILE_LOGICAL_SIZE,
                ByteCount(1),
                MeasurementState.MEASURED,
                prov(MeasurementAccuracy.UNAVAILABLE),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            SingleMeasurement(
                MetricType.FILE_LOGICAL_SIZE,
                ByteCount(1),
                MeasurementState.MEASURED,
                prov(MeasurementAccuracy.UNKNOWN),
            )
        }
    }

    @Test
    fun `there is no implicit zero - unavailable has value null and a reason`() {
        // UNAVAILABLE state is legitimate as long as no value is attached.
        val m = SingleMeasurement(
            MetricType.FILE_ALLOCATED_SIZE,
            null,
            MeasurementState.UNAVAILABLE,
            MeasurementProvenance.absent("fs", now, MeasurementMethod.FILE_LENGTH, reason = "unsupported on this platform"),
            reason = "unsupported on this platform",
        )
        assertNull(m.value)
        assertFailsWith<IllegalArgumentException> {
            // Reasonless unavailable measurement is invalid.
            SingleMeasurement(MetricType.FILE_ALLOCATED_SIZE, null, MeasurementState.NOT_APPLICABLE, prov())
        }
        // never present a zero for unknown:
        assertEquals(null, m.value)
    }

    @Test
    fun `not-yet-measured state requires a reason too`() {
        assertFailsWith<IllegalArgumentException> {
            SingleMeasurement(MetricType.FILE_ALLOCATED_SIZE, null, MeasurementState.NOT_YET_MEASURED, prov())
        }
    }
}
