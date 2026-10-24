package ktron.glyph11.parser.ultrahardened

import ktron.glyph11.ByteSpan
import ktron.glyph11.EmptyBuffer
import ktron.glyph11.HttpParseException
import ktron.glyph11.parser.ParserConstants
import ktron.glyph11.parser.ParserLimits
import ktron.glyph11.protocol.BinaryRequest
import ktron.glyph11.protocol.BinaryResponse
import ktron.glyph11.protocol.KeyValueList
import java.nio.ByteBuffer

/**
 * The hardened HTTP/1.1 parser: combined structural parse and semantic validation with full
 * security checks (request smuggling, path traversal, Host rules, resource limits, ...).
 *
 * The input is read from its position until its limit; its position and limit are not modified.
 * All offsets are relative to the input's position and all parsed fields are read-only [ByteBuffer]
 * slices of the input.
 */
public object UltraHardenedParser {

    // ---- Semantic validation constants (TransferEncodingName, ChunkedValue
    //       and AsciiEqualsIgnoreCase live in ParserConstants) ----
    private val ContentLengthName: ByteArray = "content-length".toByteArray(Charsets.US_ASCII)
    private val HostHeaderName: ByteArray = "host".toByteArray(Charsets.US_ASCII)
    private val OptionsMethodName: ByteArray = "options".toByteArray(Charsets.US_ASCII)
    private val ConnectMethodName: ByteArray = "connect".toByteArray(Charsets.US_ASCII)

    private val UlongMax: ByteArray = "18446744073709551615".toByteArray(Charsets.US_ASCII)

    private const val Space: Int = 0x20
    private const val Question: Int = 0x3F
    private const val QuerySeparator: Int = 0x26
    private const val Equal: Int = 0x3D
    private const val Colon: Int = 0x3A

    /**
     * Combined parse + semantic validation with full security checks.
     *
     * Performs a full structural parse plus every semantic check (request smuggling,
     * path traversal, Host rules, ...), fused into one pass over headers and one pass
     * over the path for better cache locality and throughput.
     *
     * @param input Contiguous buffer from the network layer.
     * @param request Target to populate with parsed request data.
     * @param limits Resource limits to enforce during parsing.
     * @return Bytes consumed on success (header + terminators - 1, callers advance by this value + 1),
     * or -1 if the header is incomplete and more data is needed.
     * @throws HttpParseException Thrown on any protocol or semantic violation.
     */
    public fun tryExtractFullHeader(
        input: ByteBuffer,
        request: BinaryRequest,
        limits: ParserLimits = ParserLimits.Default,
    ): Int {
        val span = ByteSpan(input, request.views)

        val headerEnd = span.indexOf(ParserConstants.CrlfCrlf)
        if (headerEnd < 0) return -1

        val totalHeaderBytes = headerEnd + 4
        if (totalHeaderBytes > limits.maxTotalHeaderBytes)
            throw HttpParseException("Total header size exceeds limit.", statusCode = 431)

        // ---- Request line: METHOD SP URL SP VERSION CRLF ----

        val requestLineEnd = span.indexOf(ParserConstants.Crlf)
        if (requestLineEnd < 0)
            throw HttpParseException("Invalid HTTP/1.1 request line.")

        // The request line is [0, requestLineEnd)

        // ---- Reject bare LF in request line - RFC 9112 2.2 ----
        if (span.indexOfByte('\n'.code, 0, requestLineEnd) >= 0)
            throw HttpParseException("Bare LF detected; only CRLF line endings are allowed.")

        val firstSpace = span.indexOfByte(Space, 0, requestLineEnd)
        if (firstSpace < 0)
            throw HttpParseException("Invalid request line: missing method.")

        val secondSpaceAbs = span.indexOfByte(Space, firstSpace + 1, requestLineEnd)
        if (secondSpaceAbs < 0)
            throw HttpParseException("Invalid request line: missing version.")

        val secondSpace = secondSpaceAbs

        // ---- Reject multiple spaces in request line - RFC 9112 3 ----
        if (secondSpace > firstSpace + 1 && span.at(firstSpace + 1) == Space)
            throw HttpParseException("Multiple spaces in request line.")
        if (secondSpace + 1 < requestLineEnd && span.at(secondSpace + 1) == Space)
            throw HttpParseException("Multiple spaces in request line.")

        // --- Method --- [0, firstSpace)
        val methodLength = firstSpace
        if (methodLength == 0 || methodLength > limits.maxMethodLength)
            throw HttpParseException("Method length exceeds limit.")
        if (!ParserConstants.isValidToken(span, 0, firstSpace))
            throw HttpParseException("Method contains invalid token characters.")

        request.method = span.slice(0, firstSpace)

        // ---- Semantic: CONNECT method - RFC 9110 9.3.6 ----
        if (span.equalsIgnoreCase(0, methodLength, ConnectMethodName))
            throw HttpParseException("CONNECT method is not allowed on origin servers.")

        // --- URL ---
        val urlStart = firstSpace + 1
        val urlLen = secondSpace - urlStart
        if (urlLen > limits.maxUrlLength)
            throw HttpParseException("URL length exceeds limit.", statusCode = 431)

        // The URL is [urlStart, secondSpace)

        // --- Validate request-target for control characters (NUL, etc.) ---
        if (!ParserConstants.isValidRequestTarget(span, urlStart, secondSpace))
            throw HttpParseException("Request-target contains invalid characters.")

        // --- Version --- [secondSpace + 1, requestLineEnd)
        val versionStart = secondSpace + 1
        val versionLength = requestLineEnd - versionStart
        if (!ParserConstants.isValidHttpVersion(span, versionStart, requestLineEnd))
            throw HttpParseException("Invalid HTTP version.", 505)

        request.version = span.slice(secondSpace + 1, versionLength)

        // --- Path + Query ---
        val queryStartAbs = span.indexOfByte(Question, urlStart, secondSpace)
        val queryStartIndex = if (queryStartAbs < 0) -1 else queryStartAbs - urlStart

        val pathStart = urlStart
        val pathLength: Int

        if (queryStartIndex >= 0) {
            request.path = span.slice(urlStart, queryStartIndex)
            pathLength = queryStartIndex

            val queryAbsStart = urlStart + queryStartIndex + 1
            val queryLen = urlLen - (queryStartIndex + 1)
            val queryEnd = queryAbsStart + queryLen

            var paramCount = 0
            var cur = 0
            while (cur < queryLen) {
                val pairAbsStart = queryAbsStart + cur

                val ampAbs = span.indexOfByte(QuerySeparator, queryAbsStart + cur, queryEnd)
                val amp = if (ampAbs < 0) -1 else ampAbs - (queryAbsStart + cur)
                val pairLen = if (amp < 0) queryLen - cur else amp

                val eqAbs = span.indexOfByte(Equal, pairAbsStart, pairAbsStart + pairLen)
                val eq = if (eqAbs < 0) -1 else eqAbs - pairAbsStart

                if (eq > 0) {
                    if (++paramCount > limits.maxQueryParameterCount)
                        throw HttpParseException("Query parameter count exceeds limit.", statusCode = 431)

                    request.queryParameters.add(
                        span.slice(pairAbsStart, eq),
                        span.slice(pairAbsStart + eq + 1, pairLen - (eq + 1))
                    )
                } else if (eq < 0 && pairLen > 0) {
                    if (++paramCount > limits.maxQueryParameterCount)
                        throw HttpParseException("Query parameter count exceeds limit.", statusCode = 431)

                    request.queryParameters.add(
                        span.slice(pairAbsStart, pairLen),
                        EmptyBuffer
                    )
                }

                cur += pairLen + (if (amp < 0) 0 else 1)
            }
        } else {
            request.path = span.slice(urlStart, urlLen)
            pathLength = urlLen
        }

        // ---- Semantic: single-pass path validation ----
        validatePathSemantics(span, pathStart, pathLength)

        // ---- Semantic: asterisk-form - RFC 9112 3.2.4 ----
        if (pathLength == 1 && span.at(pathStart) == '*'.code) {
            if (!span.equalsIgnoreCase(0, methodLength, OptionsMethodName))
                throw HttpParseException("Asterisk-form request-target is only valid for OPTIONS.")
        }

        // ---- Headers (structural parse + inline semantic checks) ----

        val hostCount = parseHeaders(span, requestLineEnd + 2, limits, request.headers, true)

        // ---- Post-loop cross-header semantic checks ----

        // RFC 9112 3.2 - HTTP/1.1 requires exactly one Host header
        if (hostCount != 1)
            throw HttpParseException("Request must have exactly one Host header.")

        return totalHeaderBytes - 1
    }

    /**
     * Combined parse + semantic validation of a RESPONSE header with full security checks.
     *
     * The header block is parsed by the same rules as a request's - field-lines are identical in
     * both directions, so obs-fold, bare LF, whitespace before the colon and the
     * Transfer-Encoding/Content-Length smuggling checks all carry over unchanged.
     *
     * What does not carry over: there is no Host rule (that is a request requirement), no
     * request-target, and the first line is `HTTP-version SP status-code SP [reason-phrase]`
     * rather than `METHOD SP target SP HTTP-version`.
     *
     * Whether a body follows at all is not decidable from the response alone - HEAD and 304 are
     * framed by the request that produced them. See
     * [ktron.glyph11.validation.BodyFramingDetector.detectResponseBodyFraming].
     *
     * @param input Contiguous buffer containing the response header.
     * @param response Target to populate with parsed response data.
     * @param limits Resource limits to enforce during parsing.
     * @return Bytes consumed on success (header + terminators - 1, callers advance by this value + 1),
     * or -1 if the header is incomplete and more data is needed.
     * @throws HttpParseException Thrown on any protocol or semantic violation.
     */
    public fun tryExtractFullResponseHeader(
        input: ByteBuffer,
        response: BinaryResponse,
        limits: ParserLimits = ParserLimits.Default,
    ): Int {
        val span = ByteSpan(input)

        val headerEnd = span.indexOf(ParserConstants.CrlfCrlf)
        if (headerEnd < 0) return -1

        val totalHeaderBytes = headerEnd + 4
        if (totalHeaderBytes > limits.maxTotalHeaderBytes)
            throw HttpParseException("Total header size exceeds limit.", statusCode = 431)

        // ---- Status line: HTTP-version SP status-code SP [ reason-phrase ] CRLF - RFC 9112 4 ----

        val statusLineEnd = span.indexOf(ParserConstants.Crlf)
        if (statusLineEnd < 0)
            throw HttpParseException("Invalid HTTP/1.1 status line.")

        // The status line is [0, statusLineEnd)
        val statusLineLength = statusLineEnd

        // ---- Reject bare LF in status line - RFC 9112 2.2 ----
        if (span.indexOfByte('\n'.code, 0, statusLineEnd) >= 0)
            throw HttpParseException("Bare LF detected; only CRLF line endings are allowed.")

        val firstSpace = span.indexOfByte(Space, 0, statusLineEnd)
        if (firstSpace < 0)
            throw HttpParseException("Invalid status line: missing status code.")

        // --- Version --- [0, firstSpace)
        if (!ParserConstants.isValidHttpVersion(span, 0, firstSpace))
            throw HttpParseException("Invalid HTTP version.", 505)

        response.version = span.slice(0, firstSpace)

        // --- Status code: exactly three digits - RFC 9112 4 ---
        val codeStart = firstSpace + 1
        if (codeStart + 3 > statusLineLength)
            throw HttpParseException("Invalid status line: truncated status code.")

        if (!ParserConstants.isDigit(span.at(codeStart)) ||
            !ParserConstants.isDigit(span.at(codeStart + 1)) ||
            !ParserConstants.isDigit(span.at(codeStart + 2))
        )
            throw HttpParseException("Status code must be exactly three digits.")

        val status = ((span.at(codeStart) - '0'.code) * 100) +
            ((span.at(codeStart + 1) - '0'.code) * 10) +
            (span.at(codeStart + 2) - '0'.code)

        // A three-digit code below 100 is well-formed but not a status - 0xx has no meaning and
        // treating it as one lets a garbage first line pass as a response.
        if (status < 100)
            throw HttpParseException("Status code must be in the range 100-599.")

        response.statusCode = span.slice(codeStart, 3)
        response.status = status

        // --- Reason phrase (optional) ---
        val afterCode = codeStart + 3
        if (afterCode == statusLineLength) {
            // "HTTP/1.1 200" with no trailing space. The grammar asks for the SP even when the
            // phrase is empty, but origins do send this and it is not a parsing ambiguity, so it
            // is accepted rather than turned into an interop failure.
            response.reasonPhrase = EmptyBuffer
        } else {
            if (span.at(afterCode) != Space)
                throw HttpParseException("Invalid status line: status code must be three digits.")

            val reasonStart = afterCode + 1
            val reasonLen = statusLineLength - reasonStart

            if (reasonLen > limits.maxReasonPhraseLength)
                throw HttpParseException("Reason phrase length exceeds limit.", statusCode = 431)

            // reason-phrase = 1*( HTAB / SP / VCHAR / obs-text ) - the same character set a
            // field-value admits, which is what makes this check reusable. It is a charset check
            // and nothing more: the phrase is free text and carries no meaning to act on.
            if (!ParserConstants.isValidFieldValue(span, reasonStart, reasonStart + reasonLen))
                throw HttpParseException("Reason phrase contains invalid characters.")

            response.reasonPhrase = span.slice(reasonStart, reasonLen)
        }

        // ---- Headers (structural parse + inline semantic checks) ----

        parseHeaders(span, statusLineEnd + 2, limits, response.headers, false)

        return totalHeaderBytes - 1
    }

    // =====================================================================
    //  Header block (identical for requests and responses)
    // =====================================================================

    /**
     * Parses the header lines starting at [firstLineStart] up to the empty line, adding them to
     * [target], including all inline and cross-header semantic checks (the Host rules are applied
     * to requests only). Returns the number of Host headers seen.
     */
    private fun parseHeaders(
        span: ByteSpan,
        firstLineStart: Int,
        limits: ParserLimits,
        target: KeyValueList,
        isRequest: Boolean,
    ): Int {
        var lineStart = firstLineStart
        var headerCount = 0

        // Semantic state tracked across headers
        var hasCL = false
        var hasTE = false
        var hostCount = 0
        var firstCLStart = 0
        var firstCLLength = 0

        while (true) {
            val lineEndAbs = span.indexOf(ParserConstants.Crlf, lineStart, span.length)
            if (lineEndAbs < 0)
                throw HttpParseException("Invalid headers.")

            val lineLen = lineEndAbs - lineStart

            if (lineLen == 0)
                break

            // The line is [lineStart, lineStart + lineLen)
            val lineEnd = lineStart + lineLen

            // ---- Reject bare LF in header line - RFC 9112 2.2 ----
            if (span.indexOfByte('\n'.code, lineStart, lineEnd) >= 0)
                throw HttpParseException("Bare LF detected; only CRLF line endings are allowed.")

            // ---- Reject obs-fold (line starting with SP/HTAB) - RFC 9112 5.2 ----
            if (span.at(lineStart) == ' '.code || span.at(lineStart) == '\t'.code)
                throw HttpParseException("Obsolete line folding (obs-fold) is not allowed.")

            val colonAbs = span.indexOfByte(Colon, lineStart, lineEnd)
            val colon = if (colonAbs < 0) -1 else colonAbs - lineStart

            if (colon <= 0)
                throw HttpParseException(
                    if (colon == 0)
                        "Header name is empty."
                    else
                        "Malformed header line: missing colon."
                )

            // ---- Reject whitespace between field-name and colon - RFC 9112 5.1 ----
            if (span.at(lineStart + colon - 1) == ' '.code || span.at(lineStart + colon - 1) == '\t'.code)
                throw HttpParseException("Whitespace between header name and colon is not allowed.")

            // Validate header name: [lineStart, lineStart + colon)
            val nameLength = colon
            if (nameLength > limits.maxHeaderNameLength)
                throw HttpParseException("Header name length exceeds limit.", statusCode = 431)
            if (!ParserConstants.isValidToken(span, lineStart, lineStart + colon))
                throw HttpParseException("Header name contains invalid token characters.")

            // Trim leading OWS from value
            var valAbsStart = lineStart + colon + 1
            while (valAbsStart < lineStart + lineLen) {
                val b = span.at(valAbsStart)
                if (b != ' '.code && b != '\t'.code) break
                valAbsStart++
            }

            val valLen = (lineStart + lineLen) - valAbsStart

            // Validate header value: [valAbsStart, valAbsStart + valLen)
            if (valLen > limits.maxHeaderValueLength)
                throw HttpParseException("Header value length exceeds limit.", statusCode = 431)
            if (!ParserConstants.isValidFieldValue(span, valAbsStart, valAbsStart + valLen))
                throw HttpParseException("Header value contains invalid characters.")

            if (++headerCount > limits.maxHeaderCount)
                throw HttpParseException("Header count exceeds limit.", statusCode = 431)

            target.add(
                span.slice(lineStart, colon),
                span.slice(valAbsStart, valLen)
            )

            // ---- Inline semantic checks keyed by header name ----
            // Length pre-check avoids the full case-insensitive compare for most headers.

            if (nameLength == 14 && span.equalsIgnoreCase(lineStart, nameLength, ContentLengthName)) {
                // RFC 9110 8.6 - validate format (syntax, leading zeros, overflow)
                if (!semIsValidContentLengthValue(span, valAbsStart, valLen))
                    throw HttpParseException("Invalid Content-Length format.")

                // RFC 9112 6.2 - comma-separated values must all be identical
                if (semHasConflictingCommaSeparatedCL(span, valAbsStart, valLen))
                    throw HttpParseException("Conflicting comma-separated Content-Length values.")

                // RFC 9110 8.6 - multiple CL headers must have identical values
                if (hasCL) {
                    if (!span.rangeEquals(valAbsStart, valLen, firstCLStart, firstCLLength))
                        throw HttpParseException("Conflicting Content-Length headers.")
                } else {
                    firstCLStart = valAbsStart
                    firstCLLength = valLen
                    hasCL = true
                }
            } else if (nameLength == 17 && span.equalsIgnoreCase(lineStart, nameLength, ParserConstants.TransferEncodingName)) {
                hasTE = true

                // RFC 9112 6.1 - only "chunked" is accepted
                var trimStart = valAbsStart
                var trimEnd = valAbsStart + valLen
                while (trimStart < trimEnd && (span.at(trimStart) == ' '.code || span.at(trimStart) == '\t'.code))
                    trimStart++
                while (trimEnd > trimStart && (span.at(trimEnd - 1) == ' '.code || span.at(trimEnd - 1) == '\t'.code))
                    trimEnd--

                if (!span.equalsIgnoreCase(trimStart, trimEnd - trimStart, ParserConstants.ChunkedValue))
                    throw HttpParseException("Invalid Transfer-Encoding value; only 'chunked' is accepted.")
            } else if (isRequest && nameLength == 4 && span.equalsIgnoreCase(lineStart, nameLength, HostHeaderName)) {
                hostCount++

                // RFC 9110 7.2 - Host must be host:port only
                if (span.indexOfByte('@'.code, valAbsStart, valAbsStart + valLen) >= 0 ||
                    span.indexOfByte('/'.code, valAbsStart, valAbsStart + valLen) >= 0
                )
                    throw HttpParseException("Invalid Host header: contains '@' or '/'.")
            }

            lineStart += lineLen + 2
        }

        // ---- Post-loop cross-header semantic checks ----

        // RFC 9112 6.1 - TE + CL together is a smuggling vector (and a desync vector in the
        // response direction too: a proxy that frames one way and a client that frames the other
        // disagree on where the response ends, and the remainder becomes the head of the next one).
        if (hasTE && hasCL)
            throw HttpParseException("Both Transfer-Encoding and Content-Length are present.")

        return hostCount
    }

    // =====================================================================
    //  Semantic validation helpers
    //  Prefixed with "sem" to mirror the upstream naming.
    // =====================================================================

    /**
     * Single-pass path validation covering: fragment (#), backslash, double encoding (%25),
     * encoded null byte (%00), dot segments, and overlong UTF-8 sequences.
     */
    private fun validatePathSemantics(span: ByteSpan, pathStart: Int, pathLength: Int) {
        for (i in 0 until pathLength) {
            val b = span.at(pathStart + i)

            if (b == '#'.code)
                throw HttpParseException("Fragment indicator (#) in request-target.")

            if (b == '\\'.code)
                throw HttpParseException("Backslash in request path.")

            if (b == '%'.code && i + 2 < pathLength) {
                val h1 = span.at(pathStart + i + 1)
                val h2 = span.at(pathStart + i + 2)
                if (h1 == '2'.code && h2 == '5'.code)
                    throw HttpParseException("Double-encoded percent (%25) in path.")
                if (h1 == '0'.code && h2 == '0'.code)
                    throw HttpParseException("Encoded null byte (%00) in path.")
            }

            if (b == '/'.code) {
                val remaining = pathLength - i - 1
                if (remaining >= 1 && span.at(pathStart + i + 1) == '.'.code) {
                    // "/." at end or "/./"
                    if (remaining == 1 || span.at(pathStart + i + 2) == '/'.code)
                        throw HttpParseException("Dot segment in request path.")
                    // "/.." at end or "/../"
                    if (span.at(pathStart + i + 2) == '.'.code && (remaining == 2 || span.at(pathStart + i + 3) == '/'.code))
                        throw HttpParseException("Dot segment in request path.")
                }
            }

            // Overlong UTF-8 (RFC 3629 3).
            // Note: in the hardened parser these bytes (>0x7E) are already rejected by
            // IsValidRequestTarget, but included for semantic completeness.
            if (b == 0xC0 || b == 0xC1)
                throw HttpParseException("Overlong UTF-8 sequence in path.")
            if (b == 0xE0 && i + 1 < pathLength && span.at(pathStart + i + 1) < 0xA0)
                throw HttpParseException("Overlong UTF-8 sequence in path.")
            if (b == 0xF0 && i + 1 < pathLength && span.at(pathStart + i + 1) < 0x90)
                throw HttpParseException("Overlong UTF-8 sequence in path.")
        }
    }

    /**
     * Validates Content-Length value syntax: 1*DIGIT with optional comma-separated duplicates.
     * Rejects empty, non-digit, leading zeros, overflow, and trailing commas.
     */
    private fun semIsValidContentLengthValue(span: ByteSpan, valueStart: Int, valueLength: Int): Boolean {
        if (valueLength == 0) return false

        val maxDigits = 20 // ulong.MaxValue = 18446744073709551615

        var pos = 0
        while (pos < valueLength) {
            // Skip OWS before each element
            while (pos < valueLength && (span.at(valueStart + pos) == ' '.code || span.at(valueStart + pos) == '\t'.code))
                pos++

            if (pos >= valueLength) return false

            val b = span.at(valueStart + pos)
            if (b < '0'.code || b > '9'.code) return false

            // Reject leading zeros: "0" is ok, "00" or "007" is not
            val digitStart = pos
            if (span.at(valueStart + pos) == '0'.code) {
                pos++
                if (pos < valueLength && span.at(valueStart + pos) >= '0'.code && span.at(valueStart + pos) <= '9'.code)
                    return false
            } else {
                pos++
                while (pos < valueLength && span.at(valueStart + pos) >= '0'.code && span.at(valueStart + pos) <= '9'.code)
                    pos++
            }

            // Reject values that overflow ulong
            val digitCount = pos - digitStart
            if (digitCount > maxDigits)
                return false
            if (digitCount == maxDigits) {
                for (j in 0 until maxDigits) {
                    val digit = span.at(valueStart + digitStart + j)
                    val limit = UlongMax[j].toInt() and 0xFF
                    if (digit > limit) return false
                    if (digit < limit) break
                }
            }

            // Skip OWS after the number
            while (pos < valueLength && (span.at(valueStart + pos) == ' '.code || span.at(valueStart + pos) == '\t'.code))
                pos++

            if (pos >= valueLength) return true
            if (span.at(valueStart + pos) != ','.code) return false
            pos++
        }

        return false // trailing comma with nothing after
    }

    /**
     * Returns true if a single Content-Length value contains comma-separated segments
     * that are not all identical (smuggling vector - RFC 9112 6.2).
     */
    private fun semHasConflictingCommaSeparatedCL(span: ByteSpan, valueStart: Int, valueLength: Int): Boolean {
        if (span.indexOfByte(','.code, valueStart, valueStart + valueLength) < 0)
            return false

        var firstSegmentStart = 0
        var firstSegmentLength = 0
        var start = 0
        while (start < valueLength) {
            // Skip OWS
            while (start < valueLength && (span.at(valueStart + start) == ' '.code || span.at(valueStart + start) == '\t'.code))
                start++

            var end = start
            while (end < valueLength && span.at(valueStart + end) != ','.code)
                end++

            // Trim trailing OWS
            var trimEnd = end
            while (trimEnd > start && (span.at(valueStart + trimEnd - 1) == ' '.code || span.at(valueStart + trimEnd - 1) == '\t'.code))
                trimEnd--

            val segmentStart = valueStart + start
            val segmentLength = trimEnd - start

            if (firstSegmentLength == 0) {
                firstSegmentStart = segmentStart
                firstSegmentLength = segmentLength
            } else if (!span.rangeEquals(segmentStart, segmentLength, firstSegmentStart, firstSegmentLength)) {
                return true
            }

            start = end + 1
        }

        return false
    }

}
