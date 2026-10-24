package com.mohamed.storageanalyzer.core.measurement

/**
 * A non-negative count of bytes. Bytes are the canonical unit of every
 * measurement in this library.
 *
 * All arithmetic is **checked**: overflow raises [ArithmeticException] instead
 * of silently wrapping, and subtraction never produces a negative count.
 * Aggregations must use [sum] / [plus] so that the invariant
 * "no silent negative or wrapped storage totals" holds everywhere.
 */
@JvmInline
value class ByteCount(val value: Long) : Comparable<ByteCount> {

    init {
        require(value >= 0) { "ByteCount must be non-negative (was $value); use a dedicated unavailable/sentinel model instead of negative values" }
    }

    /** Checked addition; throws [ArithmeticException] on overflow. */
    operator fun plus(other: ByteCount): ByteCount = ByteCount(Math.addExact(value, other.value))

    /** Checked subtraction; throws [IllegalArgumentException] when [other] exceeds this value. */
    operator fun minus(other: ByteCount): ByteCount {
        require(other.value <= value) { "ByteCount subtraction would go negative: $value - ${other.value}" }
        return ByteCount(value - other.value)
    }

    /** Checked multiplication by a non-negative [factor]. */
    operator fun times(factor: Long): ByteCount {
        require(factor >= 0) { "factor must be non-negative" }
        return ByteCount(Math.multiplyExact(value, factor))
    }

    override fun compareTo(other: ByteCount): Int = value.compareTo(other.value)

    override fun toString(): String = "$value B"

    companion object {
        val ZERO = ByteCount(0)

        fun of(value: Long): ByteCount = ByteCount(value)

        /**
         * Checked sum. Guarded aggregation path used by all builders so that
         * overflow becomes an explicit error, never a silently wrapped total.
         */
        fun sum(values: Iterable<ByteCount>): ByteCount {
            var acc: Long = 0
            for (v in values) {
                acc = Math.addExact(acc, v.value)
            }
            return ByteCount(acc)
        }

        /**
         * Checked sum of primitive longs (already validated by construction
         * into [ByteCount] on return). Throws on overflow.
         */
        fun sumOfLongs(values: Iterable<Long>): ByteCount {
            var acc: Long = 0
            for (v in values) {
                require(v >= 0) { "negative byte value $v aggregated" }
                acc = Math.addExact(acc, v)
            }
            return ByteCount(acc)
        }

        /**
         * Splits [total] equally among [parts] owners, returning
         * `(perOwner, remainder)` with `remainder < parts`.
         * Deterministic consumers may assign the remainder byte-by-byte in a
         * stable order (see [com.mohamed.storageanalyzer.core.policy.AccountingPolicies.EQUAL_SPLIT]).
         */
        fun splitEqually(total: ByteCount, parts: Int): Pair<ByteCount, ByteCount> {
            require(parts > 0) { "parts must be > 0" }
            val remaining = total.value
            return Pair(ByteCount(remaining / parts), ByteCount(remaining % parts))
        }
    }
}

/**
 * Binary and decimal unit formatting with explicitly documented conventions.
 *
 * This library **reports and stores canonical bytes**; formatting helpers are
 * presentation-only and never round-trip into measurements.
 */
object StorageUnits {

    enum class Convention(val suffix1200: String, val suffix1000: String) {
        /** Binary convention: 1 KiB = 1024 B, 1 MiB = 1024 KiB, ... */
        IEC("KiB", "KB"),

        /** Decimal convention: 1 kB = 1000 B, 1 MB = 1000 kB, ... */
        SI("kB", "KB")
    }

    private val IEC_STEPS = longArrayOf(1, 1024, 1024L * 1024, 1024L * 1024 * 1024, 1024L * 1024 * 1024 * 1024)
    private val IEC_NAMES = arrayOf("B", "KiB", "MiB", "GiB", "TiB")

    private val SI_STEPS = longArrayOf(1, 1000, 1000L * 1000, 1000L * 1000 * 1000, 1000L * 1000 * 1000 * 1000)
    private val SI_NAMES = arrayOf("B", "kB", "MB", "GB", "TB")

    /**
     * Formats [bytes] in binary IEC convention (KiB, MiB, GiB, TiB) with
     * [decimals] fractional digits, e.g. `1536 -> "1.5 KiB"`.
     */
    fun formatIec(bytes: ByteCount, decimals: Int = 2): String = format(bytes, decimals, IEC_STEPS, IEC_NAMES)

    /**
     * Formats [bytes] in decimal SI convention (kB, MB, GB, TB) with
     * [decimals] fractional digits, e.g. `1500 -> "1.5 kB"`.
     */
    fun formatSi(bytes: ByteCount, decimals: Int = 2): String = format(bytes, decimals, SI_STEPS, SI_NAMES)

    private fun format(bytes: ByteCount, decimals: Int, steps: LongArray, names: Array<String>): String {
        val v = bytes.value
        if (v == 0L) return "0 B"
        var idx = 0
        while (idx + 1 < steps.size && v >= steps[idx + 1]) idx++
        if (idx == 0) return "$v B"
        val scaled = v.toDouble() / steps[idx]
        val formatted =
            if (decimals <= 0) scaled.toLong().toString()
            else "%.${decimals}f".format(scaled)
        return "$formatted ${names[idx]}"
    }
}
