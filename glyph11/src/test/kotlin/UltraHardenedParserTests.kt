package ktron.glyph11.tests

import ktron.glyph11.HttpParseException
import ktron.glyph11.parser.ParserLimits
import ktron.glyph11.parser.ultrahardened.UltraHardenedParser
import ktron.glyph11.protocol.BinaryRequest
import ktron.glyph11.protocol.KeyValueList
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import java.nio.ByteBuffer
import java.util.stream.Stream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for UltraHardenedParser (fused parse + semantic validation).
 * Performs full RFC 9110/9112 structural parsing with all semantic
 * checks enforced inline during parsing.
 * Upstream this is split across partial files:
 *   - UltraHardenedParser.Parsing.cs     - core parsing (valid requests need Host)
 *   - UltraHardenedParser.Semantics.cs   - fused semantic checks (throw on violation)
 *   - UltraHardenedParser.Limits.cs      - resource limits
 *   - UltraHardenedParser.StatusCode.cs  - HTTP status codes on exceptions
 *
 * The "multi" parameter selects the plain heap buffer (false) or the same bytes placed in the
 * middle of a larger direct buffer (true), as the port has no multi-segment input.
 */
class UltraHardenedParserTests {

    private val request = BinaryRequest()
    private val defaults = ParserLimits.Default

    @AfterEach
    fun dispose() {
        request.close()
    }

    private fun parse(raw: String, multiSegment: Boolean): Pair<Boolean, Int> =
        parse(raw, multiSegment, defaults)

    private fun parse(raw: String, multiSegment: Boolean, limits: ParserLimits): Pair<Boolean, Int> {
        val bytes = TestBuffers.b(raw)

        val result = UltraHardenedParser.tryExtractFullHeader(TestBuffers.input(bytes, multiSegment), request, limits)

        return Pair(result >= 0, result)
    }

    private fun assertHeader(headers: KeyValueList, index: Int, expectedKey: String, expectedValue: String) {
        val kv = headers[index]
        AssertAscii.equal(expectedKey, kv.first)
        AssertAscii.equal(expectedValue, kv.second)
    }

    private fun assertQueryParam(query: KeyValueList, index: Int, expectedKey: String, expectedValue: String) {
        val kv = query[index]
        AssertAscii.equal(expectedKey, kv.first)
        AssertAscii.equal(expectedValue, kv.second)
    }

    // ================================================================
    // Incomplete requests - returns false
    // ================================================================

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun returnsFalse_EmptyInput(multi: Boolean) {
        val (ok, _) = parse("", multi)
        assertFalse(ok)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun returnsFalse_MissingDoubleCrlfTerminator(multi: Boolean) {
        val (ok, _) = parse("GET / HTTP/1.1\r\nHost: x\r\n", multi)
        assertFalse(ok)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun returnsFalse_OnlyRequestLine(multi: Boolean) {
        val (ok, _) = parse("GET / HTTP/1.1\r\n", multi)
        assertFalse(ok)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun returnsFalse_BareLineFeedInsteadOfCrlf(multi: Boolean) {
        val (ok, _) = parse("GET / HTTP/1.1\nHost: x\n\n", multi)
        assertFalse(ok)
    }

    // ================================================================
    // Invalid request lines - throws
    // ================================================================

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun throws_RequestLineWithNoSpaces(multi: Boolean) {
        assertFailsWith<HttpParseException> {
            parse("INVALIDLINE\r\n\r\n", multi)
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun throws_RequestLineWithSingleSpace(multi: Boolean) {
        assertFailsWith<HttpParseException> {
            parse("GET /path\r\n\r\n", multi)
        }
    }

    // ================================================================
    // Method parsing
    // ================================================================

    @ParameterizedTest
    @MethodSource("parsesHttpMethodData")
    fun parsesHttpMethod(method: String, multi: Boolean) {
        val (ok, _) = parse("$method / HTTP/1.1\r\nHost: localhost\r\n\r\n", multi)
        assertTrue(ok)
        AssertAscii.equal(method, request.method)
    }

    // ================================================================
    // Path parsing
    // ================================================================

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun parsesRootPath(multi: Boolean) {
        val (ok, _) = parse("GET / HTTP/1.1\r\nHost: localhost\r\n\r\n", multi)
        assertTrue(ok)
        AssertAscii.equal("/", request.path)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun parsesNestedPath(multi: Boolean) {
        val (ok, _) = parse("GET /api/v1/users HTTP/1.1\r\nHost: localhost\r\n\r\n", multi)
        assertTrue(ok)
        AssertAscii.equal("/api/v1/users", request.path)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun separatesPathFromQueryString(multi: Boolean) {
        val (ok, _) = parse("GET /search?q=test HTTP/1.1\r\nHost: localhost\r\n\r\n", multi)
        assertTrue(ok)
        AssertAscii.equal("/search", request.path)
    }

    // ================================================================
    // Query string parsing
    // ================================================================

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun parsesSingleQueryParameter(multi: Boolean) {
        val (ok, _) = parse("GET /p?key=val HTTP/1.1\r\nHost: localhost\r\n\r\n", multi)
        assertTrue(ok)
        assertEquals(1, request.queryParameters.count)
        assertQueryParam(request.queryParameters, 0, "key", "val")
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun parsesMultipleQueryParameters(multi: Boolean) {
        val (ok, _) = parse("GET /p?a=1&b=2&c=3 HTTP/1.1\r\nHost: localhost\r\n\r\n", multi)
        assertTrue(ok)
        assertEquals(3, request.queryParameters.count)
        assertQueryParam(request.queryParameters, 0, "a", "1")
        assertQueryParam(request.queryParameters, 1, "b", "2")
        assertQueryParam(request.queryParameters, 2, "c", "3")
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun parsesQueryParameterWithEmptyValue(multi: Boolean) {
        val (ok, _) = parse("GET /p?key= HTTP/1.1\r\nHost: localhost\r\n\r\n", multi)
        assertTrue(ok)
        assertEquals(1, request.queryParameters.count)
        assertQueryParam(request.queryParameters, 0, "key", "")
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun parsesQueryParamWithoutValue(multi: Boolean) {
        val (ok, _) = parse("GET /p?ok=1&flag&also=2 HTTP/1.1\r\nHost: localhost\r\n\r\n", multi)
        assertTrue(ok)
        assertEquals(3, request.queryParameters.count)
        assertQueryParam(request.queryParameters, 0, "ok", "1")
        assertQueryParam(request.queryParameters, 1, "flag", "")
        assertQueryParam(request.queryParameters, 2, "also", "2")
    }

    // ================================================================
    // Header field parsing
    // ================================================================

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun parsesSingleHeader(multi: Boolean) {
        val (ok, _) = parse("GET / HTTP/1.1\r\nHost: localhost\r\n\r\n", multi)
        assertTrue(ok)
        assertEquals(1, request.headers.count)
        assertHeader(request.headers, 0, "Host", "localhost")
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun parsesMultipleHeaders(multi: Boolean) {
        val raw =
            "GET / HTTP/1.1\r\n" +
            "Host: localhost\r\n" +
            "Content-Type: text/html\r\n" +
            "Accept: */*\r\n" +
            "\r\n"

        val (ok, _) = parse(raw, multi)
        assertTrue(ok)
        assertEquals(3, request.headers.count)
        assertHeader(request.headers, 0, "Host", "localhost")
        assertHeader(request.headers, 1, "Content-Type", "text/html")
        assertHeader(request.headers, 2, "Accept", "*/*")
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun trimsLeadingSpacesFromHeaderValue(multi: Boolean) {
        val (ok, _) = parse("GET / HTTP/1.1\r\nHost: localhost\r\nKey:   value\r\n\r\n", multi)
        assertTrue(ok)
        assertHeader(request.headers, 1, "Key", "value")
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun parsesEmptyHeaderValue(multi: Boolean) {
        val (ok, _) = parse("GET / HTTP/1.1\r\nHost: localhost\r\nX-Empty:\r\n\r\n", multi)
        assertTrue(ok)
        assertEquals(2, request.headers.count)
        assertHeader(request.headers, 1, "X-Empty", "")
    }

    // ================================================================
    // Version parsing
    // ================================================================

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun storesHttpVersion(multi: Boolean) {
        val (ok, _) = parse("GET / HTTP/1.1\r\nHost: localhost\r\n\r\n", multi)
        assertTrue(ok)
        AssertAscii.equal("HTTP/1.1", request.version)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun acceptsHttp10(multi: Boolean) {
        val (ok, _) = parse("GET / HTTP/1.0\r\nHost: localhost\r\n\r\n", multi)
        assertTrue(ok)
        AssertAscii.equal("HTTP/1.0", request.version)
    }

    // ================================================================
    // Bytes consumed
    // ================================================================

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun reportsCorrectBytesConsumed(multi: Boolean) {
        val raw = "GET / HTTP/1.1\r\nHost: localhost\r\n\r\n"
        val (ok, bytesRead) = parse(raw, multi)
        assertTrue(ok)
        assertEquals(raw.length - 1, bytesRead)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun bytesConsumedExcludesBody(multi: Boolean) {
        val header = "POST / HTTP/1.1\r\nHost: localhost\r\nContent-Length: 11\r\n\r\n"
        val raw = header + "BodyContent"
        val (ok, bytesRead) = parse(raw, multi)
        assertTrue(ok)
        assertEquals(header.length - 1, bytesRead)
    }

    // ================================================================
    // Full realistic request
    // ================================================================

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun parsesCompleteRealisticRequest(multi: Boolean) {
        val header =
            "POST /api/users?page=1&limit=50 HTTP/1.1\r\n" +
            "Host: example.com\r\n" +
            "Content-Type: application/json\r\n" +
            "Content-Length: 27\r\n" +
            "Authorization: Bearer tok123\r\n" +
            "Accept: application/json\r\n" +
            "\r\n"

        val raw = header + "{\"name\":\"test\"}"

        val (ok, bytesRead) = parse(raw, multi)
        assertTrue(ok)

        AssertAscii.equal("POST", request.method)
        AssertAscii.equal("/api/users", request.path)

        assertEquals(2, request.queryParameters.count)
        assertQueryParam(request.queryParameters, 0, "page", "1")
        assertQueryParam(request.queryParameters, 1, "limit", "50")

        assertEquals(5, request.headers.count)
        assertHeader(request.headers, 0, "Host", "example.com")
        assertHeader(request.headers, 1, "Content-Type", "application/json")
        assertHeader(request.headers, 2, "Content-Length", "27")
        assertHeader(request.headers, 3, "Authorization", "Bearer tok123")
        assertHeader(request.headers, 4, "Accept", "application/json")

        assertEquals(header.length - 1, bytesRead)
    }

    // ================================================================
    // Entry point dispatch
    // ================================================================

    // Upstream these two tests verify the dispatch of ReadOnlySequence input to the single
    // buffer or linearizing code path. The port has a single entry point for contiguous input,
    // so the same request is parsed from a plain buffer and from the middle of a larger one.

    @Test
    fun dispatchRoutesSingleSegmentToROM() {
        val bytes = TestBuffers.b("GET / HTTP/1.1\r\nHost: localhost\r\n\r\n")
        val input = ByteBuffer.wrap(bytes)

        val result = UltraHardenedParser.tryExtractFullHeader(input, request, defaults)
        assertTrue(result >= 0)
        AssertAscii.equal("GET", request.method)
    }

    @Test
    fun dispatchRoutesMultiSegmentToLinearized() {
        val bytes = TestBuffers.b("GET / HTTP/1.1\r\nHost: localhost\r\n\r\n")
        val input = TestBuffers.input(bytes, true)

        val result = UltraHardenedParser.tryExtractFullHeader(input, request, defaults)
        assertTrue(result >= 0)
        AssertAscii.equal("GET", request.method)
    }

    // ================================================================
    // CONNECT method rejection - RFC 9110 section 9.3.6
    // ================================================================

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun throws_ConnectMethod(multi: Boolean) {
        assertFailsWith<HttpParseException> {
            parse("CONNECT host:443 HTTP/1.1\r\nHost: host:443\r\n\r\n", multi)
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun accepts_GetMethod_NotConnect(multi: Boolean) {
        val (ok, _) = parse("GET / HTTP/1.1\r\nHost: x\r\n\r\n", multi)
        assertTrue(ok)
    }

    // ================================================================
    // Asterisk-form - RFC 9112 section 3.2.4
    // ================================================================

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun throws_AsteriskFormWithGet(multi: Boolean) {
        assertFailsWith<HttpParseException> {
            parse("GET * HTTP/1.1\r\nHost: x\r\n\r\n", multi)
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun throws_AsteriskFormWithPost(multi: Boolean) {
        assertFailsWith<HttpParseException> {
            parse("POST * HTTP/1.1\r\nHost: x\r\n\r\n", multi)
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun accepts_AsteriskFormWithOptions(multi: Boolean) {
        val (ok, _) = parse("OPTIONS * HTTP/1.1\r\nHost: x\r\n\r\n", multi)
        assertTrue(ok)
        AssertAscii.equal("*", request.path)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun accepts_NormalPathNotAsterisk(multi: Boolean) {
        val (ok, _) = parse("GET / HTTP/1.1\r\nHost: x\r\n\r\n", multi)
        assertTrue(ok)
    }

    // ================================================================
    // Path semantics - fragment (#)
    // ================================================================

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun throws_FragmentInPath(multi: Boolean) {
        assertFailsWith<HttpParseException> {
            parse("GET /path#frag HTTP/1.1\r\nHost: x\r\n\r\n", multi)
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun accepts_PathWithoutFragment(multi: Boolean) {
        val (ok, _) = parse("GET /path HTTP/1.1\r\nHost: x\r\n\r\n", multi)
        assertTrue(ok)
    }

    // ================================================================
    // Path semantics - backslash
    // ================================================================

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun throws_BackslashInPath(multi: Boolean) {
        assertFailsWith<HttpParseException> {
            parse("GET /api\\..\\etc HTTP/1.1\r\nHost: x\r\n\r\n", multi)
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun accepts_ForwardSlashesOnly(multi: Boolean) {
        val (ok, _) = parse("GET /api/users HTTP/1.1\r\nHost: x\r\n\r\n", multi)
        assertTrue(ok)
    }

    // ================================================================
    // Path semantics - double encoding (%25)
    // ================================================================

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun throws_DoubleEncodingInPath(multi: Boolean) {
        assertFailsWith<HttpParseException> {
            parse("GET /api/%252e%252e HTTP/1.1\r\nHost: x\r\n\r\n", multi)
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun accepts_SingleEncodedPath(multi: Boolean) {
        val (ok, _) = parse("GET /api/%2e%2e HTTP/1.1\r\nHost: x\r\n\r\n", multi)
        assertTrue(ok)
    }

    // ================================================================
    // Path semantics - encoded null byte (%00)
    // ================================================================

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun throws_EncodedNullByteInPath(multi: Boolean) {
        assertFailsWith<HttpParseException> {
            parse("GET /file.txt%00.jpg HTTP/1.1\r\nHost: x\r\n\r\n", multi)
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun accepts_NoEncodedNullByte(multi: Boolean) {
        val (ok, _) = parse("GET /file.txt HTTP/1.1\r\nHost: x\r\n\r\n", multi)
        assertTrue(ok)
    }

    // ================================================================
    // Path semantics - dot segments
    // ================================================================

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun throws_DotSegment_ParentTraversal(multi: Boolean) {
        assertFailsWith<HttpParseException> {
            parse("GET /api/../etc/passwd HTTP/1.1\r\nHost: x\r\n\r\n", multi)
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun throws_DotSegment_CurrentDir(multi: Boolean) {
        assertFailsWith<HttpParseException> {
            parse("GET /api/./users HTTP/1.1\r\nHost: x\r\n\r\n", multi)
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun throws_DotSegment_TrailingDotDot(multi: Boolean) {
        assertFailsWith<HttpParseException> {
            parse("GET /api/.. HTTP/1.1\r\nHost: x\r\n\r\n", multi)
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun throws_DotSegment_TrailingDot(multi: Boolean) {
        assertFailsWith<HttpParseException> {
            parse("GET /api/. HTTP/1.1\r\nHost: x\r\n\r\n", multi)
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun accepts_DotsInFilename(multi: Boolean) {
        val (ok, _) = parse("GET /api/file.tar.gz HTTP/1.1\r\nHost: x\r\n\r\n", multi)
        assertTrue(ok)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun accepts_DotSuffix(multi: Boolean) {
        val (ok, _) = parse("GET /api/test... HTTP/1.1\r\nHost: x\r\n\r\n", multi)
        assertTrue(ok)
    }

    // ================================================================
    // Path semantics - overlong UTF-8 (rejected by IsValidRequestTarget first)
    // ================================================================

    @Test
    fun throws_OverlongUtf8_C0() {
        // 0xC0 0xAF: overlong encoding of '/' - rejected at request-target validation
        val header = TestBuffers.b("GET ")
        val path = byteArrayOf(0x2F, 0xC0.toByte(), 0xAF.toByte())
        val tail = TestBuffers.b(" HTTP/1.1\r\nHost: x\r\n\r\n")
        val all = ByteArray(header.size + path.size + tail.size)
        header.copyInto(all, 0)
        path.copyInto(all, header.size)
        tail.copyInto(all, header.size + path.size)

        val rom = ByteBuffer.wrap(all)
        assertFailsWith<HttpParseException> {
            UltraHardenedParser.tryExtractFullHeader(rom, request, defaults)
        }
    }

    @Test
    fun throws_OverlongUtf8_C1() {
        val header = TestBuffers.b("GET ")
        val path = byteArrayOf(0x2F, 0xC1.toByte(), 0x9C.toByte())
        val tail = TestBuffers.b(" HTTP/1.1\r\nHost: x\r\n\r\n")
        val all = ByteArray(header.size + path.size + tail.size)
        header.copyInto(all, 0)
        path.copyInto(all, header.size)
        tail.copyInto(all, header.size + path.size)

        val rom = ByteBuffer.wrap(all)
        assertFailsWith<HttpParseException> {
            UltraHardenedParser.tryExtractFullHeader(rom, request, defaults)
        }
    }

    // ================================================================
    // Host header - exactly one required (RFC 9112 section 3.2)
    // ================================================================

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun throws_MissingHostHeader(multi: Boolean) {
        assertFailsWith<HttpParseException> {
            parse("GET / HTTP/1.1\r\nAccept: */*\r\n\r\n", multi)
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun throws_NoHeaders_MissingHost(multi: Boolean) {
        assertFailsWith<HttpParseException> {
            parse("GET / HTTP/1.1\r\n\r\n", multi)
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun throws_DuplicateHostHeaders(multi: Boolean) {
        assertFailsWith<HttpParseException> {
            parse("GET / HTTP/1.1\r\nHost: a.com\r\nHost: b.com\r\n\r\n", multi)
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun accepts_ExactlyOneHost(multi: Boolean) {
        val (ok, _) = parse("GET / HTTP/1.1\r\nHost: example.com\r\n\r\n", multi)
        assertTrue(ok)
    }

    // ================================================================
    // Host header format - RFC 9110 section 7.2
    // ================================================================

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun throws_HostWithUserinfo(multi: Boolean) {
        assertFailsWith<HttpParseException> {
            parse("GET / HTTP/1.1\r\nHost: user@example.com\r\n\r\n", multi)
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun throws_HostWithPath(multi: Boolean) {
        assertFailsWith<HttpParseException> {
            parse("GET / HTTP/1.1\r\nHost: example.com/path\r\n\r\n", multi)
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun accepts_HostWithPort(multi: Boolean) {
        val (ok, _) = parse("GET / HTTP/1.1\r\nHost: example.com:8080\r\n\r\n", multi)
        assertTrue(ok)
    }

    // ================================================================
    // Content-Length format - RFC 9110 section 8.6
    // ================================================================

    @ParameterizedTest
    @MethodSource("accepts_ValidContentLengthData")
    fun accepts_ValidContentLength(value: String, multi: Boolean) {
        val (ok, _) = parse("GET / HTTP/1.1\r\nHost: x\r\nContent-Length: $value\r\n\r\n", multi)
        assertTrue(ok)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun throws_NonDigitContentLength(multi: Boolean) {
        assertFailsWith<HttpParseException> {
            parse("GET / HTTP/1.1\r\nHost: x\r\nContent-Length: abc\r\n\r\n", multi)
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun throws_NegativeContentLength(multi: Boolean) {
        assertFailsWith<HttpParseException> {
            parse("GET / HTTP/1.1\r\nHost: x\r\nContent-Length: -1\r\n\r\n", multi)
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun throws_EmptyContentLength(multi: Boolean) {
        assertFailsWith<HttpParseException> {
            parse("GET / HTTP/1.1\r\nHost: x\r\nContent-Length:\r\n\r\n", multi)
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun throws_ContentLengthWithSpaces(multi: Boolean) {
        assertFailsWith<HttpParseException> {
            parse("GET / HTTP/1.1\r\nHost: x\r\nContent-Length: 1 2\r\n\r\n", multi)
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun throws_ContentLengthTrailingComma(multi: Boolean) {
        assertFailsWith<HttpParseException> {
            parse("GET / HTTP/1.1\r\nHost: x\r\nContent-Length: 42,\r\n\r\n", multi)
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun throws_ContentLengthCommaOnly(multi: Boolean) {
        assertFailsWith<HttpParseException> {
            parse("GET / HTTP/1.1\r\nHost: x\r\nContent-Length: ,\r\n\r\n", multi)
        }
    }

    // ================================================================
    // Content-Length leading zeros
    // ================================================================

    @ParameterizedTest
    @MethodSource("throws_ContentLengthWithLeadingZerosData")
    fun throws_ContentLengthWithLeadingZeros(value: String, multi: Boolean) {
        assertFailsWith<HttpParseException> {
            parse("GET / HTTP/1.1\r\nHost: x\r\nContent-Length: $value\r\n\r\n", multi)
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun accepts_SingleZeroContentLength(multi: Boolean) {
        val (ok, _) = parse("GET / HTTP/1.1\r\nHost: x\r\nContent-Length: 0\r\n\r\n", multi)
        assertTrue(ok)
    }

    // ================================================================
    // Content-Length comma-separated conflicts - RFC 9112 section 6.2
    // ================================================================

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun accepts_CommaSeparatedCL_Same(multi: Boolean) {
        val (ok, _) = parse("GET / HTTP/1.1\r\nHost: x\r\nContent-Length: 42, 42\r\n\r\n", multi)
        assertTrue(ok)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun throws_CommaSeparatedCL_Different(multi: Boolean) {
        assertFailsWith<HttpParseException> {
            parse("GET / HTTP/1.1\r\nHost: x\r\nContent-Length: 42, 0\r\n\r\n", multi)
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun accepts_CommaSeparatedCL_Zeros(multi: Boolean) {
        val (ok, _) = parse("GET / HTTP/1.1\r\nHost: x\r\nContent-Length: 0, 0\r\n\r\n", multi)
        assertTrue(ok)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun throws_CommaSeparatedCL_LeadingZeroInSecond(multi: Boolean) {
        assertFailsWith<HttpParseException> {
            parse("GET / HTTP/1.1\r\nHost: x\r\nContent-Length: 42, 042\r\n\r\n", multi)
        }
    }

    // ================================================================
    // Conflicting Content-Length headers - RFC 9110 section 8.6
    // ================================================================

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun accepts_DuplicateSameCL(multi: Boolean) {
        val raw =
            "GET / HTTP/1.1\r\n" +
            "Host: x\r\n" +
            "Content-Length: 10\r\n" +
            "Content-Length: 10\r\n" +
            "\r\n"
        val (ok, _) = parse(raw, multi)
        assertTrue(ok)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun throws_ConflictingCLHeaders(multi: Boolean) {
        val raw =
            "GET / HTTP/1.1\r\n" +
            "Host: x\r\n" +
            "Content-Length: 10\r\n" +
            "Content-Length: 20\r\n" +
            "\r\n"
        assertFailsWith<HttpParseException> { parse(raw, multi) }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun throws_ConflictingCLHeaders_CaseInsensitive(multi: Boolean) {
        val raw =
            "GET / HTTP/1.1\r\n" +
            "Host: x\r\n" +
            "content-length: 10\r\n" +
            "Content-Length: 20\r\n" +
            "\r\n"
        assertFailsWith<HttpParseException> { parse(raw, multi) }
    }

    // ================================================================
    // Transfer-Encoding validation - RFC 9112 section 6.1
    // ================================================================

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun accepts_ValidTE_Chunked(multi: Boolean) {
        val (ok, _) = parse("GET / HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\n\r\n", multi)
        assertTrue(ok)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun accepts_ValidTE_ChunkedCaseInsensitive(multi: Boolean) {
        val (ok, _) = parse("GET / HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: Chunked\r\n\r\n", multi)
        assertTrue(ok)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun accepts_ValidTE_ChunkedWithOWS(multi: Boolean) {
        val (ok, _) = parse("GET / HTTP/1.1\r\nHost: x\r\nTransfer-Encoding:  chunked \r\n\r\n", multi)
        assertTrue(ok)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun throws_InvalidTE_Obfuscated(multi: Boolean) {
        assertFailsWith<HttpParseException> {
            parse("GET / HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: xchunked\r\n\r\n", multi)
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun throws_InvalidTE_Quoted(multi: Boolean) {
        assertFailsWith<HttpParseException> {
            parse("GET / HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: \"chunked\"\r\n\r\n", multi)
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun throws_InvalidTE_Identity(multi: Boolean) {
        assertFailsWith<HttpParseException> {
            parse("GET / HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: identity\r\n\r\n", multi)
        }
    }

    // ================================================================
    // Transfer-Encoding + Content-Length - RFC 9112 section 6.1
    // ================================================================

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun throws_BothTEandCL(multi: Boolean) {
        val raw =
            "GET / HTTP/1.1\r\n" +
            "Host: x\r\n" +
            "Transfer-Encoding: chunked\r\n" +
            "Content-Length: 10\r\n" +
            "\r\n"
        assertFailsWith<HttpParseException> { parse(raw, multi) }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun throws_BothTEandCL_ReverseOrder(multi: Boolean) {
        val raw =
            "GET / HTTP/1.1\r\n" +
            "Host: x\r\n" +
            "Content-Length: 10\r\n" +
            "Transfer-Encoding: chunked\r\n" +
            "\r\n"
        assertFailsWith<HttpParseException> { parse(raw, multi) }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun throws_BothTEandCL_CaseInsensitive(multi: Boolean) {
        val raw =
            "GET / HTTP/1.1\r\n" +
            "Host: x\r\n" +
            "transfer-encoding: chunked\r\n" +
            "content-length: 10\r\n" +
            "\r\n"
        assertFailsWith<HttpParseException> { parse(raw, multi) }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun accepts_OnlyCL_NoTE(multi: Boolean) {
        val (ok, _) = parse("GET / HTTP/1.1\r\nHost: x\r\nContent-Length: 10\r\n\r\n", multi)
        assertTrue(ok)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun accepts_OnlyTE_NoCL(multi: Boolean) {
        val (ok, _) = parse("GET / HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\n\r\n", multi)
        assertTrue(ok)
    }

    // ================================================================
    // Content-Length header name case insensitivity
    // ================================================================

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun accepts_MixedCaseCL(multi: Boolean) {
        val (ok, _) = parse("GET / HTTP/1.1\r\nHost: x\r\ncontent-LENGTH: 10\r\n\r\n", multi)
        assertTrue(ok)
        assertHeader(request.headers, 1, "content-LENGTH", "10")
    }

    // ================================================================
    // Structural validation carried over (smoke tests)
    // ================================================================

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun throws_ObsFoldWithSpace(multi: Boolean) {
        assertFailsWith<HttpParseException> {
            parse("GET / HTTP/1.1\r\nHost: x\r\n continued\r\n\r\n", multi)
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun throws_SpaceBeforeColon(multi: Boolean) {
        assertFailsWith<HttpParseException> {
            parse("GET / HTTP/1.1\r\nHost : localhost\r\n\r\n", multi)
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun throws_DoubleSpaceAfterMethod(multi: Boolean) {
        assertFailsWith<HttpParseException> {
            parse("GET  / HTTP/1.1\r\nHost: x\r\n\r\n", multi)
        }
    }

    @ParameterizedTest
    @MethodSource("throws_InvalidHttpVersionData")
    fun throws_InvalidHttpVersion(version: String, multi: Boolean) {
        assertFailsWith<HttpParseException> {
            parse("GET / $version\r\nHost: x\r\n\r\n", multi)
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun throws_HeaderNameWithControlChar(multi: Boolean) {
        assertFailsWith<HttpParseException> {
            parse("GET / HTTP/1.1\r\nHost: x\r\nBad\u0000Name: val\r\n\r\n", multi)
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun throws_HeaderValueWithNullByte(multi: Boolean) {
        assertFailsWith<HttpParseException> {
            parse("GET / HTTP/1.1\r\nHost: x\r\nKey: val\u0000ue\r\n\r\n", multi)
        }
    }

    // ================================================================
    // Resource limits
    // ================================================================

    // ---- Header count ----

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun throws_WhenHeaderCountExceedsLimit(multi: Boolean) {
        val limits = defaults.copy(maxHeaderCount = 2)
        val raw =
            "GET / HTTP/1.1\r\n" +
            "Host: x\r\n" +
            "H1: v1\r\n" +
            "H2: v2\r\n" +
            "\r\n"

        assertFailsWith<HttpParseException> {
            parse(raw, multi, limits)
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun acceptsExactlyMaxHeaderCount(multi: Boolean) {
        val limits = defaults.copy(maxHeaderCount = 2)
        val raw =
            "GET / HTTP/1.1\r\n" +
            "Host: x\r\n" +
            "H1: v1\r\n" +
            "\r\n"

        val (ok, _) = parse(raw, multi, limits)
        assertTrue(ok)
        assertEquals(2, request.headers.count)
    }

    // ---- Header name length ----

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun throws_WhenHeaderNameExceedsLimit(multi: Boolean) {
        val limits = defaults.copy(maxHeaderNameLength = 4)
        val raw = "GET / HTTP/1.1\r\nHost: x\r\nLongName: val\r\n\r\n"

        assertFailsWith<HttpParseException> {
            parse(raw, multi, limits)
        }
    }

    // ---- Header value length ----

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun throws_WhenHeaderValueExceedsLimit(multi: Boolean) {
        val limits = defaults.copy(maxHeaderValueLength = 3)
        val raw = "GET / HTTP/1.1\r\nHost: x\r\nKey: longvalue\r\n\r\n"

        assertFailsWith<HttpParseException> {
            parse(raw, multi, limits)
        }
    }

    // ---- Method length ----

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun throws_WhenMethodExceedsLimit(multi: Boolean) {
        val limits = defaults.copy(maxMethodLength = 3)
        val raw = "POST / HTTP/1.1\r\nHost: x\r\n\r\n"

        assertFailsWith<HttpParseException> {
            parse(raw, multi, limits)
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun acceptsMethodAtExactLimit(multi: Boolean) {
        val limits = defaults.copy(maxMethodLength = 3)
        val raw = "GET / HTTP/1.1\r\nHost: x\r\n\r\n"

        val (ok, _) = parse(raw, multi, limits)
        assertTrue(ok)
    }

    // ---- URL length ----

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun throws_WhenUrlExceedsLimit(multi: Boolean) {
        val limits = defaults.copy(maxUrlLength = 5)
        val raw = "GET /toolong HTTP/1.1\r\nHost: x\r\n\r\n"

        assertFailsWith<HttpParseException> {
            parse(raw, multi, limits)
        }
    }

    // ---- Query parameter count ----

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun throws_WhenQueryParamCountExceedsLimit(multi: Boolean) {
        val limits = defaults.copy(maxQueryParameterCount = 2)
        val raw = "GET /p?a=1&b=2&c=3 HTTP/1.1\r\nHost: x\r\n\r\n"

        assertFailsWith<HttpParseException> {
            parse(raw, multi, limits)
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun acceptsExactlyMaxQueryParamCount(multi: Boolean) {
        val limits = defaults.copy(maxQueryParameterCount = 2)
        val raw = "GET /p?a=1&b=2 HTTP/1.1\r\nHost: x\r\n\r\n"

        val (ok, _) = parse(raw, multi, limits)
        assertTrue(ok)
        assertEquals(2, request.queryParameters.count)
    }

    // ---- Total header bytes ----

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun throws_WhenTotalHeaderBytesExceedsLimit(multi: Boolean) {
        val limits = defaults.copy(maxTotalHeaderBytes = 20)
        val raw = "GET / HTTP/1.1\r\nHost: localhost\r\n\r\n"

        assertFailsWith<HttpParseException> {
            parse(raw, multi, limits)
        }
    }

    // ================================================================
    // StatusCode on HttpParseException
    // ================================================================

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun methodLimitThrows400(multi: Boolean) {
        val limits = defaults.copy(maxMethodLength = 3)
        val raw = "POST / HTTP/1.1\r\nHost: x\r\n\r\n"

        val ex = assertFailsWith<HttpParseException> { parse(raw, multi, limits) }
        assertEquals(400, ex.statusCode)
        assertFalse(ex.isLimitViolation)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun headerNameLimitThrows431(multi: Boolean) {
        val limits = defaults.copy(maxHeaderNameLength = 4)
        val raw = "GET / HTTP/1.1\r\nHost: x\r\nLongHeaderName: val\r\n\r\n"

        val ex = assertFailsWith<HttpParseException> { parse(raw, multi, limits) }
        assertEquals(431, ex.statusCode)
        assertTrue(ex.isLimitViolation)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun urlLimitThrows431(multi: Boolean) {
        val limits = defaults.copy(maxUrlLength = 5)
        val raw = "GET /toolong HTTP/1.1\r\nHost: x\r\n\r\n"

        val ex = assertFailsWith<HttpParseException> { parse(raw, multi, limits) }
        assertEquals(431, ex.statusCode)
        assertTrue(ex.isLimitViolation)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun totalHeaderLimitThrows431(multi: Boolean) {
        val limits = defaults.copy(maxTotalHeaderBytes = 20)
        val raw = "GET / HTTP/1.1\r\nHost: localhost\r\n\r\n"

        val ex = assertFailsWith<HttpParseException> { parse(raw, multi, limits) }
        assertEquals(431, ex.statusCode)
        assertTrue(ex.isLimitViolation)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun headerValueLimitThrows431(multi: Boolean) {
        val limits = defaults.copy(maxHeaderValueLength = 3)
        val raw = "GET / HTTP/1.1\r\nHost: x\r\nKey: longvalue\r\n\r\n"

        val ex = assertFailsWith<HttpParseException> { parse(raw, multi, limits) }
        assertEquals(431, ex.statusCode)
        assertTrue(ex.isLimitViolation)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun headerCountLimitThrows431(multi: Boolean) {
        val limits = defaults.copy(maxHeaderCount = 1)
        val raw = "GET / HTTP/1.1\r\nHost: x\r\nH2: v2\r\n\r\n"

        val ex = assertFailsWith<HttpParseException> { parse(raw, multi, limits) }
        assertEquals(431, ex.statusCode)
        assertTrue(ex.isLimitViolation)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun structuralErrorThrows400(multi: Boolean) {
        val raw = "GET / HTTP/1.1\r\nHost: x\r\nBadHeader\r\n\r\n"

        val ex = assertFailsWith<HttpParseException> { parse(raw, multi) }
        assertEquals(400, ex.statusCode)
        assertFalse(ex.isLimitViolation)
    }

    // ---- Semantic violations also throw 400 ----

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun semanticViolationThrows400_MissingHost(multi: Boolean) {
        val ex = assertFailsWith<HttpParseException> {
            parse("GET / HTTP/1.1\r\n\r\n", multi)
        }
        assertEquals(400, ex.statusCode)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun semanticViolationThrows400_ConnectMethod(multi: Boolean) {
        val ex = assertFailsWith<HttpParseException> {
            parse("CONNECT host:443 HTTP/1.1\r\nHost: host:443\r\n\r\n", multi)
        }
        assertEquals(400, ex.statusCode)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun semanticViolationThrows400_ConflictingCL(multi: Boolean) {
        val raw =
            "GET / HTTP/1.1\r\n" +
            "Host: x\r\n" +
            "Content-Length: 10\r\n" +
            "Content-Length: 20\r\n" +
            "\r\n"

        val ex = assertFailsWith<HttpParseException> { parse(raw, multi) }
        assertEquals(400, ex.statusCode)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun semanticViolationThrows400_TEplusCL(multi: Boolean) {
        val raw =
            "GET / HTTP/1.1\r\n" +
            "Host: x\r\n" +
            "Transfer-Encoding: chunked\r\n" +
            "Content-Length: 10\r\n" +
            "\r\n"

        val ex = assertFailsWith<HttpParseException> { parse(raw, multi) }
        assertEquals(400, ex.statusCode)
    }

    companion object {

        @JvmStatic
        fun parsesHttpMethodData(): Stream<Arguments> = Stream.of(
            Arguments.of("GET", false), Arguments.of("GET", true),
            Arguments.of("POST", false), Arguments.of("POST", true),
            Arguments.of("PUT", false), Arguments.of("PUT", true),
            Arguments.of("DELETE", false), Arguments.of("DELETE", true),
            Arguments.of("HEAD", false), Arguments.of("HEAD", true),
            Arguments.of("OPTIONS", false), Arguments.of("OPTIONS", true),
            Arguments.of("PATCH", false), Arguments.of("PATCH", true),
            Arguments.of("TRACE", false), Arguments.of("TRACE", true),
        )

        @JvmStatic
        fun accepts_ValidContentLengthData(): Stream<Arguments> = Stream.of(
            Arguments.of("42", false), Arguments.of("42", true),
            Arguments.of("0", false), Arguments.of("0", true),
            Arguments.of("123456789", false), Arguments.of("123456789", true),
        )

        @JvmStatic
        fun throws_ContentLengthWithLeadingZerosData(): Stream<Arguments> = Stream.of(
            Arguments.of("007", false), Arguments.of("007", true),
            Arguments.of("00", false), Arguments.of("00", true),
            Arguments.of("01", false), Arguments.of("01", true),
            Arguments.of("0200", false), Arguments.of("0200", true),
        )

        @JvmStatic
        fun throws_InvalidHttpVersionData(): Stream<Arguments> = Stream.of(
            Arguments.of("HTTP/2.0", false), Arguments.of("HTTP/2.0", true),
            Arguments.of("http/1.1", false), Arguments.of("http/1.1", true),
        )

    }

}
