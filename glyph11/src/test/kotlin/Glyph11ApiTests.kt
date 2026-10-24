package ktron.glyph11

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

// The offset API Ktron parses with: positions into the caller's array, at any offset into it.
class Glyph11ApiTests {
    private fun field(input: ByteArray, start: Int, length: Int) = String(input, start, length, Charsets.ISO_8859_1)

    private fun at(prefix: String, request: String): Pair<ByteArray, Int> {
        val bytes = (prefix + request).toByteArray(Charsets.ISO_8859_1)
        return bytes to prefix.length
    }

    @Test
    fun `a GET with a query is located field by field`() {
        val (input, offset) = at("junk", "GET /baseline11?a=13&b=42 HTTP/1.1\r\nHost: localhost\r\nX-Two: v\r\n\r\nNEXT")
        val head = RequestHead()
        val n = Glyph11.parse(input, offset, input.size - offset, head)
        assertEquals(input.size - offset - 4, n)
        assertEquals("GET", field(input, head.methodStart, head.methodLength))
        assertEquals("/baseline11", field(input, head.pathStart, head.pathLength))
        assertEquals("a=13&b=42", field(input, head.queryStart, head.queryLength))
        assertEquals("HTTP/1.1", field(input, head.versionStart, head.versionLength))
        assertEquals(2, head.headerCount)
        assertEquals("X-Two", field(input, head.headerNameStart(1), head.headerNameLength(1)))
        assertEquals("v", field(input, head.headerValueStart(1), head.headerValueLength(1)))
        assertEquals(1, head.findHeader(input, "x-two".toByteArray()))
        assertEquals(2, head.queryCount)
        assertEquals("42", field(input, head.queryValueStart(1), head.queryValueLength(1)))
        assertEquals(1, head.findQuery(input, "b".toByteArray()))
        assertEquals(RequestHead.BODY_NONE, head.bodyFraming)
    }

    @Test
    fun `a target without a query has an empty one`() {
        val (input, offset) = at("", "GET /plain HTTP/1.1\r\nHost: h\r\n\r\n")
        val head = RequestHead()
        Glyph11.parse(input, offset, input.size, head)
        assertEquals("/plain", field(input, head.pathStart, head.pathLength))
        assertEquals(0, head.queryLength)
        assertEquals(0, head.queryCount)
    }

    @Test
    fun `body framing comes with the head`() {
        val (input, offset) = at("x", "POST /p HTTP/1.1\r\nHost: h\r\nContent-Length: 2\r\n\r\n20")
        val head = RequestHead()
        Glyph11.parse(input, offset, input.size - offset, head)
        assertEquals(RequestHead.BODY_LENGTH, head.bodyFraming)
        assertEquals(2L, head.contentLength)

        val (chunked, at) = at("", "POST /p HTTP/1.1\r\nHost: h\r\nTransfer-Encoding: chunked\r\n\r\n2\r\n20\r\n0\r\n\r\n")
        val head2 = RequestHead()
        val headLength = Glyph11.parse(chunked, at, chunked.size, head2)
        assertEquals(RequestHead.BODY_CHUNKED, head2.bodyFraming)

        val body = ByteBuilder()
        val wire = Glyph11.decodeChunked(chunked, headLength, chunked.size - headLength, body)
        assertEquals(chunked.size - headLength, wire)
        assertEquals("20", String(body.array, 0, body.length))
        assertEquals(0, Glyph11.decodeChunked(chunked, headLength, chunked.size - headLength - 2, body))
    }

    @Test
    fun `an incomplete head is 0 and a malformed one is refused with its status`() {
        val (partial, _) = at("", "GET / HTTP/1.1\r\nHost: h\r\n")
        assertEquals(0, Glyph11.parse(partial, 0, partial.size, RequestHead()))

        val (bad, _) = at("", "GET / HTTP/1.1\r\nHost: h\r\nContent-Length: 1\r\nTransfer-Encoding: chunked\r\n\r\n")
        val refused = assertFailsWith<HttpParseException> { Glyph11.parse(bad, 0, bad.size, RequestHead()) }
        assertEquals(400, refused.statusCode)
    }
}
