package ktron.glyph11

import ktron.glyph11.parser.ChunkResult
import ktron.glyph11.parser.ChunkedBodyStream
import ktron.glyph11.parser.ParserLimits
import ktron.glyph11.parser.ultrahardened.UltraHardenedParser
import ktron.glyph11.protocol.BinaryRequest
import ktron.glyph11.validation.BodyFraming
import ktron.glyph11.validation.BodyFramingDetector
import java.nio.ByteBuffer

/*
 * What a parser made of a request, field by field, so that two parsers can be compared: the old one (the port's
 * UltraHardenedParser and BodyFramingDetector, the reference) and the offset API.
 */

/** A request head as one parser saw it: accepted with its fields, incomplete, or refused with a status. */
data class Outcome(
    val kind: String,
    val status: Int = 0,
    val message: String? = null,
    val headLength: Int = 0,
    val method: String = "",
    val path: String = "",
    val query: String = "",
    val version: String = "",
    val headers: List<Pair<String, String>> = emptyList(),
    val params: List<Pair<String, String>> = emptyList(),
    val framing: Int = 0,
    val contentLength: Long = 0,
) {
    override fun toString(): String = when (kind) {
        ACCEPTED -> "accepted($headLength) $method ${Vectors.escape(path)} ?${Vectors.escape(query)} $version " +
            "headers=${headers.map { Vectors.escape(it.first) + ":" + Vectors.escape(it.second) }} " +
            "params=${params.map { Vectors.escape(it.first) + "=" + Vectors.escape(it.second) }} framing=$framing/$contentLength"
        REFUSED -> "refused $status \"$message\""
        else -> kind
    }

    companion object {
        const val ACCEPTED = "accepted"
        const val INCOMPLETE = "incomplete"
        const val REFUSED = "refused"
        val incomplete = Outcome(INCOMPLETE)
        fun refused(status: Int, message: String?) = Outcome(REFUSED, status, message)
        fun crashed(e: Throwable) = Outcome("crashed ${e.javaClass.simpleName}: ${e.message}")
    }
}

/** A chunked body as one decoder saw it. */
data class ChunkOutcome(val kind: String, val wire: Int = 0, val payload: String = "", val status: Int = 0, val message: String? = null) {
    override fun toString(): String = when (kind) {
        Outcome.ACCEPTED -> "decoded($wire) ${Vectors.escape(payload)}"
        Outcome.REFUSED -> "refused $status \"$message\""
        else -> kind
    }
}

object Outcomes {
    private fun latin1(a: ByteArray, start: Int, length: Int) = String(a, start, length, Charsets.ISO_8859_1)

    private fun latin1(b: ByteBuffer): String {
        val chars = CharArray(b.remaining())
        for (i in chars.indices) chars[i] = (b.get(b.position() + i).toInt() and 0xFF).toChar()
        return String(chars)
    }

    private val request = BinaryRequest()

    /** UltraHardenedParser.tryExtractFullHeader, then BodyFramingDetector: the semantics the offset API keeps. */
    fun reference(input: ByteArray, offset: Int, length: Int, limits: ParserLimits): Outcome {
        request.clear()
        val consumed = try {
            UltraHardenedParser.tryExtractFullHeader(ByteBuffer.wrap(input, offset, length), request, limits)
        } catch (e: HttpParseException) {
            return Outcome.refused(e.statusCode, e.message)
        } catch (e: RuntimeException) {
            return Outcome.crashed(e)
        }
        if (consumed < 0) return Outcome.incomplete
        val framing = BodyFramingDetector.detectBodyFraming(request)
        // the raw query: what the target holds after its first '?' (UltraHardenedParser only splits it)
        val firstSpace = (offset until offset + length).first { input[it] == ' '.code.toByte() }
        val secondSpace = (firstSpace + 1 until offset + length).first { input[it] == ' '.code.toByte() }
        val question = (firstSpace + 1 until secondSpace).firstOrNull { input[it] == '?'.code.toByte() }
        return Outcome(
            Outcome.ACCEPTED,
            headLength = consumed + 1,
            method = latin1(request.method),
            path = latin1(request.path),
            query = if (question == null) "" else latin1(input, question + 1, secondSpace - question - 1),
            version = latin1(request.version),
            headers = (0 until request.headers.count).map { latin1(request.headers[it].first) to latin1(request.headers[it].second) },
            params = (0 until request.queryParameters.count).map {
                latin1(request.queryParameters[it].first) to latin1(request.queryParameters[it].second)
            },
            framing = when (framing.framing) {
                BodyFraming.ContentLength -> RequestHead.BODY_LENGTH
                BodyFraming.Chunked -> RequestHead.BODY_CHUNKED
                else -> RequestHead.BODY_NONE
            },
            contentLength = if (framing.framing == BodyFraming.ContentLength) framing.contentLength else 0,
        )
    }

    /** What [head] says after a parse that returned [n]. */
    fun of(input: ByteArray, n: Int, head: RequestHead): Outcome {
        if (n == 0) return Outcome.incomplete
        return Outcome(
            Outcome.ACCEPTED,
            headLength = n,
            method = latin1(input, head.methodStart, head.methodLength),
            path = latin1(input, head.pathStart, head.pathLength),
            query = latin1(input, head.queryStart, head.queryLength),
            version = latin1(input, head.versionStart, head.versionLength),
            headers = (0 until head.headerCount).map {
                latin1(input, head.headerNameStart(it), head.headerNameLength(it)) to
                    latin1(input, head.headerValueStart(it), head.headerValueLength(it))
            },
            params = (0 until head.queryCount).map {
                latin1(input, head.queryKeyStart(it), head.queryKeyLength(it)) to
                    latin1(input, head.queryValueStart(it), head.queryValueLength(it))
            },
            framing = head.bodyFraming,
            contentLength = head.contentLength,
        )
    }

    inline fun outcome(input: ByteArray, head: RequestHead, parse: () -> Int): Outcome =
        try {
            of(input, parse(), head)
        } catch (e: HttpParseException) {
            Outcome.refused(e.statusCode, e.message)
        } catch (e: RuntimeException) {
            Outcome.crashed(e)
        }

    fun glyph11(input: ByteArray, offset: Int, length: Int, limits: ParserLimits, head: RequestHead = RequestHead()): Outcome =
        outcome(input, head) { Glyph11.parse(input, offset, length, head, limits) }

    /** The exact parser alone, as Glyph11.parse uses it for what its fast path leaves: a negative result is a refusal. */
    fun exact(input: ByteArray, offset: Int, length: Int, limits: ParserLimits, head: RequestHead = RequestHead()): Outcome =
        outcome(input, head) {
            val n = ExactParser.parse(input, offset, length, head, limits)
            if (n < 0) throw Refusals.exception(n)
            n
        }

    /** The old decodeChunked: ChunkedBodyStream chunk by chunk, a fresh stream per call. */
    fun referenceChunked(input: ByteArray, offset: Int, length: Int): ChunkOutcome {
        val payload = StringBuilder()
        val stream = ChunkedBodyStream()
        var at = offset
        val end = offset + length
        try {
            while (true) {
                when (stream.tryReadChunk(ByteBuffer.wrap(input, at, end - at))) {
                    ChunkResult.NeedMoreData -> return ChunkOutcome(Outcome.INCOMPLETE)
                    ChunkResult.Chunk -> {
                        payload.append(latin1(input, at + stream.dataOffset, stream.dataLength))
                        at += stream.bytesConsumed
                    }
                    ChunkResult.Completed -> return ChunkOutcome(Outcome.ACCEPTED, at + stream.bytesConsumed - offset, payload.toString())
                }
            }
        } catch (e: HttpParseException) {
            return ChunkOutcome(Outcome.REFUSED, status = e.statusCode, message = e.message)
        } catch (e: RuntimeException) {
            return ChunkOutcome("crashed ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    fun glyph11Chunked(input: ByteArray, offset: Int, length: Int, into: ByteBuilder = ByteBuilder()): ChunkOutcome =
        try {
            val n = Glyph11.decodeChunked(input, offset, length, into)
            if (n == 0) ChunkOutcome(Outcome.INCOMPLETE) else ChunkOutcome(Outcome.ACCEPTED, n, latin1(into.array, 0, into.length))
        } catch (e: HttpParseException) {
            ChunkOutcome(Outcome.REFUSED, status = e.statusCode, message = e.message)
        } catch (e: RuntimeException) {
            ChunkOutcome("crashed ${e.javaClass.simpleName}: ${e.message}")
        }
}

/** The corpus in src/test/resources/vectors, and its escaping. */
object Vectors {
    class Request(val limits: ParserLimits, val bytes: ByteArray, val source: String)

    private fun lines(name: String): List<Pair<Int, String>> =
        Vectors::class.java.getResourceAsStream("/vectors/$name")!!.bufferedReader(Charsets.ISO_8859_1).readLines()
            .mapIndexed { i, l -> (i + 1) to l }
            .filter { (_, l) -> l.isNotEmpty() && !l.startsWith("#") }

    val requests: List<Request> by lazy {
        lines("requests.txt").map { (n, l) ->
            val tab = l.indexOf('\t')
            Request(limits(l.substring(0, tab)), unescape(l.substring(tab + 1)), "requests.txt:$n")
        }
    }

    val chunked: List<ByteArray> by lazy { lines("chunked.txt").map { (_, l) -> unescape(l) } }

    fun limits(spec: String): ParserLimits {
        var l = ParserLimits.Default
        if (spec == "-") return l
        for (part in spec.split(',')) {
            val (name, value) = part.split('=')
            val v = value.toInt()
            l = when (name) {
                "maxHeaderCount" -> l.copy(maxHeaderCount = v)
                "maxHeaderNameLength" -> l.copy(maxHeaderNameLength = v)
                "maxHeaderValueLength" -> l.copy(maxHeaderValueLength = v)
                "maxUrlLength" -> l.copy(maxUrlLength = v)
                "maxQueryParameterCount" -> l.copy(maxQueryParameterCount = v)
                "maxMethodLength" -> l.copy(maxMethodLength = v)
                "maxReasonPhraseLength" -> l.copy(maxReasonPhraseLength = v)
                "maxTotalHeaderBytes" -> l.copy(maxTotalHeaderBytes = v)
                else -> error("unknown limit $name")
            }
        }
        return l
    }

    fun describe(limits: ParserLimits): String {
        val d = ParserLimits.Default
        val parts = mutableListOf<String>()
        if (limits.maxHeaderCount != d.maxHeaderCount) parts += "maxHeaderCount=${limits.maxHeaderCount}"
        if (limits.maxHeaderNameLength != d.maxHeaderNameLength) parts += "maxHeaderNameLength=${limits.maxHeaderNameLength}"
        if (limits.maxHeaderValueLength != d.maxHeaderValueLength) parts += "maxHeaderValueLength=${limits.maxHeaderValueLength}"
        if (limits.maxUrlLength != d.maxUrlLength) parts += "maxUrlLength=${limits.maxUrlLength}"
        if (limits.maxQueryParameterCount != d.maxQueryParameterCount) parts += "maxQueryParameterCount=${limits.maxQueryParameterCount}"
        if (limits.maxMethodLength != d.maxMethodLength) parts += "maxMethodLength=${limits.maxMethodLength}"
        if (limits.maxTotalHeaderBytes != d.maxTotalHeaderBytes) parts += "maxTotalHeaderBytes=${limits.maxTotalHeaderBytes}"
        return if (parts.isEmpty()) "-" else parts.joinToString(",")
    }

    fun unescape(s: String): ByteArray {
        val out = java.io.ByteArrayOutputStream(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '\\') {
                out.write(c.code)
                i++
                continue
            }
            when (s[i + 1]) {
                'r' -> out.write(0x0D)
                'n' -> out.write(0x0A)
                't' -> out.write(0x09)
                '\\' -> out.write(0x5C)
                'x' -> {
                    out.write(s.substring(i + 2, i + 4).toInt(16))
                    i += 2
                }
                else -> error("bad escape in $s")
            }
            i += 2
        }
        return out.toByteArray()
    }

    fun escape(s: String): String = escape(s.toByteArray(Charsets.ISO_8859_1))

    fun escape(b: ByteArray, from: Int = 0, to: Int = b.size): String {
        val sb = StringBuilder()
        for (i in from until to) {
            val x = b[i].toInt() and 0xFF
            when {
                x == 0x5C -> sb.append("\\\\")
                x == 0x0D -> sb.append("\\r")
                x == 0x0A -> sb.append("\\n")
                x == 0x09 -> sb.append("\\t")
                x in 0x20..0x7E -> sb.append(x.toChar())
                else -> sb.append("\\x%02x".format(x))
            }
        }
        return sb.toString()
    }
}
