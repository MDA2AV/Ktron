package ktron.glyph11.bench

import ktron.glyph11.ByteBuilder
import ktron.glyph11.RequestHead
import ktron.glyph11.parser.ChunkResult
import ktron.glyph11.parser.ChunkedBodyStream
import ktron.glyph11.parser.ParserLimits
import ktron.glyph11.parser.ultrahardened.UltraHardenedParser
import ktron.glyph11.protocol.BinaryRequest
import ktron.glyph11.validation.BodyFraming
import ktron.glyph11.validation.BodyFramingDetector
import java.nio.ByteBuffer

/**
 * The offset API as commit 8ce2192 implemented it, an adapter over the port's ByteBuffer parser: the baseline the
 * benchmarks compare against. Copied unchanged but for its name.
 */
internal object LegacyGlyph11 {
    fun parse(input: ByteArray, offset: Int, length: Int, head: RequestHead, limits: ParserLimits = ParserLimits.Default): Int {
        head.reset()
        val request = scratch.get()
        request.clear()
        val consumed = UltraHardenedParser.tryExtractFullHeader(ByteBuffer.wrap(input, offset, length), request, limits)
        if (consumed < 0) return 0

        head.headLength = consumed + 1
        request.method.let { head.methodStart = start(it); head.methodLength = it.remaining() }
        request.path.let { head.pathStart = start(it); head.pathLength = it.remaining() }
        request.version.let { head.versionStart = start(it); head.versionLength = it.remaining() }
        locateQuery(input, head)

        val headers = request.headers
        for (i in 0 until headers.count) {
            val (name, value) = headers[i]
            head.addHeader(start(name), name.remaining(), start(value), value.remaining())
        }
        val query = request.queryParameters
        for (i in 0 until query.count) {
            val (key, value) = query[i]
            head.addQuery(start(key), key.remaining(), start(value), value.remaining())
        }

        val framing = BodyFramingDetector.detectBodyFraming(request)
        when (framing.framing) {
            BodyFraming.ContentLength -> {
                head.bodyFraming = RequestHead.BODY_LENGTH
                head.contentLength = framing.contentLength
            }
            BodyFraming.Chunked -> head.bodyFraming = RequestHead.BODY_CHUNKED
            else -> head.bodyFraming = RequestHead.BODY_NONE
        }
        return head.headLength
    }

    fun decodeChunked(input: ByteArray, offset: Int, length: Int, into: ByteBuilder): Int {
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

    private val scratch = ThreadLocal.withInitial { BinaryRequest() }

    // A field's first byte as an index into the input: the parser's pooled views are positioned at it.
    private fun start(view: ByteBuffer): Int = view.position()

    // The query is what the target holds after the path's '?', up to the space before the version.
    private fun locateQuery(input: ByteArray, head: RequestHead) {
        val afterPath = head.pathStart + head.pathLength
        if (afterPath < input.size && input[afterPath] == '?'.code.toByte()) {
            var end = afterPath + 1
            while (end < input.size && input[end] != ' '.code.toByte()) end++
            head.queryStart = afterPath + 1
            head.queryLength = end - afterPath - 1
        } else {
            head.queryStart = afterPath
            head.queryLength = 0
        }
    }
}
