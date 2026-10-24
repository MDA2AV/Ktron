package ktron.glyph11

import ktron.glyph11.parser.ChunkResult
import ktron.glyph11.parser.ChunkedBodyStream
import ktron.glyph11.parser.ParserLimits
import ktron.glyph11.parser.ultrahardened.UltraHardenedParser
import ktron.glyph11.protocol.BinaryRequest
import ktron.glyph11.protocol.BinaryResponse
import ktron.glyph11.validation.BodyFraming
import ktron.glyph11.validation.BodyFramingDetector
import ktron.glyph11.validation.ChunkedBodyValidator
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

// Requests that used to be waited on forever or misread, through the offset API and the port's ByteBuffer API alike.
class Glyph11HardeningTests {
    private fun bytes(s: String) = s.toByteArray(Charsets.ISO_8859_1)

    // ---- a head that cannot end within maxTotalHeaderBytes is refused with 431, not waited on ----

    @Test
    fun `an unterminated head as long as the limit is refused with 431`() {
        val limits = ParserLimits.Default.copy(maxTotalHeaderBytes = 64)
        val head = bytes("GET / HTTP/1.1\r\nHost: x\r\nX-Pad: " + "a".repeat(64))
        for (length in 0 until 64) assertEquals(0, Glyph11.parse(head, 0, length, RequestHead(), limits), "length $length")
        for (length in 64..head.size) {
            val refused = assertFailsWith<HttpParseException>("length $length") { Glyph11.parse(head, 0, length, RequestHead(), limits) }
            assertEquals(431, refused.statusCode)
        }
    }

    @Test
    fun `a head that ends exactly at the limit is still accepted`() {
        val request = bytes("GET / HTTP/1.1\r\nHost: x\r\n\r\n")
        val limits = ParserLimits.Default.copy(maxTotalHeaderBytes = request.size)
        assertEquals(request.size, Glyph11.parse(request, 0, request.size, RequestHead(), limits))
        // all but its last byte: it can still end within the limit
        assertEquals(0, Glyph11.parse(request, 0, request.size - 1, RequestHead(), limits))
        val over = ParserLimits.Default.copy(maxTotalHeaderBytes = request.size - 1)
        assertEquals(431, assertFailsWith<HttpParseException> { Glyph11.parse(request, 0, request.size - 1, RequestHead(), over) }.statusCode)
    }

    @Test
    fun `a megabyte of header bytes without an end is refused under the default limits`() {
        val line = bytes("X-Filler: " + "f".repeat(1000) + "\r\n")
        val input = ByteArray(ParserLimits.Default.maxTotalHeaderBytes)
        bytes("GET / HTTP/1.1\r\n").copyInto(input)
        var at = 16
        while (at + line.size <= input.size) { line.copyInto(input, at); at += line.size }
        input.fill('f'.code.toByte(), at)
        assertEquals(0, Glyph11.parse(input, 0, input.size - 1, RequestHead()))
        assertEquals(431, assertFailsWith<HttpParseException> { Glyph11.parse(input, 0, input.size, RequestHead()) }.statusCode)
    }

    @Test
    fun `the ByteBuffer parsers refuse an unterminated head as long as the limit`() {
        val limits = ParserLimits.Default.copy(maxTotalHeaderBytes = 32)
        val request = ByteBuffer.wrap(bytes("GET / HTTP/1.1\r\nHost: x\r\nX: " + "a".repeat(8)))
        assertEquals(431, assertFailsWith<HttpParseException> { UltraHardenedParser.tryExtractFullHeader(request, BinaryRequest(), limits) }.statusCode)
        request.limit(31)
        assertEquals(-1, UltraHardenedParser.tryExtractFullHeader(request, BinaryRequest(), limits))

        val response = ByteBuffer.wrap(bytes("HTTP/1.1 200 OK\r\nServer: x\r\nX: " + "a".repeat(8)))
        assertEquals(431, assertFailsWith<HttpParseException> { UltraHardenedParser.tryExtractFullResponseHeader(response, BinaryResponse(), limits) }.statusCode)
    }

    @Test
    fun `a refusal is a shared exception, one per reason, with its status`() {
        val missingHost = bytes("GET / HTTP/1.1\r\n\r\n")
        val badValue = bytes("GET / HTTP/1.1\r\nHost: x\r\nX: a\u0000b\r\n\r\n")
        val first = assertFailsWith<HttpParseException> { Glyph11.parse(missingHost, 0, missingHost.size, RequestHead()) }
        val second = assertFailsWith<HttpParseException> { Glyph11.parse(missingHost, 0, missingHost.size, RequestHead()) }
        kotlin.test.assertSame(first, second)
        assertEquals(400, first.statusCode)
        assertEquals(0, first.stackTrace.size)
        val other = assertFailsWith<HttpParseException> { Glyph11.parse(badValue, 0, badValue.size, RequestHead()) }
        kotlin.test.assertNotSame(first, other)
        assertEquals("Header value contains invalid characters.", other.message)
    }

    // ---- chunk sizes are unsigned: 2^63 and up is refused, not read as its low bits ----

    private val topBitSizes = listOf("8000000000000005", "8000000000000000", "ffffffffffffffff", "FFFFFFFFFFFFFFFF", "800000000000000a")

    @Test
    fun `a chunk size with the top bit set is refused by decodeChunked`() {
        for (size in topBitSizes) {
            val body = bytes("$size\r\nhello\r\n0\r\n\r\n")
            val refused = assertFailsWith<HttpParseException>(size) { Glyph11.decodeChunked(body, 0, body.size, ByteBuilder()) }
            assertEquals(400, refused.statusCode)
        }
    }

    @Test
    fun `a chunk size with the top bit set is refused by ChunkedBodyStream and ChunkedBodyValidator`() {
        for (size in topBitSizes) {
            val body = bytes("$size\r\nhello\r\n0\r\n\r\n")
            assertFailsWith<HttpParseException>(size) { ChunkedBodyStream().tryReadChunk(ByteBuffer.wrap(body)) }
            assertFailsWith<HttpParseException>(size) { ChunkedBodyValidator.tryValidate(ByteBuffer.wrap(body)) }
        }
    }

    @Test
    fun `sixteen digits below the top bit are a size, however large`() {
        // leading zeros: 5
        val small = bytes("0000000000000005\r\nhello\r\n0\r\n\r\n")
        val into = ByteBuilder()
        assertEquals(small.size, Glyph11.decodeChunked(small, 0, small.size, into))
        assertEquals("hello", String(into.array, 0, into.length, Charsets.ISO_8859_1))
        assertEquals(small.size, ChunkedBodyValidator.tryValidate(ByteBuffer.wrap(small)))

        // 2^63 - 2: a chunk that has not all arrived, not an error and not a short read
        val huge = bytes("7ffffffffffffffe\r\n5\r\nhello\r\n0\r\n\r\n")
        assertEquals(0, Glyph11.decodeChunked(huge, 0, huge.size, ByteBuilder()))
        assertEquals(ChunkResult.NeedMoreData, ChunkedBodyStream().tryReadChunk(ByteBuffer.wrap(huge)))
        // the validator added the size to its position and overflowed: it took the chunk's data for the next size line
        assertEquals(-1, ChunkedBodyValidator.tryValidate(ByteBuffer.wrap(huge)))
    }

    // ---- a Content-Length the checks accept frames that length: framed as no body, the body would be read as the
    // next request ----

    // value -> the length it frames
    private val listedLengths = listOf(
        "5 ,5" to 5L, "5\t, 5" to 5L, "5 , 5 " to 5L, "5 ,5\r\nContent-Length: 5 ,5" to 5L,
        "9223372036854775807" to Long.MAX_VALUE,
        "9223372036854775808" to Long.MAX_VALUE, "9999999999999999999" to Long.MAX_VALUE,
        "18446744073709551615" to Long.MAX_VALUE, "9223372036854775808, 9223372036854775808" to Long.MAX_VALUE,
    )

    @Test
    fun `OWS before a list's comma and lengths of 2^63 and up still frame a length`() {
        for ((value, length) in listedLengths) {
            val request = bytes("POST / HTTP/1.1\r\nHost: x\r\nContent-Length: $value\r\n\r\n")
            val head = RequestHead()
            assertEquals(request.size, Glyph11.parse(request, 0, request.size, head), value)
            assertEquals(RequestHead.BODY_LENGTH, head.bodyFraming, value)
            assertEquals(length, head.contentLength, value)
        }
    }

    @Test
    fun `the ByteBuffer API frames them the same`() {
        for ((value, length) in listedLengths) {
            val request = BinaryRequest()
            val bytes = ByteBuffer.wrap(bytes("POST / HTTP/1.1\r\nHost: x\r\nContent-Length: $value\r\n\r\n"))
            UltraHardenedParser.tryExtractFullHeader(bytes, request, ParserLimits.Default)
            val framing = BodyFramingDetector.detectBodyFraming(request)
            assertEquals(BodyFraming.ContentLength, framing.framing, value)
            assertEquals(length, framing.contentLength, value)
        }
    }
}
