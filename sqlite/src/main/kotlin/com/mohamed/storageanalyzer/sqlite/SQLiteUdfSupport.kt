package com.mohamed.storageanalyzer.sqlite

import org.sqlite.Function
import java.sql.Connection
import java.sql.SQLException

/**
 * Registers the two scalar SQL helper functions the record-format aggregate
 * expressions rely on (see [SQLiteRecordFormat]). Registration happens once
 * per individually opened connection and never touches database content —
 * functions live in the session, not in the schema.
 *
 * - `varint_len_tf(v)` → SQLite varint byte length of a 64-bit value
 *   (non-negative threshold encoding; negative rowids occupy 9 bytes).
 * - `int_serial_payload(i)` → INTEGER storage bytes by signed magnitude.
 */
internal object SQLiteUdfSupport {

    const val VARINT_LEN_FUNCTION = "varint_len_tf"
    const val INT_PAYLOAD_FUNCTION = "int_serial_payload"

    fun register(connection: Connection) {
        Function.create(
            connection,
            VARINT_LEN_FUNCTION,
            object : Function() {
                override fun xFunc() {
                    result(SQLiteRecordFormat.varintLen(value_long(0)).toLong())
                }
            },
            1,
        )
        Function.create(
            connection,
            INT_PAYLOAD_FUNCTION,
            object : Function() {
                override fun xFunc() {
                    // NULL never reaches this branch through the generated CASE
                    // expressions; for a direct integer call, value_long() is exact.
                    result(SQLiteRecordFormat.integerPayloadBytes(value_long(0)))
                }
            },
            1,
        )
    }
}
