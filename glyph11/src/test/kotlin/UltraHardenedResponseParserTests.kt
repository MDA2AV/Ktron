package ktron.glyph11.tests

import ktron.glyph11.HttpParseException
import ktron.glyph11.parser.ParserLimits
import ktron.glyph11.parser.ultrahardened.UltraHardenedParser
import ktron.glyph11.protocol.BinaryResponse
import ktron.glyph11.validation.BodyFraming
import ktron.glyph11.validation.BodyFramingDetector
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for the RESPONSE half of UltraHardenedParser: the status line, the header rules it
 * shares with requests, the ones it must NOT share (Host), and body framing - which depends on
 * the request that produced the response and so cannot be decided from the response alone.
 *
 * The "multi" parameter selects the plain heap buffer (false) or the same bytes placed in the
 * middle of a larger direct buffer (true), as the port has no multi-segment input.
 */
class UltraHardenedResponseParserTests {

    private val response = BinaryResponse()
    private val defaults = ParserLimits.Default

    @AfterEach
    fun dispose() {
        response.close()
    }

    // ================================================================
    // Status line - RFC 9112 section 4
    // ================================================================

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun parses_StatusLine(multi: Boolean) {
        val (ok, _) = parse("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n", multi)

        assertTrue(ok)
        assertEquals(404, response.status)
        AssertAscii.equal("HTTP/1.1", response.version)
        AssertAscii.equal("404", response.statusCode)
        AssertAscii.equal("Not Found", response.reasonPhrase)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun parses_EmptyReasonPhrase(multi: Boolean) {
        // HTTP/2 and HTTP/3 have no reason phrase, so anything translating down emits none.
        val (ok, _) = parse("HTTP/1.1 200 \r\nContent-Length: 0\r\n\r\n", multi)

        assertTrue(ok)
        assertEquals(200, response.status)
        assertTrue(response.reasonPhrase.remaining() == 0)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun parses_MissingReasonPhraseAndSpace(multi: Boolean) {
        // The grammar asks for the SP even with no phrase; origins omit it and there is no
        // ambiguity in accepting that.
        val (ok, _) = parse("HTTP/1.1 204\r\n\r\n", multi)

        assertTrue(ok)
        assertEquals(204, response.status)
        assertTrue(response.reasonPhrase.remaining() == 0)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "HTTP/1.1 20 OK\r\n\r\n",
            "HTTP/1.1 2000 OK\r\n\r\n",
            "HTTP/1.1 2x0 OK\r\n\r\n",
            "HTTP/1.1  200 OK\r\n\r\n",
            "HTTP/1.1 099 OK\r\n\r\n",
            "HTTP/1.1\r\n\r\n",
        ]
    )
    fun throws_MalformedStatusCode(raw: String) {
        assertFailsWith<HttpParseException> { parse(raw, multiSegment = false) }
    }

    @Test
    fun throws_InvalidVersion() {
        val ex = assertFailsWith<HttpParseException> {
            parse("HTTP/9.9 200 OK\r\n\r\n", multiSegment = false)
        }
        assertEquals(505, ex.statusCode)
    }

    @Test
    fun throws_BareLfInStatusLine() {
        assertFailsWith<HttpParseException> {
            parse("HTTP/1.1 200 OK\nContent-Length: 0\r\n\r\n", multiSegment = false)
        }
    }

    @Test
    fun throws_ControlCharacterInReasonPhrase() {
        assertFailsWith<HttpParseException> {
            parse("HTTP/1.1 200 O\u0000K\r\n\r\n", multiSegment = false)
        }
    }

    @Test
    fun throws_ReasonPhraseOverLimit() {
        val reason = "x".repeat(40)
        val limits = defaults.copy(maxReasonPhraseLength = 8)

        val ex = assertFailsWith<HttpParseException> {
            parse("HTTP/1.1 200 $reason\r\n\r\n", multiSegment = false, limits = limits)
        }
        assertEquals(431, ex.statusCode)
    }

    // ================================================================
    // Header rules shared with requests
    // ================================================================

    @ParameterizedTest
    @ValueSource(
        strings = [
            "HTTP/1.1 200 OK\r\n Content-Length: 0\r\n\r\n",              // obs-fold
            "HTTP/1.1 200 OK\r\nContent-Length : 0\r\n\r\n",              // WS before colon
            "HTTP/1.1 200 OK\r\n: 0\r\n\r\n",                             // empty name
            "HTTP/1.1 200 OK\r\nContent-Length\r\n\r\n",                  // no colon
            "HTTP/1.1 200 OK\r\nContent-Length: 5\r\nContent-Length: 6\r\n\r\n",
            "HTTP/1.1 200 OK\r\nTransfer-Encoding: gzip\r\n\r\n",
        ]
    )
    fun throws_MalformedHeaders(raw: String) {
        assertFailsWith<HttpParseException> { parse(raw, multiSegment = false) }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun throws_TransferEncodingAndContentLength(multi: Boolean) {
        // Desync in this direction too: frame it one way at the proxy and the other at the
        // client, and the remainder becomes the head of the next response.
        assertFailsWith<HttpParseException> {
            parse("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nContent-Length: 5\r\n\r\n", multi)
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun accepts_ResponseWithoutHost(multi: Boolean) {
        // The Host rule is a REQUEST requirement; carrying it over would reject every response.
        val (ok, _) = parse("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n", multi)
        assertTrue(ok)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun returns_FalseWhenIncomplete(multi: Boolean) {
        val (ok, read) = parse("HTTP/1.1 200 OK\r\nContent-Length:", multi)
        assertFalse(ok)
        assertEquals(-1, read)
    }

    // ================================================================
    // Body framing - RFC 9112 section 6, which needs the request method
    // ================================================================

    @Test
    fun framing_HeadResponseHasNoBody() {
        parse("HTTP/1.1 200 OK\r\nContent-Length: 1234\r\n\r\n", multiSegment = false)

        // The Content-Length describes the body a GET would have returned. Framing on it reads
        // the NEXT response as this one's content.
        val framing = BodyFramingDetector.detectResponseBodyFraming(response, TestBuffers.buffer("HEAD"))
        assertEquals(BodyFraming.None, framing.framing)

        val asGet = BodyFramingDetector.detectResponseBodyFraming(response, TestBuffers.buffer("GET"))
        assertEquals(BodyFraming.ContentLength, asGet.framing)
        assertEquals(1234L, asGet.contentLength)
    }

    @ParameterizedTest
    @ValueSource(ints = [100, 199, 204, 304])
    fun framing_StatusesThatCannotHaveABody(status: Int) {
        parse("HTTP/1.1 $status X\r\nContent-Length: 99\r\n\r\n", multiSegment = false)

        val framing = BodyFramingDetector.detectResponseBodyFraming(response, TestBuffers.buffer("GET"))
        assertEquals(BodyFraming.None, framing.framing)
    }

    @Test
    fun framing_ConnectTunnelIsNotABody() {
        parse("HTTP/1.1 200 Connection Established\r\n\r\n", multiSegment = false)

        val framing = BodyFramingDetector.detectResponseBodyFraming(response, TestBuffers.buffer("CONNECT"))
        assertEquals(BodyFraming.None, framing.framing)
    }

    @Test
    fun framing_ChunkedWinsOverNothing() {
        parse("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n", multiSegment = false)

        val framing = BodyFramingDetector.detectResponseBodyFraming(response, TestBuffers.buffer("GET"))
        assertEquals(BodyFraming.Chunked, framing.framing)
    }

    @Test
    fun framing_ZeroContentLengthIsNoBody() {
        parse("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n", multiSegment = false)

        val framing = BodyFramingDetector.detectResponseBodyFraming(response, TestBuffers.buffer("GET"))
        assertEquals(BodyFraming.None, framing.framing)
    }

    @Test
    fun framing_NoFramingHeaderRunsUntilClose() {
        // The case with no request equivalent: a response with no framing header is delimited by
        // the connection closing, which is all HTTP/1.0 origins ever had.
        parse("HTTP/1.1 200 OK\r\nServer: origin\r\n\r\n", multiSegment = false)

        val framing = BodyFramingDetector.detectResponseBodyFraming(response, TestBuffers.buffer("GET"))
        assertEquals(BodyFraming.UntilClose, framing.framing)
    }

    // ================================================================
    // Helpers
    // ================================================================

    private fun parse(raw: String, multiSegment: Boolean, limits: ParserLimits = defaults): Pair<Boolean, Int> {
        val bytes = TestBuffers.b(raw)

        val result = UltraHardenedParser.tryExtractFullResponseHeader(
            TestBuffers.input(bytes, multiSegment), response, limits
        )

        return Pair(result >= 0, result)
    }

}
