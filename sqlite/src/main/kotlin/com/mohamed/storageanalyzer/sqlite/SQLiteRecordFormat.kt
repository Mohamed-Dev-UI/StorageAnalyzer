package com.mohamed.storageanalyzer.sqlite

import com.mohamed.storageanalyzer.core.measurement.ByteCount
import com.mohamed.storageanalyzer.core.measurement.MeasurementAccuracy
import com.mohamed.storageanalyzer.core.measurement.MeasurementMethod
import com.mohamed.storageanalyzer.core.measurement.MeasurementProvenance
import java.time.Instant

/**
 * SQLite 3 record-format math for record-level measurements.
 *
 * A table b-tree cell of a rowid table contains
 *
 *   varint(payloadSize) + varint(rowid) + record
 *
 * where record = varint(headerSize) + serial-type varints + value payloads,
 * and headerSize is a fixed point: headerSize = serialVarints + varintLen(headerSize).
 * WITHOUT ROWID tables store rows as index-cells: record only.
 *
 * **Accounting convention** (docs/MEASUREMENT_ACCURACY.md):
 * recordEncodedSize counts all record bytes and the cell-level varints, but
 * NOT the per-cell pointer (2 B), page-granularity rounding, free-page reuse,
 * or overflow placement. Accuracy: [MeasurementAccuracy.CONVENTION].
 *
 * For per-record values the caller supplies the SQL `typeof`-classified
 * values (integer magnitude, text/blob byte length). For aggregates this
 * object generates equivalent SQL so sums happen engine-side (no row
 * transfer) — the generated SQL relies on two registered helper functions
 * `varint_len_tf(v)` and `int_serial_payload(i)` (see [SQLiteUdfSupport]).
 */
object SQLiteRecordFormat {

    /** Encoded sizes of one value's parts. */
    data class ValueSize(
        /** payload bytes inside the record body (serial-type data). */
        val payloadBytes: Long,
        /** bytes of the serial type varint inside the record header. */
        val serialTypeVarintBytes: Long,
    )

    const val LIMIT_EXCLUDES_CELL_POINTER = "sqlite.excludes.cell-pointer"
    const val LIMIT_EXCLUDES_PAGE_ALLOCATION = "sqlite.excludes.page-allocation"

    /**
     * Encoded size of one value given its SQL storage class (`typeof()`).
     */
    fun valueSize(
        valueClass: String,
        integerMagnitude: Long,
        byteLength: Long,
        isRowidAlias: Boolean,
    ): ValueSize? = when {
        isRowidAlias -> ValueSize(0, 0) // rowid alias columns are stored as NULL
        valueClass == "null" -> ValueSize(0, 0)
        valueClass == "integer" -> {
            val payload = integerPayloadBytes(integerMagnitude)
            ValueSize(payload, varintLen(SQLITE_INTEGER_SERIAL_STRIPE).toLong())
        }
        valueClass == "real" -> ValueSize(8, 1)
        valueClass == "text" -> ValueSize(byteLength, varintLen(13L + 2 * byteLength).toLong())
        valueClass == "blob" -> ValueSize(byteLength, varintLen(12L + 2 * byteLength).toLong())
        else -> null // unexpected classes; reported as unknown, never approximated
    }

    /**
     * Serial type lengths for INTEGER storage:
     * -128..127 → 1, -32768..32767 → 2, ... else 8 bytes.
     */
    fun integerPayloadBytes(v: Long): Long = when {
        v >= -128L && v <= 127L -> 1L
        v >= -32768L && v <= 32767L -> 2L
        v >= -8_388_608L && v <= 8_388_607L -> 3L
        v >= -2_147_483_648L && v <= 2_147_483_647L -> 4L
        v >= -140_737_488_355_328L && v <= 140_737_488_355_327L -> 6L
        else -> 8L
    }

    private const val SQLITE_INTEGER_SERIAL_STRIPE = 1L // serial ids 1..6 use a 1-byte varint

    /**
     * Length of the SQLite varint encoding of `value`.
     * Negative values (possible for rowids) occupy up to 9 bytes.
     */
    fun varintLen(value: Long): Int {
        if (value < 0) return 9
        val v = value
        return when {
            v < 128L -> 1
            v < 16_384L -> 2
            v < 2_097_152L -> 3
            v < 268_435_456L -> 4
            v < 34_359_738_368L -> 5
            v < 4_398_046_511_104L -> 6
            v < 562_949_953_421_312L -> 7
            v < 72_057_594_037_927_936L -> 8
            else -> 9
        }
    }

    /** Fixed point: h = serialVarints + varintLen(h). */
    fun headerSizeOf(serialVarints: Long): Long {
        var h = serialVarints + 1
        repeat(3) {
            val candidate = serialVarints + varintLen(h)
            if (candidate == h) return h
            h = candidate
        }
        return h
    }

    /** Fully-computed convention record of one row. */
    fun recordSize(
        valueSizes: List<ValueSize>,
        payloadsExtra: Long,
        isWithoutRowid: Boolean,
        rowid: Long,
    ): RecordByteCount {
        require(payloadsExtra >= 0) { "payloadsExtra must be >= 0" }
        val serialVarints = valueSizes.sumOf { it.serialTypeVarintBytes }
        val payloadBytes = valueSizes.sumOf { it.payloadBytes } + payloadsExtra
        val header = headerSizeOf(serialVarints)
        val record = Math.addExact(header, payloadBytes)
        val chunks = buildList {
            add(Chunk("record_header", header))
            add(Chunk("value_payloads", payloadBytes))
            if (!isWithoutRowid) {
                add(Chunk("rowid_varint", varintLen(rowid).toLong()))
                add(Chunk("payload_size_varint", varintLen(record).toLong()))
            }
        }
        return RecordByteCount(chunks)
    }

    data class Chunk(val name: String, val bytes: Long)

    data class RecordByteCount(val chunks: List<Chunk>) {
        val total: ByteCount by lazy {
            ByteCount.sum(chunks.map { ByteCount.of(it.bytes) })
        }
    }

    // ------------------------------------------------------- SQL aggregation

    /**
     * The SQL expression that computes one row's convention-encoded size in
     * `table` (with [rowidAliasColumn] marked when the rowid alias exists, so
     * that column is counted as NULL — the rowid carries its bytes).
     */
    fun rowSizeExpression(
        table: String,
        columns: List<ColumnSpec>,
        isWithoutRowid: Boolean,
        rowidRef: String = "rowid",
    ): String {
        val serials = columns.map { serialVarintExpr(table, it.name, it.isRowidAlias) }
        val payloads = columns.map { payloadExpr(table, it.name, it.isRowidAlias) }
        val serialSum = serials.joinToString(" + ").ifEmpty { "0" }
        val payloadSum = payloads.joinToString(" + ").ifEmpty { "0" }
        val header = headerSolveExpr(serialSum)
        val record = "($header + $payloadSum)"
        return if (isWithoutRowid) record
        else "(varint_len_tf($rowidRef) + varint_len_tf($record) + $record)"
    }

    /** One SQL expression pair (payload bytes, serial-varint bytes) per column for aggregates. */
    fun columnComponents(table: String, col: ColumnSpec): Pair<String, String> =
        payloadExpr(table, col.name, col.isRowidAlias) to serialVarintExpr(table, col.name, col.isRowidAlias)

    data class ColumnSpec(
        val name: String,
        /** True for the rowid-alias column (INTEGER PRIMARY KEY) of rowid tables. */
        val isRowidAlias: Boolean,
    )

    /** header solve: h = s + varint_len_tf(h), nested to the fixed point (≤3 iterations). */
    private fun headerSolveExpr(serialSum: String): String {
        val h1 = "($serialSum + varint_len_tf($serialSum + 1))"
        val h2 = "($serialSum + varint_len_tf($h1))"
        return "($serialSum + varint_len_tf($h2))"
    }

    private fun serialVarintExpr(table: String, col: String, rowidAlias: Boolean): String {
        if (rowidAlias) return "0"
        val ref = "\"$table\".\"$col\""
        return "CASE typeof($ref) " +
            "WHEN 'null' THEN 0 " +
            "WHEN 'integer' THEN 1 " +
            "WHEN 'real' THEN 1 " +
            "WHEN 'text' THEN varint_len_tf(13 + 2 * length(CAST($ref AS BLOB))) " +
            "WHEN 'blob' THEN varint_len_tf(12 + 2 * length($ref)) " +
            "ELSE 0 END"
    }

    private fun payloadExpr(table: String, col: String, rowidAlias: Boolean): String {
        if (rowidAlias) return "0"
        val ref = "\"$table\".\"$col\""
        return "CASE typeof($ref) " +
            "WHEN 'null' THEN 0 " +
            "WHEN 'integer' THEN int_serial_payload($ref) " +
            "WHEN 'real' THEN 8 " +
            "WHEN 'text' THEN length(CAST($ref AS BLOB)) " +
            "WHEN 'blob' THEN length($ref) " +
            "ELSE 0 END"
    }

    /** Provenance for record measurements under this convention. */
    fun provenance(now: Instant, backend: String): MeasurementProvenance = MeasurementProvenance(
        method = MeasurementMethod.SQLITE_RECORD_FORMAT,
        accuracy = MeasurementAccuracy.CONVENTION,
        measuredAt = now,
        providerId = SQLiteStorageProvider.providerIdValue,
        backend = backend,
        limitations = setOf(LIMIT_EXCLUDES_CELL_POINTER, LIMIT_EXCLUDES_PAGE_ALLOCATION),
    )
}
