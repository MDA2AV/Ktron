package ktron.glyph11.parser

import ktron.glyph11.ByteSpan
import java.nio.ByteBuffer

/**
 * Constants and validation helpers shared by the parsers.
 */
public object ParserConstants {

    // ---- Line terminators ----

    /** `\r\n` */
    public val Crlf: ByteArray = byteArrayOf(0x0D, 0x0A)

    /** `\r\n\r\n` */
    public val CrlfCrlf: ByteArray = byteArrayOf(0x0D, 0x0A, 0x0D, 0x0A)

    // ---- Special bytes ----

    /** ' ' */
    public const val Space: Byte = 0x20

    /** '?' */
    public const val Question: Byte = 0x3F

    /** '&' */
    public const val QuerySeparator: Byte = 0x26

    /** '=' */
    public const val Equal: Byte = 0x3D

    /** ':' */
    public const val Colon: Byte = 0x3A

    // ---- Character class lookup tables (indexed by the unsigned byte value) ----

    /** Token chars (RFC 9110 5.6.2): `!#$%&'*+-.^_`|~` DIGIT ALPHA */
    public val TokenSearchValues: BooleanArray = createTable(
        "!#$%&'*+-.0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ^_`abcdefghijklmnopqrstuvwxyz|~".toByteArray(Charsets.US_ASCII)
    )

    /** Field-value chars (RFC 9110 5.5): HTAB SP VCHAR obs-text */
    public val FieldValueSearchValues: BooleanArray = createTable(buildFieldValueBytes())

    /** Request-target: only VCHAR (0x21-0x7E) and SP (0x20) - RFC 3986 URIs are ASCII-only */
    public val RequestTargetSearchValues: BooleanArray = createTable(buildRequestTargetBytes())

    /** Builds the set of bytes allowed in a field value: HTAB, SP, VCHAR and obs-text. */
    public fun buildFieldValueBytes(): ByteArray {
        val bytes = ByteArray(1 + (0x7E - 0x20 + 1) + (0xFF - 0x80 + 1))
        var i = 0
        bytes[i++] = 0x09 // HTAB
        for (b in 0x20..0x7E) bytes[i++] = b.toByte() // SP + VCHAR
        for (b in 0x80..0xFF) bytes[i++] = b.toByte() // obs-text
        return bytes
    }

    /** Builds the set of bytes allowed in a request-target: SP and ASCII VCHAR only. */
    public fun buildRequestTargetBytes(): ByteArray {
        // ASCII VCHAR (0x21-0x7E) + SP (0x20) only - no non-ASCII (0x80-0xFF)
        val bytes = ByteArray(0x7E - 0x20 + 1)
        var i = 0
        for (b in 0x20..0x7E) bytes[i++] = b.toByte()
        return bytes
    }

    private fun createTable(allowed: ByteArray): BooleanArray {
        val table = BooleanArray(256)
        for (b in allowed) table[b.toInt() and 0xFF] = true
        return table
    }

    // ---- Validation helpers (ByteBuffer: position until limit) ----

    /** True if every byte of the buffer content is a token character (an empty buffer is valid). */
    public fun isValidToken(span: ByteBuffer): Boolean = allIn(TokenSearchValues, span)

    /** True if every byte of the buffer content is allowed in a field value (an empty buffer is valid). */
    public fun isValidFieldValue(span: ByteBuffer): Boolean = allIn(FieldValueSearchValues, span)

    /**
     * HTTP-version = "HTTP/" DIGIT "." DIGIT  (RFC 9112 2.6)
     * Exactly 8 bytes, case-sensitive.
     */
    public fun isValidHttpVersion(span: ByteBuffer): Boolean = isValidHttpVersion(ByteSpan(span), 0, span.remaining())

    /** True if the byte is an ASCII digit. */
    public fun isDigit(b: Byte): Boolean = isDigit(b.toInt() and 0xFF)

    /**
     * Validates that a request-target contains no control characters (0x00-0x1F, 0x7F).
     * RFC 9112 3.2 - request-target must only contain VCHAR and unreserved/reserved URI chars.
     */
    public fun isValidRequestTarget(span: ByteBuffer): Boolean = allIn(RequestTargetSearchValues, span)

    // ---- Semantic validation constants ----

    /** `transfer-encoding` */
    public val TransferEncodingName: ByteArray = "transfer-encoding".toByteArray(Charsets.US_ASCII)

    /** `chunked` */
    public val ChunkedValue: ByteArray = "chunked".toByteArray(Charsets.US_ASCII)

    /** ASCII case-insensitive comparison of the content of both buffers. */
    public fun asciiEqualsIgnoreCase(a: ByteBuffer, b: ByteBuffer): Boolean {
        if (a.remaining() != b.remaining()) return false

        val aStart = a.position()
        val bStart = b.position()

        for (i in 0 until a.remaining()) {
            if (((a.get(aStart + i).toInt() and 0xFF) or 0x20) != ((b.get(bStart + i).toInt() and 0xFF) or 0x20)) return false
        }

        return true
    }

    // ---- Internal range based helpers (used by the parsers to avoid slicing) ----

    internal fun isDigit(b: Int): Boolean = (b - '0'.code) in 0..9

    internal fun isValidToken(span: ByteSpan, from: Int, to: Int): Boolean = span.allIn(TokenSearchValues, from, to)

    internal fun isValidFieldValue(span: ByteSpan, from: Int, to: Int): Boolean = span.allIn(FieldValueSearchValues, from, to)

    internal fun isValidRequestTarget(span: ByteSpan, from: Int, to: Int): Boolean = span.allIn(RequestTargetSearchValues, from, to)

    internal fun isValidHttpVersion(span: ByteSpan, from: Int, to: Int): Boolean {
        return to - from == 8
            && span.at(from) == 'H'.code
            && span.at(from + 1) == 'T'.code
            && span.at(from + 2) == 'T'.code
            && span.at(from + 3) == 'P'.code
            && span.at(from + 4) == '/'.code
            && span.at(from + 5) == '1'.code
            && span.at(from + 6) == '.'.code
            && (span.at(from + 7) == '0'.code || span.at(from + 7) == '1'.code)
    }

    private fun allIn(table: BooleanArray, span: ByteBuffer): Boolean {
        val start = span.position()
        val end = span.limit()

        for (i in start until end) {
            if (!table[span.get(i).toInt() and 0xFF]) return false
        }

        return true
    }

}
