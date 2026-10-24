package ktron.glyph11

import ktron.glyph11.Ascii.CR
import ktron.glyph11.Ascii.HTAB
import ktron.glyph11.Ascii.LF
import ktron.glyph11.Ascii.PATH
import ktron.glyph11.Ascii.QUERY
import ktron.glyph11.Ascii.SP
import ktron.glyph11.Ascii.TOKEN
import ktron.glyph11.parser.ChunkResult
import ktron.glyph11.parser.ChunkedBodyStream
import ktron.glyph11.parser.ParserLimits
import java.nio.ByteBuffer
import java.util.Objects

/**
 * The array-and-offsets entry points Ktron parses with: the hardened parser's full validation, its results as
 * offsets into the input instead of buffer views.
 */
public object Glyph11 {
    /**
     * Parses the request head at `[offset, offset + length)` of [input] into [head], body framing included, and
     * returns the head's length with its CRLFCRLF; 0 when the head has not all arrived. Throws
     * [HttpParseException] for a request to refuse, with the status to refuse it with: the checks, their order and
     * their statuses are UltraHardenedParser.tryExtractFullHeader's, and the framing BodyFramingDetector's. A head
     * that has not ended within [ParserLimits.maxTotalHeaderBytes] is refused with 431.
     */
    public fun parse(input: ByteArray, offset: Int, length: Int, head: RequestHead, limits: ParserLimits = ParserLimits.Default): Int {
        Objects.checkFromIndexSize(offset, length, input.size)
        val n = FastParser.parse(input, offset, length, head, limits)
        return if (n != FastParser.BAIL) n else ExactParser.parse(input, offset, length, head, limits)
    }

    /**
     * Decodes the chunked body at `[offset, offset + length)` of [input], appending its payload to [into], and returns
     * the bytes its framing occupied; 0 when it has not all arrived. Throws [HttpParseException] for malformed framing.
     */
    public fun decodeChunked(input: ByteArray, offset: Int, length: Int, into: ByteBuilder): Int {
        into.reset()
        val stream = ChunkedBodyStream()
        var at = offset
        val end = offset + length
        while (true) {
            val view = ByteBuffer.wrap(input, at, end - at)
            when (stream.tryReadChunk(view)) {
                ChunkResult.NeedMoreData -> return 0
                ChunkResult.Chunk -> {
                    into.append(input, at + stream.dataOffset, stream.dataLength)
                    at += stream.bytesConsumed
                }
                ChunkResult.Completed -> return at + stream.bytesConsumed - offset
            }
        }
    }
}

/**
 * One forward pass over a head that is valid: each line is scanned once, with the checks fused into the scans (a
 * class table per byte for the method, the target and the field names, eight bytes at a time for the field values).
 * It accepts exactly the heads UltraHardenedParser accepts and returns 0 exactly when it would wait, but it does not
 * work out why a head is refused: anything it does not accept it leaves to [ExactParser] ([BAIL]).
 */
internal object FastParser {
    const val BAIL: Int = -1

    private val HTTP_11: Long = wordOf("HTTP/1.1")
    private val HTTP_10: Long = wordOf("HTTP/1.0")
    private const val FOLD: Long = 0x2020202020202020L
    private val CONTENT_: Long = wordOf("content-")
    private val T_LENGTH: Long = wordOf("t-length") // content-length's bytes 6 to 13
    private val TRANSFER: Long = wordOf("transfer")
    private val _ENCODIN: Long = wordOf("-encodin")
    private const val CL_INVALID = -2L

    private fun wordOf(s: String): Long {
        var w = 0L
        for (i in 7 downTo 0) w = (w shl 8) or s[i].code.toLong()
        return w
    }

    fun parse(a: ByteArray, offset: Int, length: Int, head: RequestHead, limits: ParserLimits): Int {
        val maxTotal = limits.maxTotalHeaderBytes
        // an acceptable head ends within maxTotal bytes: no scan looks further
        val limit = if (length > maxTotal) offset + maxTotal else offset + length
        val cls = Ascii.CLASS

        // ---- method SP ----
        var i = offset
        while (i < limit && cls[a[i].toInt() and 0xFF].toInt() and TOKEN != 0) i++
        if (i >= limit) return outOfInput(length, maxTotal, head)
        val methodLength = i - offset
        if (a[i].toInt() != SP || methodLength == 0 || methodLength > limits.maxMethodLength) return BAIL
        if (methodLength == 7 && isConnect(a, offset)) return BAIL

        // ---- target: the path, then the query after its first '?' ----
        val targetStart = ++i
        var question = -1
        while (true) {
            if (i >= limit) return outOfInput(length, maxTotal, head)
            val c = a[i].toInt() and 0xFF
            if (cls[c].toInt() and PATH != 0) {
                i++
                continue
            }
            if (c == '/'.code) {
                // a dot segment: "/." or "/.." before the path's end ('?' or SP) or a '/'
                if (i + 1 < limit && a[i + 1].toInt() == '.'.code) {
                    if (i + 2 >= limit) return BAIL
                    val c2 = a[i + 2].toInt()
                    if (c2 == '/'.code || c2 == '?'.code || c2 == SP) return BAIL
                    if (c2 == '.'.code) {
                        if (i + 3 >= limit) return BAIL
                        val c3 = a[i + 3].toInt()
                        if (c3 == '/'.code || c3 == '?'.code || c3 == SP) return BAIL
                    }
                }
                i++
                continue
            }
            if (c == '%'.code) {
                // %25 and %00, when both digits are in the path
                if (i + 2 >= limit) return BAIL
                val h1 = a[i + 1].toInt()
                val h2 = a[i + 2].toInt()
                if (h1 != '?'.code && h1 != SP && h2 != '?'.code && h2 != SP &&
                    ((h1 == '2'.code && h2 == '5'.code) || (h1 == '0'.code && h2 == '0'.code))
                ) return BAIL
                i++
                continue
            }
            if (c == '?'.code) {
                question = i++
                break
            }
            if (c == SP) break
            return BAIL // '#', '\', a control, DEL, non-ASCII
        }
        var paramCount = 0
        if (question >= 0) {
            var qs = head.query
            val maxParams = limits.maxQueryParameterCount
            var pair = i
            var eq = -1
            while (true) {
                if (i >= limit) return outOfInput(length, maxTotal, head)
                val c = a[i].toInt() and 0xFF
                if (cls[c].toInt() and QUERY != 0) {
                    i++
                    continue
                }
                if (c == '='.code) {
                    if (eq < 0) eq = i
                    i++
                    continue
                }
                if (c != '&'.code && c != SP) return BAIL
                // key=value, or a key alone; an empty pair or one that starts with '=' is no parameter
                if (eq > pair || (eq < 0 && i > pair)) {
                    if (++paramCount > maxParams) return BAIL
                    val at = (paramCount - 1) shl 2
                    if (at + 4 > qs.size) {
                        qs = qs.copyOf(maxOf(qs.size * 2, at + 4))
                        head.query = qs
                    }
                    qs[at] = pair
                    if (eq > pair) {
                        qs[at + 1] = eq - pair
                        qs[at + 2] = eq + 1
                        qs[at + 3] = i - eq - 1
                    } else {
                        qs[at + 1] = i - pair
                        qs[at + 2] = i
                        qs[at + 3] = 0
                    }
                }
                if (c == SP) break
                pair = ++i
                eq = -1
            }
        }
        val targetEnd = i
        if (targetEnd - targetStart > limits.maxUrlLength) return BAIL
        val pathLength = (if (question >= 0) question else targetEnd) - targetStart
        if (pathLength == 1 && a[targetStart].toInt() == '*'.code && !(methodLength == 7 && isOptions(a, offset))) return BAIL

        // ---- SP HTTP-version CRLF ----
        if (i + 11 > limit) return BAIL
        val version = Ascii.word(a, i + 1)
        if ((version != HTTP_11 && version != HTTP_10) || a[i + 9].toInt() != CR || a[i + 10].toInt() != LF) return BAIL
        val versionStart = i + 1

        // ---- header lines, up to the empty one ----
        var line = i + 11
        var hs = head.headers
        var headerCount = 0
        var hostCount = 0
        var chunked = false
        var clStart = -1
        var clLength = 0
        var contentLength = 0L
        val maxName = limits.maxHeaderNameLength
        val maxValue = limits.maxHeaderValueLength
        val maxHeaders = limits.maxHeaderCount
        while (true) {
            if (line >= limit) return outOfInput(length, maxTotal, head)
            if (a[line].toInt() == CR) {
                if (line + 1 >= limit) return outOfInput(length, maxTotal, head)
                if (a[line + 1].toInt() != LF) return BAIL
                line += 2
                break
            }

            // name ':'
            var n = line
            while (n < limit && cls[a[n].toInt() and 0xFF].toInt() and TOKEN != 0) n++
            if (n >= limit) return outOfInput(length, maxTotal, head)
            val nameLength = n - line
            if (a[n].toInt() != ':'.code || nameLength == 0 || nameLength > maxName) return BAIL

            // OWS value: field-value bytes up to the line's CR, which must be a CRLF
            var v = n + 1
            while (v < limit && (a[v].toInt() == SP || a[v].toInt() == HTAB)) v++
            var e = Ascii.firstControl(a, v, limit)
            while (true) {
                if (e >= limit) return outOfInput(length, maxTotal, head)
                val ce = a[e].toInt()
                if (ce == CR) break
                if (ce != HTAB) return BAIL
                e = Ascii.firstControl(a, e + 1, limit)
            }
            if (e + 1 >= limit) return outOfInput(length, maxTotal, head)
            if (a[e + 1].toInt() != LF) return BAIL
            val valueLength = e - v
            if (valueLength > maxValue || ++headerCount > maxHeaders) return BAIL

            val at = (headerCount - 1) shl 2
            if (at + 4 > hs.size) {
                hs = hs.copyOf(maxOf(hs.size * 2, at + 4))
                head.headers = hs
            }
            hs[at] = line
            hs[at + 1] = nameLength
            hs[at + 2] = v
            hs[at + 3] = valueLength

            // the names that carry checks, told apart by their length first
            when (nameLength) {
                4 -> if (isHost(a, line)) {
                    hostCount++
                    if (hasAtOrSlash(a, v, e)) return BAIL
                }
                14 -> if (isContentLength(a, line)) {
                    if (clStart < 0) {
                        contentLength = contentLengthOf(a, v, e)
                        if (contentLength == CL_INVALID) return BAIL
                        clStart = v
                        clLength = valueLength
                    } else if (!Ascii.rangeEquals(a, v, valueLength, clStart, clLength)) {
                        return BAIL
                    }
                }
                17 -> if (isTransferEncoding(a, line)) {
                    if (!isChunked(a, v, e)) return BAIL
                    chunked = true
                }
            }
            line = e + 2
        }
        if (hostCount != 1 || (chunked && clStart >= 0)) return BAIL

        val headLength = line - offset
        head.headLength = headLength
        head.methodStart = offset
        head.methodLength = methodLength
        head.pathStart = targetStart
        head.pathLength = pathLength
        if (question >= 0) {
            head.queryStart = question + 1
            head.queryLength = targetEnd - question - 1
        } else {
            head.queryStart = targetEnd
            head.queryLength = 0
        }
        head.versionStart = versionStart
        head.versionLength = 8
        head.headerCount = headerCount
        head.queryCount = paramCount
        if (chunked) {
            head.bodyFraming = RequestHead.BODY_CHUNKED
            head.contentLength = 0
        } else if (contentLength > 0) {
            head.bodyFraming = RequestHead.BODY_LENGTH
            head.contentLength = contentLength
        } else {
            head.bodyFraming = RequestHead.BODY_NONE
            head.contentLength = 0
        }
        return headLength
    }

    /**
     * The input ended inside a head that is valid so far: it holds no CRLFCRLF, and none that ends within the limit.
     * 0 to wait for more, or 431 once the input holds the limit.
     */
    private fun outOfInput(length: Int, maxTotal: Int, head: RequestHead): Int {
        head.reset()
        if (length >= maxTotal) throw HttpParseException("Total header size exceeds limit.", 431)
        return 0
    }

    private fun fold(b: Byte): Int = b.toInt() or 0x20

    private const val ATS: Long = 0x4040404040404040L
    private const val SLASHES: Long = 0x2f2f2f2f2f2f2f2fL

    /** `[v, e)` holds an '@' or a '/': eight bytes at a time, then byte by byte. */
    private fun hasAtOrSlash(a: ByteArray, v: Int, e: Int): Boolean {
        var i = v
        while (i + 8 <= e) {
            val w = Ascii.word(a, i)
            if (Ascii.zeroBytes(w xor ATS) or Ascii.zeroBytes(w xor SLASHES) != 0L) return true
            i += 8
        }
        while (i < e) {
            val b = a[i].toInt()
            if (b == '@'.code || b == '/'.code) return true
            i++
        }
        return false
    }

    /** The 20 digits at [v] stand for more than 2^64 - 1 (out of line: contentLengthOf stays small enough to inline). */
    private fun aboveUlongMax(a: ByteArray, v: Int): Boolean {
        for (j in 0 until 20) {
            val d = a[v + j]
            if (d > ULONG_MAX[j]) return true
            if (d < ULONG_MAX[j]) return false
        }
        return false
    }

    private fun isConnect(a: ByteArray, at: Int): Boolean =
        fold(a[at]) == 'c'.code && fold(a[at + 1]) == 'o'.code && fold(a[at + 2]) == 'n'.code && fold(a[at + 3]) == 'n'.code &&
            fold(a[at + 4]) == 'e'.code && fold(a[at + 5]) == 'c'.code && fold(a[at + 6]) == 't'.code

    private fun isOptions(a: ByteArray, at: Int): Boolean =
        fold(a[at]) == 'o'.code && fold(a[at + 1]) == 'p'.code && fold(a[at + 2]) == 't'.code && fold(a[at + 3]) == 'i'.code &&
            fold(a[at + 4]) == 'o'.code && fold(a[at + 5]) == 'n'.code && fold(a[at + 6]) == 's'.code

    private fun isHost(a: ByteArray, at: Int): Boolean =
        fold(a[at]) == 'h'.code && fold(a[at + 1]) == 'o'.code && fold(a[at + 2]) == 's'.code && fold(a[at + 3]) == 't'.code

    // a 14-byte name followed by ':' and its line: both words are inside the array
    private fun isContentLength(a: ByteArray, at: Int): Boolean =
        (Ascii.word(a, at) or FOLD) == CONTENT_ && (Ascii.word(a, at + 6) or FOLD) == T_LENGTH

    private fun isTransferEncoding(a: ByteArray, at: Int): Boolean =
        (Ascii.word(a, at) or FOLD) == TRANSFER && (Ascii.word(a, at + 8) or FOLD) == _ENCODIN && fold(a[at + 16]) == 'g'.code

    /** `[v, e)`, its leading OWS skipped already, is "chunked" once its trailing OWS is trimmed. */
    private fun isChunked(a: ByteArray, v: Int, e: Int): Boolean {
        var end = e
        while (end > v && (a[end - 1].toInt() == SP || a[end - 1].toInt() == HTAB)) end--
        return end - v == 7 &&
            fold(a[v]) == 'c'.code && fold(a[v + 1]) == 'h'.code && fold(a[v + 2]) == 'u'.code && fold(a[v + 3]) == 'n'.code &&
            fold(a[v + 4]) == 'k'.code && fold(a[v + 5]) == 'e'.code && fold(a[v + 6]) == 'd'.code
    }

    private fun isDigit(b: Byte): Boolean = b >= '0'.code.toByte() && b <= '9'.code.toByte()

    private fun isOws(b: Byte): Boolean = b == SP.toByte() || b == HTAB.toByte()

    private val ULONG_MAX = "18446744073709551615".toByteArray(Charsets.US_ASCII)

    /**
     * A Content-Length value `[v, e)`, its leading OWS skipped: [CL_INVALID] unless UltraHardenedParser accepts it,
     * 1*DIGIT without a leading zero and at most 2^64 - 1, its comma-separated copies all the same digits; else what
     * BodyFramingDetector frames it as, those digits as a Long, -1 (no body) when they overflow one or OWS stands
     * between them and the first comma.
     */
    private fun contentLengthOf(a: ByteArray, v: Int, e: Int): Long {
        if (v >= e || !isDigit(a[v])) return CL_INVALID
        var p = v + 1
        if (a[v] == '0'.code.toByte()) {
            if (p < e && isDigit(a[p])) return CL_INVALID
        } else {
            while (p < e && isDigit(a[p])) p++
        }
        val digits = p - v
        if (digits > 20 || (digits == 20 && aboveUlongMax(a, v))) return CL_INVALID
        var framing = HeadFraming.leadingDigits(a, v, p)
        var q = p
        while (q < e && isOws(a[q])) q++
        if (q < e) {
            if (q > p) framing = -1
            while (true) {
                if (a[q] != ','.code.toByte()) return CL_INVALID
                q++
                while (q < e && isOws(a[q])) q++
                if (q >= e || e - q < digits || !Ascii.rangeEquals(a, q, digits, v, digits)) return CL_INVALID
                q += digits
                if (q < e && isDigit(a[q])) return CL_INVALID
                while (q < e && isOws(a[q])) q++
                if (q >= e) break
            }
        }
        return framing
    }
}
