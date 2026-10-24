package ktron.glyph11.tests

import java.nio.ByteBuffer

/**
 * Helpers to create the buffers handed to the parsers.
 */
internal object TestBuffers {

    private val Before = byteArrayOf(0x0D, 0x0A, 0x0D, 0x0A, 0x0D, 0x0A, 0x0D)

    private val After = byteArrayOf(0x0D, 0x0A, 0x0D, 0x0A, 0x0A)

    /** ASCII bytes of the given string. */
    fun b(s: String): ByteArray = s.toByteArray(Charsets.US_ASCII)

    /** Buffer wrapping the ASCII bytes of the given string (the equivalent of the u8 literals). */
    fun buffer(s: String): ByteBuffer = ByteBuffer.wrap(b(s))

    /**
     * Creates the input buffer for the given bytes: a plain heap buffer if [embedded] is false,
     * otherwise the same bytes placed in the middle of a larger direct buffer (position and limit
     * are set to exactly the given bytes, with other bytes before and after).
     */
    fun input(bytes: ByteArray, embedded: Boolean): ByteBuffer {
        if (!embedded) {
            return ByteBuffer.wrap(bytes)
        }

        val buffer = ByteBuffer.allocateDirect(Before.size + bytes.size + After.size)

        buffer.put(Before)
        buffer.put(bytes)
        buffer.put(After)

        buffer.position(Before.size)
        buffer.limit(Before.size + bytes.size)

        return buffer
    }

    /** Returns a view on the buffer that starts the given number of bytes after its position. */
    fun skip(buffer: ByteBuffer, count: Int): ByteBuffer {
        val view = buffer.duplicate()
        view.position(buffer.position() + count)
        return view
    }

    /** Reads an ASCII string from the given offset (relative to the position) of the buffer. */
    fun asciiAt(buffer: ByteBuffer, offset: Int, length: Int): String {
        val chars = CharArray(length)

        for (i in 0 until length) {
            chars[i] = (buffer.get(buffer.position() + offset + i).toInt() and 0xFF).toChar()
        }

        return String(chars)
    }

}
