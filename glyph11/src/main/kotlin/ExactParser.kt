package ktron.glyph11

import ktron.glyph11.Ascii.FIELD
import ktron.glyph11.Ascii.LF
import ktron.glyph11.Ascii.SP
import ktron.glyph11.Ascii.TARGET
import ktron.glyph11.Ascii.TOKEN
import ktron.glyph11.Ascii.isOws
import ktron.glyph11.parser.ParserLimits

/**
 * UltraHardenedParser.tryExtractFullHeader step by step, over an array: every check in its order, and the reason it
 * refuses for, so that a refused request is refused with the same status and message. [Glyph11.parse] hands it what
 * its fast path does not accept; it is not tuned.
 */
internal object ExactParser {
    private val CONNECT = "connect".toByteArray(Charsets.US_ASCII)
    private val OPTIONS = "options".toByteArray(Charsets.US_ASCII)
    private val CONTENT_LENGTH = "content-length".toByteArray(Charsets.US_ASCII)
    private val TRANSFER_ENCODING = "transfer-encoding".toByteArray(Charsets.US_ASCII)
    private val HOST = "host".toByteArray(Charsets.US_ASCII)
    private val CHUNKED = "chunked".toByteArray(Charsets.US_ASCII)
    private val ULONG_MAX = "18446744073709551615".toByteArray(Charsets.US_ASCII)

    /** The head's length, 0 to wait for more, or a [Refusals] code (negative): what it is refused for. */
    fun parse(a: ByteArray, offset: Int, length: Int, head: RequestHead, limits: ParserLimits): Int {
        head.reset()
        val end = offset + length
        val maxTotal = limits.maxTotalHeaderBytes

        // A head must end within maxTotal bytes: a CRLFCRLF past them, or none in that many, is 431.
        val headerEnd = Ascii.indexOfCrlfCrlf(a, offset, if (length > maxTotal) offset + maxTotal else end)
        if (headerEnd < 0) {
            if (length >= maxTotal) return Refusals.TOTAL_TOO_LARGE
            return 0
        }
        val total = headerEnd + 4 - offset

        // ---- Request line: METHOD SP URL SP VERSION CRLF ----
        val requestLineEnd = Ascii.indexOfCrlf(a, offset, end)
        if (requestLineEnd < 0) return Refusals.REQUEST_LINE
        if (Ascii.indexOf(a, LF, offset, requestLineEnd) >= 0)
            return Refusals.BARE_LF

        val firstSpace = Ascii.indexOf(a, SP, offset, requestLineEnd)
        if (firstSpace < 0) return Refusals.MISSING_METHOD
        val secondSpace = Ascii.indexOf(a, SP, firstSpace + 1, requestLineEnd)
        if (secondSpace < 0) return Refusals.MISSING_VERSION
        if (secondSpace > firstSpace + 1 && a[firstSpace + 1].toInt() == SP)
            return Refusals.MULTIPLE_SPACES
        if (secondSpace + 1 < requestLineEnd && a[secondSpace + 1].toInt() == SP)
            return Refusals.MULTIPLE_SPACES

        val methodLength = firstSpace - offset
        if (methodLength == 0 || methodLength > limits.maxMethodLength) return Refusals.METHOD_LENGTH
        if (!Ascii.allIn(a, offset, firstSpace, TOKEN)) return Refusals.METHOD_TOKEN
        if (Ascii.foldEquals(a, offset, methodLength, CONNECT))
            return Refusals.CONNECT

        val urlStart = firstSpace + 1
        val urlLength = secondSpace - urlStart
        if (urlLength > limits.maxUrlLength) return Refusals.URL_TOO_LONG
        if (!Ascii.allIn(a, urlStart, secondSpace, TARGET)) return Refusals.TARGET_CHARACTERS

        val versionStart = secondSpace + 1
        if (!isHttpVersion(a, versionStart, requestLineEnd)) return Refusals.VERSION

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
                        return Refusals.TOO_MANY_PARAMETERS
                    head.addQuery(pair, eq - pair, eq + 1, pairEnd - eq - 1)
                } else if (eq < 0 && pairEnd > pair) {
                    if (++paramCount > limits.maxQueryParameterCount)
                        return Refusals.TOO_MANY_PARAMETERS
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

        val path = validatePath(a, urlStart, pathLength)
        if (path != 0) return path

        if (pathLength == 1 && a[urlStart] == '*'.code.toByte() && !Ascii.foldEquals(a, offset, methodLength, OPTIONS))
            return Refusals.ASTERISK_FORM

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
            if (lineEnd < 0) return Refusals.HEADERS
            if (lineEnd == lineStart) break

            if (Ascii.indexOf(a, LF, lineStart, lineEnd) >= 0)
                return Refusals.BARE_LF
            if (isOws(a[lineStart].toInt())) return Refusals.OBS_FOLD

            val colon = Ascii.indexOf(a, ':'.code, lineStart, lineEnd)
            if (colon < 0) return Refusals.MISSING_COLON
            if (colon == lineStart) return Refusals.EMPTY_NAME
            if (isOws(a[colon - 1].toInt())) return Refusals.SPACE_BEFORE_COLON

            val nameLength = colon - lineStart
            if (nameLength > limits.maxHeaderNameLength) return Refusals.NAME_TOO_LONG
            if (!Ascii.allIn(a, lineStart, colon, TOKEN)) return Refusals.NAME_TOKEN

            var valueStart = colon + 1
            while (valueStart < lineEnd && isOws(a[valueStart].toInt())) valueStart++
            val valueLength = lineEnd - valueStart
            if (valueLength > limits.maxHeaderValueLength) return Refusals.VALUE_TOO_LONG
            if (!Ascii.allIn(a, valueStart, lineEnd, FIELD)) return Refusals.VALUE_CHARACTERS

            if (++headerCount > limits.maxHeaderCount) return Refusals.TOO_MANY_HEADERS
            head.addHeader(lineStart, nameLength, valueStart, valueLength)

            if (nameLength == 14 && Ascii.foldEquals(a, lineStart, 14, CONTENT_LENGTH)) {
                if (!isValidContentLength(a, valueStart, valueLength)) return Refusals.CONTENT_LENGTH_FORMAT
                if (hasConflictingCommaSeparatedCL(a, valueStart, valueLength))
                    return Refusals.CONTENT_LENGTH_COMMAS
                if (hasCL) {
                    if (!Ascii.rangeEquals(a, valueStart, valueLength, firstCLStart, firstCLLength))
                        return Refusals.CONTENT_LENGTHS
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
                    return Refusals.TRANSFER_ENCODING
            } else if (nameLength == 4 && Ascii.foldEquals(a, lineStart, 4, HOST)) {
                hostCount++
                if (Ascii.indexOf(a, '@'.code, valueStart, lineEnd) >= 0 || Ascii.indexOf(a, '/'.code, valueStart, lineEnd) >= 0)
                    return Refusals.HOST_FORMAT
            }

            lineStart = lineEnd + 2
        }

        if (hasTE && hasCL) return Refusals.TE_AND_CL
        if (hostCount != 1) return Refusals.HOST_COUNT

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

    /**
     * Fragment, backslash, %25, %00 and dot segments in the path (its overlong UTF-8 checks are past the target check):
     * 0, or the refusal.
     */
    private fun validatePath(a: ByteArray, pathStart: Int, pathLength: Int): Int {
        for (i in 0 until pathLength) {
            val b = a[pathStart + i].toInt()
            if (b == '#'.code) return Refusals.FRAGMENT
            if (b == '\\'.code) return Refusals.BACKSLASH
            if (b == '%'.code && i + 2 < pathLength) {
                val h1 = a[pathStart + i + 1].toInt()
                val h2 = a[pathStart + i + 2].toInt()
                if (h1 == '2'.code && h2 == '5'.code) return Refusals.DOUBLE_ENCODING
                if (h1 == '0'.code && h2 == '0'.code) return Refusals.ENCODED_NULL
            }
            if (b == '/'.code) {
                val remaining = pathLength - i - 1
                if (remaining >= 1 && a[pathStart + i + 1].toInt() == '.'.code) {
                    if (remaining == 1 || a[pathStart + i + 2].toInt() == '/'.code)
                        return Refusals.DOT_SEGMENT
                    if (a[pathStart + i + 2].toInt() == '.'.code && (remaining == 2 || a[pathStart + i + 3].toInt() == '/'.code))
                        return Refusals.DOT_SEGMENT
                }
            }
        }
        return 0
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
 * else the last Content-Length's leading digits when they are positive, else no body: the digits before the first
 * comma and the OWS before it, so `5 ,5` is 5.
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
            var to = if (comma >= 0) comma else clEnd
            while (to > clStart && isOws(a[to - 1].toInt())) to--
            val value = leadingDigits(a, clStart, to)
            if (value > 0) {
                head.bodyFraming = RequestHead.BODY_LENGTH
                head.contentLength = value
                return
            }
        }
        head.bodyFraming = RequestHead.BODY_NONE
    }

    /**
     * `[from, to)` as a decimal number, -1 if it is empty or holds a non-digit, Long.MAX_VALUE from 2^63 up: longer
     * than any body may be, so refused rather than read as no body.
     */
    @JvmStatic
    fun leadingDigits(a: ByteArray, from: Int, to: Int): Long {
        if (to == from) return -1
        var result = 0L
        for (j in from until to) {
            val d = a[j] - '0'.code.toByte()
            if (d < 0 || d > 9) return -1
            result = result * 10 + d
            if (result < 0) {
                for (k in j + 1 until to) if (a[k] < '0'.code.toByte() || a[k] > '9'.code.toByte()) return -1
                return Long.MAX_VALUE
            }
        }
        return result
    }
}
