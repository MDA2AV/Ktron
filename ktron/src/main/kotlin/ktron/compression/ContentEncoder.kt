package ktron.compression

import ktron.glyph11.ByteBuilder

/**
 * A response body coding. Each instance belongs to one reactor thread: it keeps native state and scratch
 * buffers across calls and is not thread-safe.
 */
interface ContentEncoder {
    /** The coding as Content-Encoding names it: "gzip" or "br". */
    val name: String

    /** Appends the encoded form of `input[offset, offset + length)` to [output]; returns the bytes appended. */
    fun encode(input: ByteArray, offset: Int, length: Int, output: ByteBuilder): Int
}
