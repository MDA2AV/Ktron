package ktron.glyph11.tests

import java.nio.ByteBuffer
import kotlin.test.assertEquals

/**
 * Compares the content of a buffer (position until limit) with an ASCII string.
 * The position of the buffer is not modified.
 */
internal object AssertAscii {

    fun equal(expectedAscii: String, actual: ByteBuffer) {
        assertEquals(expectedAscii, asciiOf(actual))
    }

    fun asciiOf(actual: ByteBuffer): String {
        val start = actual.position()
        val length = actual.remaining()

        val chars = CharArray(length)

        for (i in 0 until length) {
            chars[i] = (actual.get(start + i).toInt() and 0xFF).toChar()
        }

        return String(chars)
    }

}
