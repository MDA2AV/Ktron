package ktron.glyph11

import java.nio.ByteBuffer

/**
 * A shared, immutable, zero-length buffer used wherever upstream assigns an empty memory.
 */
internal val EmptyBuffer: ByteBuffer = ByteBuffer.allocate(0).asReadOnlyBuffer()

/**
 * Internal view on the content (`position until limit`) of a [ByteBuffer] with checked, unsigned,
 * absolute byte access. Never touches the position or limit of the wrapped buffer.
 *
 * Heap buffers are read through their backing array, all other buffers (direct, read-only) through
 * absolute `get` calls. All indexes are relative to the position the buffer had on creation.
 *
 * If a [pool] is given, [slice] serves views on heap buffers from it instead of creating new ones.
 */
internal class ByteSpan(private val buffer: ByteBuffer, private val pool: ViewPool? = null) {

    private val start: Int = buffer.position()

    private val array: ByteArray? = if (buffer.hasArray()) buffer.array() else null

    private val arrayBase: Int = if (array != null) buffer.arrayOffset() + start else 0

    // the slices handed out are read-only, so a writable input is converted once instead of per slice
    private var readOnly: ByteBuffer? = if (buffer.isReadOnly) buffer else null

    /** The number of bytes in this span. */
    val length: Int = buffer.remaining()

    /** Returns the byte at [index] as an unsigned value (0..255); throws if out of range. */
    fun at(index: Int): Int {
        if (index < 0 || index >= length) {
            throw IndexOutOfBoundsException("Index $index out of bounds for length $length")
        }

        val a = array

        return if (a != null) {
            a[arrayBase + index].toInt() and 0xFF
        } else {
            buffer.get(start + index).toInt() and 0xFF
        }
    }

    /** Index of the first occurrence of [needle] within `[from, to)`, or -1. */
    fun indexOf(needle: ByteArray, from: Int = 0, to: Int = length): Int {
        val size = needle.size
        val last = to - size

        if (size == 0) {
            return if (from <= last) from else -1
        }

        val first = needle[0].toInt() and 0xFF

        var i = from

        while (i <= last) {
            i = indexOfByte(first, i, last + 1)

            if (i < 0) return -1

            var j = 1

            while (j < size && at(i + j) == (needle[j].toInt() and 0xFF)) {
                j++
            }

            if (j == size) return i

            i++
        }

        return -1
    }

    /** Index of the first occurrence of the byte [value] within `[from, to)`, or -1. */
    fun indexOfByte(value: Int, from: Int, to: Int): Int {
        checkRange(from, to)

        val a = array

        if (a != null) {
            val needle = value.toByte()
            val end = arrayBase + to

            var i = arrayBase + from

            while (i < end) {
                if (a[i] == needle) return i - arrayBase
                i++
            }

            return -1
        }

        var i = from

        while (i < to) {
            if (at(i) == value) return i
            i++
        }

        return -1
    }

    /** True if all bytes within `[from, to)` are flagged in [table]. */
    fun allIn(table: BooleanArray, from: Int, to: Int): Boolean {
        checkRange(from, to)

        val a = array

        if (a != null) {
            val end = arrayBase + to

            var i = arrayBase + from

            while (i < end) {
                if (!table[a[i].toInt() and 0xFF]) return false
                i++
            }

            return true
        }

        var i = from

        while (i < to) {
            if (!table[at(i)]) return false
            i++
        }

        return true
    }

    /** True if both ranges have the same length and content. */
    fun rangeEquals(aStart: Int, aLength: Int, bStart: Int, bLength: Int): Boolean {
        if (aLength != bLength) return false

        for (i in 0 until aLength) {
            if (at(aStart + i) != at(bStart + i)) return false
        }

        return true
    }

    /** ASCII case-insensitive comparison of `[from, from + len)` with [other] (same semantics as upstream). */
    fun equalsIgnoreCase(from: Int, len: Int, other: ByteArray): Boolean {
        if (len != other.size) return false

        for (i in 0 until len) {
            if ((at(from + i) or 0x20) != ((other[i].toInt() and 0xFF) or 0x20)) return false
        }

        return true
    }

    /**
     * Creates a read-only buffer whose content (position until limit) is exactly `[offset, offset + len)` of this span.
     * Views served by the pool start at the position of the field within the backing array.
     */
    fun slice(offset: Int, len: Int): ByteBuffer {
        val a = array

        if (pool != null && a != null) {
            pool.view(a, arrayBase + offset, len)?.let { return it }
        }

        val source = readOnly ?: buffer.asReadOnlyBuffer().also { readOnly = it }

        return source.slice(start + offset, len)
    }

    private fun checkRange(from: Int, to: Int) {
        if (from < to && (from < 0 || to > length)) {
            throw IndexOutOfBoundsException("Range [$from, $to) out of bounds for length $length")
        }
    }

}
