package ktron.glyph11.tests

import ktron.glyph11.HttpParseException
import ktron.glyph11.parser.flexibleparser.FlexibleParser
import ktron.glyph11.protocol.BinaryRequest
import ktron.glyph11.protocol.KeyValueList
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import java.util.stream.Stream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * HTTP/1.1 header parsing compliance tests (RFC 9112).
 * Each test runs against the plain heap buffer and against the same bytes placed in the middle
 * of a larger direct buffer (the port has no multi-segment input).
 */
class FlexibleParserHeaderCompliance {

    private val request = BinaryRequest()

    @AfterEach
    fun dispose() {
        request.close()
    }

    // region Helpers

    private fun parse(raw: String, multiSegment: Boolean): Pair<Boolean, Int> {
        val bytes = TestBuffers.b(raw)

        val result = FlexibleParser.tryExtractFullHeader(TestBuffers.input(bytes, multiSegment), request)

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

    // endregion

    // ----------------------------------------------------------------
    // Incomplete requests - parser returns false
    // ----------------------------------------------------------------

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
        // HTTP/1.1 requires CRLF; bare LF must not be accepted as a terminator
        val (ok, _) = parse("GET / HTTP/1.1\nHost: x\n\n", multi)
        assertFalse(ok)
    }

    // ----------------------------------------------------------------
    // Invalid request lines - parser throws
    // ----------------------------------------------------------------

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
        // Missing HTTP-version component
        assertFailsWith<HttpParseException> {
            parse("GET /path\r\n\r\n", multi)
        }
    }

    // ----------------------------------------------------------------
    // Method parsing
    // ----------------------------------------------------------------

    @ParameterizedTest
    @MethodSource("parsesHttpMethodData")
    fun parsesHttpMethod(method: String, multi: Boolean) {
        val (ok, _) = parse("$method / HTTP/1.1\r\n\r\n", multi)
        assertTrue(ok)
        AssertAscii.equal(method, request.method)
    }

    // ----------------------------------------------------------------
    // Path parsing
    // ----------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun parsesRootPath(multi: Boolean) {
        val (ok, _) = parse("GET / HTTP/1.1\r\n\r\n", multi)
        assertTrue(ok)
        AssertAscii.equal("/", request.path)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun parsesNestedPath(multi: Boolean) {
        val (ok, _) = parse("GET /api/v1/users HTTP/1.1\r\n\r\n", multi)
        assertTrue(ok)
        AssertAscii.equal("/api/v1/users", request.path)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun separatesPathFromQueryString(multi: Boolean) {
        val (ok, _) = parse("GET /search?q=test HTTP/1.1\r\n\r\n", multi)
        assertTrue(ok)
        AssertAscii.equal("/search", request.path)
    }

    // ----------------------------------------------------------------
    // Query string parsing
    // ----------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun parsesSingleQueryParameter(multi: Boolean) {
        val (ok, _) = parse("GET /p?key=val HTTP/1.1\r\n\r\n", multi)
        assertTrue(ok)
        assertEquals(1, request.queryParameters.count)
        assertQueryParam(request.queryParameters, 0, "key", "val")
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun parsesMultipleQueryParameters(multi: Boolean) {
        val (ok, _) = parse("GET /p?a=1&b=2&c=3 HTTP/1.1\r\n\r\n", multi)
        assertTrue(ok)
        assertEquals(3, request.queryParameters.count)
        assertQueryParam(request.queryParameters, 0, "a", "1")
        assertQueryParam(request.queryParameters, 1, "b", "2")
        assertQueryParam(request.queryParameters, 2, "c", "3")
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun parsesQueryParameterWithEmptyValue(multi: Boolean) {
        val (ok, _) = parse("GET /p?key= HTTP/1.1\r\n\r\n", multi)
        assertTrue(ok)
        assertEquals(1, request.queryParameters.count)
        assertQueryParam(request.queryParameters, 0, "key", "")
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun noQueryParams_TrailingQuestionMarkOnly(multi: Boolean) {
        val (ok, _) = parse("GET /p? HTTP/1.1\r\n\r\n", multi)
        assertTrue(ok)
        AssertAscii.equal("/p", request.path)
        assertEquals(0, request.queryParameters.count)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun parsesQueryParamWithoutValue(multi: Boolean) {
        val (ok, _) = parse("GET /p?ok=1&flag&also=2 HTTP/1.1\r\n\r\n", multi)
        assertTrue(ok)
        assertEquals(3, request.queryParameters.count)
        assertQueryParam(request.queryParameters, 0, "ok", "1")
        assertQueryParam(request.queryParameters, 1, "flag", "")
        assertQueryParam(request.queryParameters, 2, "also", "2")
    }

    // ----------------------------------------------------------------
    // Header field parsing
    // ----------------------------------------------------------------

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
        val (ok, _) = parse("GET / HTTP/1.1\r\nKey:   value\r\n\r\n", multi)
        assertTrue(ok)
        assertHeader(request.headers, 0, "Key", "value")
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun trimsLeadingTabFromHeaderValue(multi: Boolean) {
        val (ok, _) = parse("GET / HTTP/1.1\r\nKey:\tvalue\r\n\r\n", multi)
        assertTrue(ok)
        assertHeader(request.headers, 0, "Key", "value")
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun trimsLeadingMixedWhitespaceFromHeaderValue(multi: Boolean) {
        val (ok, _) = parse("GET / HTTP/1.1\r\nKey: \t \tvalue\r\n\r\n", multi)
        assertTrue(ok)
        assertHeader(request.headers, 0, "Key", "value")
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun preservesInternalWhitespaceInHeaderValue(multi: Boolean) {
        val (ok, _) = parse("GET / HTTP/1.1\r\nX-Data: hello world\r\n\r\n", multi)
        assertTrue(ok)
        assertHeader(request.headers, 0, "X-Data", "hello world")
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun parsesHeaderValueContainingColon(multi: Boolean) {
        val (ok, _) = parse("GET / HTTP/1.1\r\nHost: localhost:8080\r\n\r\n", multi)
        assertTrue(ok)
        assertHeader(request.headers, 0, "Host", "localhost:8080")
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun parsesEmptyHeaderValue(multi: Boolean) {
        val (ok, _) = parse("GET / HTTP/1.1\r\nX-Empty:\r\n\r\n", multi)
        assertTrue(ok)
        assertEquals(1, request.headers.count)
        assertHeader(request.headers, 0, "X-Empty", "")
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun parsesRequestWithNoHeaders(multi: Boolean) {
        val (ok, _) = parse("GET / HTTP/1.1\r\n\r\n", multi)
        assertTrue(ok)
        assertEquals(0, request.headers.count)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun ignoresHeaderLineWithoutColon(multi: Boolean) {
        val raw =
            "GET / HTTP/1.1\r\n" +
            "Valid: value\r\n" +
            "nocolonhere\r\n" +
            "Also: present\r\n" +
            "\r\n"

        val (ok, _) = parse(raw, multi)
        assertTrue(ok)
        assertEquals(2, request.headers.count)
        assertHeader(request.headers, 0, "Valid", "value")
        assertHeader(request.headers, 1, "Also", "present")
    }

    // ----------------------------------------------------------------
    // Bytes consumed
    // ----------------------------------------------------------------

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
        val header = "POST / HTTP/1.1\r\nHost: localhost\r\n\r\n"
        val raw = header + "BodyContent"
        val (ok, bytesRead) = parse(raw, multi)
        assertTrue(ok)
        assertEquals(header.length - 1, bytesRead)
    }

    // ----------------------------------------------------------------
    // Full realistic request
    // ----------------------------------------------------------------

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

    }

}
