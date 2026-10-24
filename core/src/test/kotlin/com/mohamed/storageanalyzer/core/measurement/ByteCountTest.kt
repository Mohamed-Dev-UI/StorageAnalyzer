package com.mohamed.storageanalyzer.core.measurement

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ByteCountTest {

    @Test
    fun `rejects negative values`() {
        assertFailsWith<IllegalArgumentException> { ByteCount(-1) }
    }

    @Test
    fun `addition is checked and never wraps`() {
        val big = ByteCount(Long.MAX_VALUE)
        assertFailsWith<ArithmeticException> { big + ByteCount(1) }
    }

    @Test
    fun `sum never wraps silently`() {
        val acc = (0 until 3).map { ByteCount(Long.MAX_VALUE / 2) }
        assertFailsWith<ArithmeticException> { ByteCount.sum(acc) }
        assertEquals(ByteCount(10), ByteCount.sum(listOf(ByteCount(4), ByteCount(6))))
    }

    @Test
    fun `sumOfLongs rejects negatives rather than producing negative totals`() {
        assertFailsWith<IllegalArgumentException> { ByteCount.sumOfLongs(listOf(5, -3)) }
    }

    @Test
    fun `subtraction of greater value is rejected (no negative storage)`() {
        assertFailsWith<IllegalArgumentException> { ByteCount(1) - ByteCount(2) }
        assertEquals(ByteCount(1), ByteCount(2) - ByteCount(1))
    }

    @Test
    fun `splitEqually distributes the remainder exactly`() {
        val (per, rem) = ByteCount.splitEqually(ByteCount(10), 3)
        assertEquals(ByteCount(3), per)
        assertEquals(ByteCount(1), rem)
        assertEquals(1L, rem.value % 3)
    }

    @Test
    fun `formatting distinguishes binary IEC and decimal SI conventions`() {
        assertEquals("1.00 KiB", StorageUnits.formatIec(ByteCount(1024)))
        assertEquals("1.00 MB", StorageUnits.formatSi(ByteCount(1_000_000)))
        assertEquals("1.50 KiB", StorageUnits.formatIec(ByteCount(1536)))
        assertEquals("0 B", StorageUnits.formatIec(ByteCount(0)))
        assertEquals("512 B", StorageUnits.formatSi(ByteCount(512)))
    }

    @Test
    fun `multiplication checks overflow`() {
        assertFailsWith<ArithmeticException> { ByteCount(Long.MAX_VALUE) * 2L }
        assertTrue((ByteCount(2) * 4L) == ByteCount(8))
    }
}
