package ktron.glyph11

import java.lang.invoke.MethodHandles
import java.lang.invoke.VarHandle
import java.nio.ByteOrder

/** Character classes and word-at-a-time scans over a request's bytes. */
internal object Ascii {
    const val CR: Int = 0x0D
    const val LF: Int = 0x0A
    const val SP: Int = 0x20
    const val HTAB: Int = 0x09

    // ---- 256-entry classes, one bit each, indexed by the unsigned byte ----

    /** tchar (RFC 9110 5.6.2): a method or a field name. */
    const val TOKEN: Int = 0x01

    /** A request-target byte (VCHAR or SP) with no meaning to the path checks: not `? # \ % /`. */
    const val PATH: Int = 0x02

    /** A request-target byte (VCHAR) with no meaning to the query split: not `& =`. */
    const val QUERY: Int = 0x04

    /** RequestTargetSearchValues: SP and VCHAR (0x20-0x7E). */
    const val TARGET: Int = 0x08

    /** FieldValueSearchValues: HTAB, SP, VCHAR and obs-text. */
    const val FIELD: Int = 0x10

    @JvmField
    val CLASS: ByteArray = ByteArray(256).also { t ->
        for (c in "!#$%&'*+-.^_`|~0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz") t[c.code] = TOKEN.toByte()
        for (b in 0x21..0x7E) {
            var bits = t[b].toInt() or TARGET or FIELD
            if (b.toChar() !in "?#\\%/") bits = bits or PATH
            if (b.toChar() !in "&=") bits = bits or QUERY
            t[b] = bits.toByte()
        }
        t[SP] = (t[SP].toInt() or TARGET or FIELD).toByte()
        t[HTAB] = (t[HTAB].toInt() or FIELD).toByte()
        for (b in 0x80..0xFF) t[b] = (t[b].toInt() or FIELD).toByte()
    }

    /** Hex digit values, -1 for anything else. */
    @JvmField
    val HEX: ByteArray = ByteArray(256) { -1 }.also { t ->
        for (i in 0..9) t['0'.code + i] = i.toByte()
        for (i in 0..5) {
            t['a'.code + i] = (10 + i).toByte()
            t['A'.code + i] = (10 + i).toByte()
        }
    }

    // ---- words: eight bytes, little endian, the byte at the lowest index in the low bits ----

    private val LONGS: VarHandle = MethodHandles.byteArrayViewVarHandle(LongArray::class.java, ByteOrder.LITTLE_ENDIAN)

    /** The eight bytes at [at]; `at + 8 <= a.size`. */
    @JvmStatic
    fun word(a: ByteArray, at: Int): Long = LONGS.get(a, at) as Long

    private const val ONES: Long = 0x0101010101010101L
    private const val HIGHS: Long = -0x7f7f7f7f7f7f7f80L // 0x8080808080808080
    private const val SPACES: Long = 0x2020202020202020L
    private const val DELS: Long = 0x7f7f7f7f7f7f7f7fL

    /** The high bit of each zero byte of [x]; as in [controls], only the lowest one is exact. */
    @JvmStatic
    fun zeroBytes(x: Long): Long = (x - ONES) and x.inv() and HIGHS

    /**
     * The high bit of each byte below 0x20 or equal to 0x7F (a byte no field value holds, but for HTAB). Borrows
     * can flag bytes above a flagged one, never below: the lowest flag is exact, which is all a scan reads.
     */
    @JvmStatic
    fun controls(w: Long): Long = ((w - SPACES) and w.inv() and HIGHS) or zeroBytes(w xor DELS)

    /**
     * The index of the first byte in `[from, limit)` below 0x20 or equal to 0x7F, or [limit] if there is none. Reads
     * whole words up to the end of the array, past [limit]: what it finds there is not reported.
     */
    @JvmStatic
    fun firstControl(a: ByteArray, from: Int, limit: Int): Int {
        var i = from
        val lastWord = a.size - 8
        while (i < limit && i <= lastWord) {
            val m = controls(word(a, i))
            if (m != 0L) {
                val at = i + (java.lang.Long.numberOfTrailingZeros(m) ushr 3)
                return if (at < limit) at else limit
            }
            i += 8
        }
        while (i < limit) {
            val b = a[i].toInt() and 0xFF
            if (b < SP || b == 0x7F) return i
            i++
        }
        return limit
    }

    // ---- the reference scans, for the exact parser ----

    /** The first index of [b] in `[from, to)`, or -1. */
    @JvmStatic
    fun indexOf(a: ByteArray, b: Int, from: Int, to: Int): Int {
        val v = b.toByte()
        for (i in from until to) if (a[i] == v) return i
        return -1
    }

    /** The index of the CR of the first CRLF in `[from, to)`, or -1. */
    @JvmStatic
    fun indexOfCrlf(a: ByteArray, from: Int, to: Int): Int {
        var i = from
        while (i + 1 < to) {
            if (a[i].toInt() == CR && a[i + 1].toInt() == LF) return i
            i++
        }
        return -1
    }

    /** The index of the first CR of the first CRLFCRLF in `[from, to)`, or -1. */
    @JvmStatic
    fun indexOfCrlfCrlf(a: ByteArray, from: Int, to: Int): Int {
        var i = from
        while (i + 3 < to) {
            if (a[i].toInt() == CR && a[i + 1].toInt() == LF && a[i + 2].toInt() == CR && a[i + 3].toInt() == LF) return i
            i++
        }
        return -1
    }

    /** True if every byte in `[from, to)` has [bits] in [CLASS]. */
    @JvmStatic
    fun allIn(a: ByteArray, from: Int, to: Int, bits: Int): Boolean {
        for (i in from until to) if (CLASS[a[i].toInt() and 0xFF].toInt() and bits == 0) return false
        return true
    }

    /**
     * `[at, at + length)` equals [lower] under the parsers' case folding: both sides OR 0x20, which is ASCII case
     * folding for the letters and leaves `-` alone.
     */
    @JvmStatic
    fun foldEquals(a: ByteArray, at: Int, length: Int, lower: ByteArray): Boolean {
        if (length != lower.size) return false
        for (i in 0 until length) if ((a[at + i].toInt() or 0x20) != (lower[i].toInt() or 0x20)) return false
        return true
    }

    @JvmStatic
    fun rangeEquals(a: ByteArray, aStart: Int, aLength: Int, bStart: Int, bLength: Int): Boolean {
        if (aLength != bLength) return false
        for (i in 0 until aLength) if (a[aStart + i] != a[bStart + i]) return false
        return true
    }

    @JvmStatic
    fun isOws(b: Int): Boolean = b == SP || b == HTAB
}
