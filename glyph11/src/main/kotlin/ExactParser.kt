package ktron.glyph11

import ktron.glyph11.Ascii.FIELD
import ktron.glyph11.Ascii.LF
import ktron.glyph11.Ascii.SP
import ktron.glyph11.Ascii.TARGET
import ktron.glyph11.Ascii.TOKEN
import ktron.glyph11.Ascii.isOws
import ktron.glyph11.parser.ParserLimits

/**
 * UltraHardenedParser.tryExtractFullHeader step by step, over an array: every check in its order, with its message
 * and status, so that a refused request is refused for the same reason. [Glyph11.parse] hands it what its fast path
 * does not accept; it is not tuned.
 */
internal object ExactParser {
    private val CONNECT = "connect".toByteArray(Charsets.US_ASCII)
    private val OPTIONS = "options".toByteArray(Charsets.US_ASCII)
    private val CONTENT_LENGTH = "content-length".toByteArray(Charsets.US_ASCII)
    private val TRANSFER_ENCODING = "transfer-encoding".toByteArray(Charsets.US_ASCII)
    private val HOST = "host".toByteArray(Charsets.US_ASCII)
    private val CHUNKED = "chunked".toByteArray(Charsets.US_ASCII)
    private val ULONG_MAX = "18446744073709551615".toByteArray(Charsets.US_ASCII)

    fun parse(a: ByteArray, offset: Int, length: Int, head: RequestHead, limits: ParserLimits): Int {
        head.reset()
        try {
            return parseInto(a, offset, length, head, limits)
        } catch (e: HttpParseException) {
            head.reset()
            throw e
        }
    }

    private fun parseInto(a: ByteArray, offset: Int, length: Int, head: RequestHead, limits: ParserLimits): Int {
        val end = offset + length
        val maxTotal = limits.maxTotalHeaderBytes

        // A head must end within maxTotal bytes: a CRLFCRLF past them, or none in that many, is 431.
        val headerEnd = Ascii.indexOfCrlfCrlf(a, offset, if (length > maxTotal) offset + maxTotal else end)
        if (headerEnd < 0) {
            if (length >= maxTotal) throw HttpParseException("Total header size exceeds limit.", 431)
            return 0
        }
        val total = headerEnd + 4 - offset

        // ---- Request line: METHOD SP URL SP VERSION CRLF ----
        val requestLineEnd = Ascii.indexOfCrlf(a, offset, end)
        if (requestLineEnd < 0) throw HttpParseException("Invalid HTTP/1.1 request line.")
        if (Ascii.indexOf(a, LF, offset, requestLineEnd) >= 0)
            throw HttpParseException("Bare LF detected; only CRLF line endings are allowed.")

        val firstSpace = Ascii.indexOf(a, SP, offset, requestLineEnd)
        if (firstSpace < 0) throw HttpParseException("Invalid request line: missing method.")
        val secondSpace = Ascii.indexOf(a, SP, firstSpace + 1, requestLineEnd)
        if (secondSpace < 0) throw HttpParseException("Invalid request line: missing version.")
        if (secondSpace > firstSpace + 1 && a[firstSpace + 1].toInt() == SP)
            throw HttpParseException("Multiple spaces in request line.")
        if (secondSpace + 1 < requestLineEnd && a[secondSpace + 1].toInt() == SP)
            throw HttpParseException("Multiple spaces in request line.")

        val methodLength = firstSpace - offset
        if (methodLength == 0 || methodLength > limits.maxMethodLength) throw HttpParseException("Method length exceeds limit.")
        if (!Ascii.allIn(a, offset, firstSpace, TOKEN)) throw HttpParseException("Method contains invalid token characters.")
        if (Ascii.foldEquals(a, offset, methodLength, CONNECT))
            throw HttpParseException("CONNECT method is not allowed on origin servers.")

        val urlStart = firstSpace + 1
        val urlLength = secondSpace - urlStart
        if (urlLength > limits.maxUrlLength) throw HttpParseException("URL length exceeds limit.", 431)
        if (!Ascii.allIn(a, urlStart, secondSpace, TARGET)) throw HttpParseException("Request-target contains invalid characters.")

        val versionStart = secondSpace + 1
        if (!isHttpVersion(a, versionStart, requestLineEnd)) throw HttpParseException("Invalid HTTP version.", 505)

        // ---- Path + query ----
        val question = Ascii.indexOf(a, '?'.code, urlStart, secondSpace)
        val pathLength: Int
        if (question >= 0) {
            pathLength = question - urlStart
            val queryEnd = secondSpace
            var paramCount = 0
            var pair = question + 1
            while (pair < queryEnd) {
                val amp = Ascii.indexOf(a, '&'.code, pair, queryEnd)
                val pairEnd = if (amp < 0) queryEnd else amp
                val eq = Ascii.indexOf(a, '='.code, pair, pairEnd)
                if (eq > pair) {
                    if (++paramCount > limits.maxQueryParameterCount)
                        throw HttpParseException("Query parameter count exceeds limit.", 431)
                    head.addQuery(pair, eq - pair, eq + 1, pairEnd - eq - 1)
                } else if (eq < 0 && pairEnd > pair) {
                    if (++paramCount > limits.maxQueryParameterCount)
                        throw HttpParseException("Query parameter count exceeds limit.", 431)
                    head.addQuery(pair, pairEnd - pair, pairEnd, 0)
                }
                pair = if (amp < 0) pairEnd else amp + 1
            }
            head.queryStart = question + 1
            head.queryLength = secondSpace - question - 1
        } else {
            pathLength = urlLength
            head.queryStart = secondSpace
            head.queryLength = 0
        }

        validatePath(a, urlStart, pathLength)

        if (pathLength == 1 && a[urlStart] == '*'.code.toByte() && !Ascii.foldEquals(a, offset, methodLength, OPTIONS))
            throw HttpParseException("Asterisk-form request-target is only valid for OPTIONS.")

        // ---- Headers ----
        var lineStart = requestLineEnd + 2
        var headerCount = 0
        var hasCL = false
        var hasTE = false
        var hostCount = 0
        var firstCLStart = 0
        var firstCLLength = 0
        while (true) {
            val lineEnd = Ascii.indexOfCrlf(a, lineStart, end)
            if (lineEnd < 0) throw HttpParseException("Invalid headers.")
            if (lineEnd == lineStart) break

            if (Ascii.indexOf(a, LF, lineStart, lineEnd) >= 0)
                throw HttpParseException("Bare LF detected; only CRLF line endings are allowed.")
            if (isOws(a[lineStart].toInt())) throw HttpParseException("Obsolete line folding (obs-fold) is not allowed.")

            val colon = Ascii.indexOf(a, ':'.code, lineStart, lineEnd)
            if (colon < 0) throw HttpParseException("Malformed header line: missing colon.")
            if (colon == lineStart) throw HttpParseException("Header name is empty.")
            if (isOws(a[colon - 1].toInt())) throw HttpParseException("Whitespace between header name and colon is not allowed.")

            val nameLength = colon - lineStart
            if (nameLength > limits.maxHeaderNameLength) throw HttpParseException("Header name length exceeds limit.", 431)
            if (!Ascii.allIn(a, lineStart, colon, TOKEN)) throw HttpParseException("Header name contains invalid token characters.")

            var valueStart = colon + 1
            while (valueStart < lineEnd && isOws(a[valueStart].toInt())) valueStart++
            val valueLength = lineEnd - valueStart
            if (valueLength > limits.maxHeaderValueLength) throw HttpParseException("Header value length exceeds limit.", 431)
            if (!Ascii.allIn(a, valueStart, lineEnd, FIELD)) throw HttpParseException("Header value contains invalid characters.")

            if (++headerCount > limits.maxHeaderCount) throw HttpParseException("Header count exceeds limit.", 431)
            head.addHeader(lineStart, nameLength, valueStart, valueLength)

            if (nameLength == 14 && Ascii.foldEquals(a, lineStart, 14, CONTENT_LENGTH)) {
                if (!isValidContentLength(a, valueStart, valueLength)) throw HttpParseException("Invalid Content-Length format.")
                if (hasConflictingCommaSeparatedCL(a, valueStart, valueLength))
                    throw HttpParseException("Conflicting comma-separated Content-Length values.")
                if (hasCL) {
                    if (!Ascii.rangeEquals(a, valueStart, valueLength, firstCLStart, firstCLLength))
                        throw HttpParseException("Conflicting Content-Length headers.")
                } else {
                    firstCLStart = valueStart
                    firstCLLength = valueLength
                    hasCL = true
                }
            } else if (nameLength == 17 && Ascii.foldEquals(a, lineStart, 17, TRANSFER_ENCODING)) {
                hasTE = true
                var s = valueStart
                var e = lineEnd
                while (s < e && isOws(a[s].toInt())) s++
                while (e > s && isOws(a[e - 1].toInt())) e--
                if (!Ascii.foldEquals(a, s, e - s, CHUNKED))
                    throw HttpParseException("Invalid Transfer-Encoding value; only 'chunked' is accepted.")
            } else if (nameLength == 4 && Ascii.foldEquals(a, lineStart, 4, HOST)) {
                hostCount++
                if (Ascii.indexOf(a, '@'.code, valueStart, lineEnd) >= 0 || Ascii.indexOf(a, '/'.code, valueStart, lineEnd) >= 0)
                    throw HttpParseException("Invalid Host header: contains '@' or '/'.")
            }

            lineStart = lineEnd + 2
        }

        if (hasTE && hasCL) throw HttpParseException("Both Transfer-Encoding and Content-Length are present.")
        if (hostCount != 1) throw HttpParseException("Request must have exactly one Host header.")

        head.headLength = total
        head.methodStart = offset
        head.methodLength = methodLength
        head.pathStart = urlStart
        head.pathLength = pathLength
        head.versionStart = versionStart
        head.versionLength = requestLineEnd - versionStart
        HeadFraming.apply(a, head)
        return total
    }

    private fun isHttpVersion(a: ByteArray, from: Int, to: Int): Boolean =
        to - from == 8 &&
            a[from] == 'H'.code.toByte() && a[from + 1] == 'T'.code.toByte() && a[from + 2] == 'T'.code.toByte() &&
            a[from + 3] == 'P'.code.toByte() && a[from + 4] == '/'.code.toByte() && a[from + 5] == '1'.code.toByte() &&
            a[from + 6] == '.'.code.toByte() && (a[from + 7] == '0'.code.toByte() || a[from + 7] == '1'.code.toByte())

    /** Fragment, backslash, %25, %00 and dot segments in the path (its overlong UTF-8 checks are past the target check). */
    private fun validatePath(a: ByteArray, pathStart: Int, pathLength: Int) {
        for (i in 0 until pathLength) {
            val b = a[pathStart + i].toInt()
            if (b == '#'.code) throw HttpParseException("Fragment indicator (#) in request-target.")
            if (b == '\\'.code) throw HttpParseException("Backslash in request path.")
            if (b == '%'.code && i + 2 < pathLength) {
                val h1 = a[pathStart + i + 1].toInt()
                val h2 = a[pathStart + i + 2].toInt()
                if (h1 == '2'.code && h2 == '5'.code) throw HttpParseException("Double-encoded percent (%25) in path.")
                if (h1 == '0'.code && h2 == '0'.code) throw HttpParseException("Encoded null byte (%00) in path.")
            }
            if (b == '/'.code) {
                val remaining = pathLength - i - 1
                if (remaining >= 1 && a[pathStart + i + 1].toInt() == '.'.code) {
                    if (remaining == 1 || a[pathStart + i + 2].toInt() == '/'.code)
                        throw HttpParseException("Dot segment in request path.")
                    if (a[pathStart + i + 2].toInt() == '.'.code && (remaining == 2 || a[pathStart + i + 3].toInt() == '/'.code))
                        throw HttpParseException("Dot segment in request path.")
                }
            }
        }
    }

    private fun isDigit(b: Int): Boolean = b >= '0'.code && b <= '9'.code

    /** 1*DIGIT, comma-separated with OWS: no leading zeros, nothing above 2^64 - 1, no empty element. */
    private fun isValidContentLength(a: ByteArray, start: Int, length: Int): Boolean {
        if (length == 0) return false
        var pos = 0
        while (pos < length) {
            while (pos < length && isOws(a[start + pos].toInt())) pos++
            if (pos >= length) return false
            if (!isDigit(a[start + pos].toInt())) return false
            val digitStart = pos
            if (a[start + pos].toInt() == '0'.code) {
                pos++
                if (pos < length && isDigit(a[start + pos].toInt())) return false
            } else {
                pos++
                while (pos < length && isDigit(a[start + pos].toInt())) pos++
            }
            val digits = pos - digitStart
            if (digits > 20) return false
            if (digits == 20) {
                for (j in 0 until 20) {
                    val d = a[start + digitStart + j].toInt()
                    val limit = ULONG_MAX[j].toInt()
                    if (d > limit) return false
                    if (d < limit) break
                }
            }
            while (pos < length && isOws(a[start + pos].toInt())) pos++
            if (pos >= length) return true
            if (a[start + pos].toInt() != ','.code) return false
            pos++
        }
        return false
    }

    private fun hasConflictingCommaSeparatedCL(a: ByteArray, start: Int, length: Int): Boolean {
        if (Ascii.indexOf(a, ','.code, start, start + length) < 0) return false
        var firstStart = 0
        var firstLength = 0
        var s = 0
        while (s < length) {
            while (s < length && isOws(a[start + s].toInt())) s++
            var e = s
            while (e < length && a[start + e].toInt() != ','.code) e++
            var trimEnd = e
            while (trimEnd > s && isOws(a[start + trimEnd - 1].toInt())) trimEnd--
            val segmentStart = start + s
            val segmentLength = trimEnd - s
            if (firstLength == 0) {
                firstStart = segmentStart
                firstLength = segmentLength
            } else if (!Ascii.rangeEquals(a, segmentStart, segmentLength, firstStart, firstLength)) {
                return true
            }
            s = e + 1
        }
        return false
    }
}

/**
 * BodyFramingDetector.detectBodyFraming over a parsed [RequestHead]: chunked if a Transfer-Encoding is "chunked",
 * else the last Content-Length's leading digits when they are positive, else no body. It keeps the detector's
 * reading of the value exactly: the digits before the first comma, so `5 ,5` (a space before the comma) is no body.
 */
internal object HeadFraming {
    private val TE = "transfer-encoding".toByteArray(Charsets.US_ASCII)
    private val CL = "content-length".toByteArray(Charsets.US_ASCII)
    private val CHUNKED = "chunked".toByteArray(Charsets.US_ASCII)

    fun apply(a: ByteArray, head: RequestHead) {
        var chunked = false
        var clStart = -1
        var clEnd = -1
        for (i in 0 until head.headerCount) {
            val nameLength = head.headerNameLength(i)
            if (nameLength != 17 && nameLength != 14) continue
            var s = head.headerValueStart(i)
            var e = s + head.headerValueLength(i)
            while (s < e && isOws(a[s].toInt())) s++
            while (e > s && isOws(a[e - 1].toInt())) e--
            if (nameLength == 17 && Ascii.foldEquals(a, head.headerNameStart(i), 17, TE)) {
                if (Ascii.foldEquals(a, s, e - s, CHUNKED)) chunked = true
            } else if (nameLength == 14 && Ascii.foldEquals(a, head.headerNameStart(i), 14, CL)) {
                clStart = s
                clEnd = e
            }
        }
        if (chunked) {
            head.bodyFraming = RequestHead.BODY_CHUNKED
            return
        }
        if (clStart >= 0 && clEnd > clStart) {
            val comma = Ascii.indexOf(a, ','.code, clStart, clEnd)
            val value = leadingDigits(a, clStart, if (comma >= 0) comma else clEnd)
            if (value > 0) {
                head.bodyFraming = RequestHead.BODY_LENGTH
                head.contentLength = value
                return
            }
        }
        head.bodyFraming = RequestHead.BODY_NONE
    }

    /** `[from, to)` as a decimal number, -1 if it is empty, holds a non-digit, or overflows a Long. */
    @JvmStatic
    fun leadingDigits(a: ByteArray, from: Int, to: Int): Long {
        if (to == from) return -1
        var result = 0L
        for (j in from until to) {
            val d = a[j] - '0'.code.toByte()
            if (d < 0 || d > 9) return -1
            result = result * 10 + d
            if (result < 0) return -1
        }
        return result
    }
}
