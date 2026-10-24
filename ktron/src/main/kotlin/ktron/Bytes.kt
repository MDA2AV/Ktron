package ktron

import java.lang.invoke.MethodHandles
import java.lang.invoke.VarHandle
import java.nio.ByteOrder

/** A growable byte buffer, reused across requests: it settles at the largest size its connection sees. */
public class Buffer internal constructor(initialCapacity: Int = 0) {
    public var array: ByteArray = ByteArray(initialCapacity)
        private set
    public var length: Int = 0
        private set

    public fun reset() {
        length = 0
    }

    /** Makes room for [count] more bytes and returns the array to write them into, at [length]. */
    public fun ensure(count: Int): ByteArray {
        if (array.size - length < count) array = array.copyOf(maxOf(length + count, array.size * 2, 64))
        return array
    }

    /** Commits what was written into [ensure]'s array, up to [newLength]. */
    public fun setLength(newLength: Int) {
        length = newLength
    }

    public fun append(source: ByteArray, offset: Int = 0, count: Int = source.size - offset) {
        ensure(count)
        source.copyInto(array, length, offset, offset + count)
        length += count
    }

    /** Drops [count] bytes from the front, moving the rest down. */
    public fun consume(count: Int) {
        if (count >= length) {
            length = 0
            return
        }
        array.copyInto(array, 0, count, length)
        length -= count
    }
}

/** Decimal numbers in and out of ASCII bytes. Parsing is lenient as .NET's Utf8Parser is: leading digits count. */
public object Ascii {
    /** At most this many bytes for any Long, sign included. */
    public const val MAX_LONG_BYTES: Int = 20

    // "00".."99" as little-endian shorts, so a pair is one 16-bit store.
    private val PAIRS16 = ShortArray(100) { (('0'.code + it / 10) or (('0'.code + it % 10) shl 8)).toShort() }
    private val SHORT: VarHandle = MethodHandles.byteArrayViewVarHandle(ShortArray::class.java, ByteOrder.LITTLE_ENDIAN)

    /** Writes [value] at [at] in [destination] and returns the position after it. */
    public fun writeLong(value: Long, destination: ByteArray, at: Int): Int {
        // Most numbers fit an Int, whose division by a constant is the cheap one.
        if (value >= 0 && value <= Int.MAX_VALUE.toLong()) return writeInt(value.toInt(), destination, at)
        if (value < 0 && value > Int.MIN_VALUE.toLong()) {
            destination[at] = '-'.code.toByte()
            return writeInt(-value.toInt(), destination, at + 1)
        }
        return writeWide(value, destination, at)
    }

    /** The decimal digits [value] takes, sign included. */
    public fun length(value: Long): Int {
        if (value >= 0 && value <= Int.MAX_VALUE.toLong()) return digits(value.toInt())
        return value.toString().length
    }

    // A non-negative Int. The widths most numbers come in are written straight; wider ones from the end, a pair
    // at a time.
    private fun writeInt(value: Int, destination: ByteArray, at: Int): Int {
        if (value < 10) {
            destination[at] = ('0'.code + value).toByte()
            return at + 1
        }
        if (value < 100) {
            SHORT.set(destination, at, PAIRS16[value])
            return at + 2
        }
        if (value < 1_000) {
            val high = value / 100
            destination[at] = ('0'.code + high).toByte()
            SHORT.set(destination, at + 1, PAIRS16[value - high * 100])
            return at + 3
        }
        if (value < 10_000) {
            val high = value / 100
            SHORT.set(destination, at, PAIRS16[high])
            SHORT.set(destination, at + 2, PAIRS16[value - high * 100])
            return at + 4
        }
        val end = at + digits(value)
        var pos = end
        var v = value
        while (v >= 100) {
            val q = v / 100
            pos -= 2
            SHORT.set(destination, pos, PAIRS16[v - q * 100])
            v = q
        }
        if (v >= 10) SHORT.set(destination, pos - 2, PAIRS16[v]) else destination[pos - 1] = ('0'.code + v).toByte()
        return end
    }

    private fun digits(v: Int): Int = when {
        v < 10 -> 1
        v < 100 -> 2
        v < 1_000 -> 3
        v < 10_000 -> 4
        v < 100_000 -> 5
        v < 1_000_000 -> 6
        v < 10_000_000 -> 7
        v < 100_000_000 -> 8
        v < 1_000_000_000 -> 9
        else -> 10
    }

    // The rest of the Long range, by negative accumulation so Long.MIN_VALUE needs no special case.
    private fun writeWide(value: Long, destination: ByteArray, at: Int): Int {
        var pos = at
        var v = if (value < 0) value else -value
        if (value < 0) destination[pos++] = '-'.code.toByte()
        val start = pos
        while (v != 0L) {
            destination[pos++] = ('0'.code - (v % 10).toInt()).toByte()
            v /= 10
        }
        destination.reverse(start, pos)
        return pos
    }

    /**
     * The number at the start of [bytes] in `[from, to)`: an optional sign, then decimal digits up to the first byte
     * that is not one. Null when there are no digits or the value overflows a Long.
     */
    public fun parseLong(bytes: ByteArray, from: Int, to: Int): Long? {
        var i = from
        var negative = false
        if (i < to && (bytes[i] == '-'.code.toByte() || bytes[i] == '+'.code.toByte())) {
            negative = bytes[i] == '-'.code.toByte()
            i++
        }
        val digitsStart = i
        var value = 0L
        while (i < to) {
            val d = bytes[i] - '0'.code.toByte()
            if (d !in 0..9) break
            if (value > (Long.MAX_VALUE - d) / 10) return null
            value = value * 10 + d
            i++
        }
        if (i == digitsStart) return null
        return if (negative) -value else value
    }
}
