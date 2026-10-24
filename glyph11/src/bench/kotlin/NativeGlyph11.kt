package ktron.glyph11.bench

import ktron.glyph11.ByteBuilder
import ktron.glyph11.HttpParseException
import ktron.glyph11.RequestHead
import ktron.glyph11.parser.ParserLimits
import java.io.File
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.lang.invoke.MethodHandle
import java.nio.file.Path
import java.util.Objects

/**
 * The Glyph11 C core (`glyph11_parse_request`, core/include/glyph11.h) over FFM, filling a [RequestHead] as
 * [ktron.glyph11.Glyph11.parse] does. The downcall is critical and takes the input as a heap segment of the caller's
 * array, so the bytes are neither copied nor pinned beyond the call; the request struct and the arrays the core writes
 * spans into are off-heap, allocated once per parser.
 *
 * The library is `-Dglyph11.lib=<path to libglyph11.so>` (`:glyph11:nativeCore` builds it into build/native).
 */
internal object NativeGlyph11 {
    private val libPath: String? = System.getProperty("glyph11.lib")

    val available: Boolean = libPath != null && File(libPath).isFile

    private val lookup: SymbolLookup? =
        if (available) SymbolLookup.libraryLookup(Path.of(libPath!!), Arena.global()) else null

    private fun downcall(name: String, descriptor: FunctionDescriptor): MethodHandle? =
        lookup?.let { Linker.nativeLinker().downcallHandle(it.find(name).orElseThrow(), descriptor, Linker.Option.critical(true)) }

    // static final, so C2 sees constant handles and inlines the downcalls into their callers

    /** glyph11_status (const uint8_t* buf, size_t len, const glyph11_limits*, glyph11_request*, size_t* consumed) */
    @JvmField
    val PARSE: MethodHandle? = downcall(
        "glyph11_parse_request", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS, ADDRESS, ADDRESS),
    )

    /**
     * glyph11_chunk_result (glyph11_chunk_decoder*, const uint8_t* in, size_t in_len, uint8_t* out, size_t out_cap,
     * size_t* in_consumed, size_t* out_written)
     */
    @JvmField
    val CHUNK_DECODE: MethodHandle? = downcall(
        "glyph11_chunk_decode",
        FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG, ADDRESS, JAVA_LONG, ADDRESS, ADDRESS),
    )

    const val CHUNK_DONE = 1
    const val CHUNK_ERROR = 2
    const val DECODER_SIZE = 32L

    // glyph11_request (LP64): four spans {u32 off, u32 len}, then headers*, header_cap, header_count, query*,
    // query_cap, query_count
    const val REQ_METHOD = 0L
    const val REQ_TARGET = 8L
    const val REQ_PATH = 16L
    const val REQ_VERSION = 24L
    const val REQ_HEADERS = 32L
    const val REQ_HEADER_CAP = 40L
    const val REQ_HEADER_COUNT = 44L
    const val REQ_QUERY = 48L
    const val REQ_QUERY_CAP = 56L
    const val REQ_QUERY_COUNT = 60L
    const val REQ_SIZE = 64L
    const val FIELD_SIZE = 16L
    const val LIMITS_SIZE = 32L

    const val OK = 0
    const val INCOMPLETE = 1

    /** glyph11_status_http_code: the limit breaches (300 and up) are 431, everything else 400. */
    fun httpCode(status: Int): Int = if (status >= 300) 431 else 400

    /** glyph11_status_message, as constants, so a refusal reads no native string. */
    fun message(status: Int): String = when (status) {
        100 -> "invalid request line"
        101 -> "method contains invalid token characters"
        102 -> "method length exceeds limit"
        103 -> "invalid HTTP version"
        104 -> "request-target contains invalid characters"
        105 -> "bare LF; only CRLF line endings are allowed"
        106 -> "obsolete line folding (obs-fold) is not allowed"
        107 -> "whitespace between header name and colon"
        108 -> "multiple spaces in request line"
        109 -> "header name contains invalid token characters"
        110 -> "header value contains invalid characters"
        111 -> "header name is empty"
        112 -> "malformed header line: missing colon"
        200 -> "both Transfer-Encoding and Content-Length present"
        201 -> "conflicting Content-Length values"
        202 -> "invalid Content-Length format"
        203 -> "invalid Transfer-Encoding value; only 'chunked' is accepted"
        204 -> "request must have exactly one Host header"
        205 -> "invalid Host header: contains '@' or '/'"
        206 -> "dot segment in request path"
        207 -> "backslash in request path"
        208 -> "double-encoded percent (%25) in path"
        209 -> "encoded null byte (%00) in path"
        210 -> "overlong UTF-8 sequence in path"
        211 -> "fragment indicator (#) in request-target"
        212 -> "CONNECT method is not allowed on origin servers"
        213 -> "asterisk-form request-target is only valid for OPTIONS"
        300 -> "total header size exceeds limit"
        301 -> "URL length exceeds limit"
        302 -> "header name length exceeds limit"
        303 -> "header value length exceeds limit"
        304 -> "header count exceeds limit"
        305 -> "query parameter count exceeds limit"
        else -> "unknown"
    }
}

/**
 * One parser's off-heap state: the limits, the request struct and the span arrays it points at. Not thread-safe:
 * one per reactor (or connection), like the [RequestHead] it fills.
 */
internal class NativeParser(private val limits: ParserLimits = ParserLimits.Default) : Parser() {
    private val arena = Arena.ofAuto()
    private val limitsSegment = arena.allocate(NativeGlyph11.LIMITS_SIZE, 8)
    private val request = arena.allocate(NativeGlyph11.REQ_SIZE, 8)
    private val headers = arena.allocate(NativeGlyph11.FIELD_SIZE * maxOf(1, limits.maxHeaderCount), 8)
    private val query = arena.allocate(NativeGlyph11.FIELD_SIZE * maxOf(1, limits.maxQueryParameterCount), 8)
    private val consumed = arena.allocate(8, 8)

    // the heap segment of the last array parsed, a connection parses from the same array again and again
    private var lastInput: ByteArray? = null
    private var lastSegment: MemorySegment = MemorySegment.NULL

    init {
        limitsSegment.set(JAVA_INT, 0, NativeGlyph11.LIMITS_SIZE.toInt())
        limitsSegment.set(JAVA_INT, 4, limits.maxHeaderCount)
        limitsSegment.set(JAVA_INT, 8, limits.maxHeaderNameLength)
        limitsSegment.set(JAVA_INT, 12, limits.maxHeaderValueLength)
        limitsSegment.set(JAVA_INT, 16, limits.maxUrlLength)
        limitsSegment.set(JAVA_INT, 20, limits.maxQueryParameterCount)
        limitsSegment.set(JAVA_INT, 24, limits.maxMethodLength)
        limitsSegment.set(JAVA_INT, 28, limits.maxTotalHeaderBytes)
        request.set(ADDRESS, NativeGlyph11.REQ_HEADERS, headers)
        request.set(JAVA_INT, NativeGlyph11.REQ_HEADER_CAP, limits.maxHeaderCount)
        request.set(ADDRESS, NativeGlyph11.REQ_QUERY, query)
        request.set(JAVA_INT, NativeGlyph11.REQ_QUERY_CAP, limits.maxQueryParameterCount)
    }

    /** The core's raw status for the head at `[offset, offset + length)`, its spans left in the request struct. */
    fun status(input: ByteArray, offset: Int, length: Int): Int {
        Objects.checkFromIndexSize(offset, length, input.size)
        var segment = lastSegment
        if (input !== lastInput) {
            segment = MemorySegment.ofArray(input)
            lastSegment = segment
            lastInput = input
        }
        return NativeGlyph11.PARSE!!.invokeExact(
            segment.asSlice(offset.toLong(), length.toLong()), length.toLong(), limitsSegment, request, consumed,
        ) as Int
    }

    override fun parse(input: ByteArray, offset: Int, length: Int, head: RequestHead): Int {
        head.reset()
        val status = status(input, offset, length)
        if (status != NativeGlyph11.OK) {
            if (status == NativeGlyph11.INCOMPLETE) {
                // the core waits for a CRLFCRLF however long the head grows; the Kotlin parser refuses one that
                // cannot end within the limit, and so does this binding
                if (length >= limits.maxTotalHeaderBytes) throw HttpParseException("Total header size exceeds limit.", 431)
                return 0
            }
            throw HttpParseException(NativeGlyph11.message(status), NativeGlyph11.httpCode(status))
        }
        val r = request
        head.headLength = consumed.get(JAVA_LONG, 0).toInt()
        head.methodStart = offset + r.get(JAVA_INT, NativeGlyph11.REQ_METHOD)
        head.methodLength = r.get(JAVA_INT, NativeGlyph11.REQ_METHOD + 4)
        head.pathStart = offset + r.get(JAVA_INT, NativeGlyph11.REQ_PATH)
        head.pathLength = r.get(JAVA_INT, NativeGlyph11.REQ_PATH + 4)
        head.versionStart = offset + r.get(JAVA_INT, NativeGlyph11.REQ_VERSION)
        head.versionLength = r.get(JAVA_INT, NativeGlyph11.REQ_VERSION + 4)
        // the query is what the target holds past the path and its '?'
        val targetLength = r.get(JAVA_INT, NativeGlyph11.REQ_TARGET + 4)
        val afterPath = head.pathStart + head.pathLength
        if (targetLength > head.pathLength) {
            head.queryStart = afterPath + 1
            head.queryLength = targetLength - head.pathLength - 1
        } else {
            head.queryStart = afterPath
            head.queryLength = 0
        }
        val headerCount = r.get(JAVA_INT, NativeGlyph11.REQ_HEADER_COUNT)
        val h = headers
        for (i in 0 until headerCount) {
            val at = i * NativeGlyph11.FIELD_SIZE
            head.addHeader(
                offset + h.get(JAVA_INT, at), h.get(JAVA_INT, at + 4),
                offset + h.get(JAVA_INT, at + 8), h.get(JAVA_INT, at + 12),
            )
        }
        val queryCount = r.get(JAVA_INT, NativeGlyph11.REQ_QUERY_COUNT)
        val q = query
        for (i in 0 until queryCount) {
            val at = i * NativeGlyph11.FIELD_SIZE
            val keyStart = offset + q.get(JAVA_INT, at)
            val keyLength = q.get(JAVA_INT, at + 4)
            val rawValueStart = q.get(JAVA_INT, at + 8)
            val valueLength = q.get(JAVA_INT, at + 12)
            // a key without '=' has the span {0, 0} for its value: placed at the key's end, as Glyph11.parse does
            val valueStart = if (valueLength == 0 && rawValueStart == 0) keyStart + keyLength else offset + rawValueStart
            head.addQuery(keyStart, keyLength, valueStart, valueLength)
        }
        HeadFraming.apply(input, head)
        return head.headLength
    }

    private val decoder = arena.allocate(NativeGlyph11.DECODER_SIZE, 8)
    private val inConsumed = arena.allocate(8, 8)
    private val outWritten = arena.allocate(8, 8)
    private var out = ByteArray(256)
    private var outSegment: MemorySegment = MemorySegment.ofArray(out)

    /**
     * The C core's streaming chunked decoder, from a fresh state, over `[offset, offset + length)`: the decoded body
     * goes to a scratch array and then [into]. A body is complete when the core says DONE; OK (input exhausted) is
     * "not all arrived" for this one-shot use.
     */
    override fun decodeChunked(input: ByteArray, offset: Int, length: Int, into: ByteBuilder): Int {
        Objects.checkFromIndexSize(offset, length, input.size)
        into.reset()
        if (out.size < length) {
            out = ByteArray(maxOf(length, out.size * 2))
            outSegment = MemorySegment.ofArray(out)
        }
        var segment = lastSegment
        if (input !== lastInput) {
            segment = MemorySegment.ofArray(input)
            lastSegment = segment
            lastInput = input
        }
        decoder.fill(0)
        val result = NativeGlyph11.CHUNK_DECODE!!.invokeExact(
            decoder, segment.asSlice(offset.toLong(), length.toLong()), length.toLong(),
            outSegment, out.size.toLong(), inConsumed, outWritten,
        ) as Int
        return when (result) {
            NativeGlyph11.CHUNK_DONE -> {
                into.append(out, 0, outWritten.get(JAVA_LONG, 0).toInt())
                inConsumed.get(JAVA_LONG, 0).toInt()
            }
            NativeGlyph11.CHUNK_ERROR -> throw HttpParseException("malformed chunked encoding")
            else -> 0
        }
    }
}

/**
 * BodyFramingDetector.detectBodyFraming over a [RequestHead]: chunked if a Transfer-Encoding is "chunked", else the
 * last Content-Length's leading digits when they are positive, else no body. The C core leaves framing to its
 * caller.
 */
internal object HeadFraming {
    private val TE = "transfer-encoding".toByteArray(Charsets.US_ASCII)
    private val CL = "content-length".toByteArray(Charsets.US_ASCII)
    private val CHUNKED = "chunked".toByteArray(Charsets.US_ASCII)

    private fun foldEquals(input: ByteArray, at: Int, length: Int, lower: ByteArray): Boolean {
        if (length != lower.size) return false
        for (i in 0 until length) if ((input[at + i].toInt() or 0x20) != (lower[i].toInt() or 0x20)) return false
        return true
    }

    private fun ows(b: Byte): Boolean = b == ' '.code.toByte() || b == '\t'.code.toByte()

    fun apply(input: ByteArray, head: RequestHead) {
        var chunked = false
        var clStart = -1
        var clEnd = -1
        for (i in 0 until head.headerCount) {
            val nameLength = head.headerNameLength(i)
            if (nameLength != 17 && nameLength != 14) continue
            var s = head.headerValueStart(i)
            var e = s + head.headerValueLength(i)
            while (s < e && ows(input[s])) s++
            while (e > s && ows(input[e - 1])) e--
            if (nameLength == 17 && foldEquals(input, head.headerNameStart(i), 17, TE)) {
                if (foldEquals(input, s, e - s, CHUNKED)) chunked = true
            } else if (nameLength == 14 && foldEquals(input, head.headerNameStart(i), 14, CL)) {
                clStart = s
                clEnd = e
            }
        }
        if (chunked) {
            head.bodyFraming = RequestHead.BODY_CHUNKED
            return
        }
        if (clStart >= 0 && clEnd > clStart) {
            var end = clEnd
            for (j in clStart until clEnd) if (input[j] == ','.code.toByte()) { end = j; break }
            var value = -1L
            if (end > clStart) {
                value = 0L
                for (j in clStart until end) {
                    val d = input[j] - '0'.code.toByte()
                    if (d < 0 || d > 9) { value = -1L; break }
                    value = value * 10 + d
                    if (value < 0) { value = -1L; break }
                }
            }
            if (value > 0) {
                head.bodyFraming = RequestHead.BODY_LENGTH
                head.contentLength = value
                return
            }
        }
        head.bodyFraming = RequestHead.BODY_NONE
    }
}
