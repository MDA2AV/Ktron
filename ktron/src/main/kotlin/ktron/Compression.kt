package ktron

import ktron.compression.BrotliEncoder
import ktron.compression.ContentEncoder
import ktron.compression.DeflaterGzipEncoder
import ktron.compression.GzipEncoder
import ktron.glyph11.ByteBuilder

/**
 * Compresses the response bodies a client accepts compressed: gzip on libdeflate, or brotli on libbrotlienc.
 *
 * gzip at level 1 is the default and wins when a client accepts both: on dynamic JSON of a few KB, measured
 * against every gzip, zlib and brotli setting, its speed buys more than any smaller setting's bytes do.
 * Brotli answers clients that accept br and not gzip. A body is compressed when it is at least [minLength] bytes,
 * its Content-Type is one of [types], the response carries no Content-Encoding yet, and compressing made it smaller.
 *
 * @property brotliQuality 0..11, or -1 for no brotli at all.
 * @property vary add `Vary: Accept-Encoding` to compressed responses, for caches between server and client.
 */
public class Compression(
    private val gzipLevel: Int = 1,
    private val brotliQuality: Int = 0,
    private val preferBrotli: Boolean = false,
    private val minLength: Int = 256,
    types: List<String> = DEFAULT_TYPES,
    private val vary: Boolean = false,
) : Middleware {
    private val types: Array<ByteArray> = types.map { it.lowercase().toByteArray() }.toTypedArray()

    // One set per reactor thread: the encoders are not thread-safe, and the output is sent before the thread takes
    // another request.
    private class Encoders(gzipLevel: Int, brotliQuality: Int) {
        val gzip: ContentEncoder? = if (gzipLevel < 0) null else try {
            GzipEncoder(gzipLevel)
        } catch (e: LinkageError) {
            DeflaterGzipEncoder(minOf(gzipLevel, 9))   // no libdeflate on this host
        }
        val brotli: ContentEncoder? = if (brotliQuality < 0) null else try {
            BrotliEncoder(brotliQuality)
        } catch (e: LinkageError) {
            null
        }
        val out = ByteBuilder(0)
    }

    private val encoders = ThreadLocal.withInitial { Encoders(gzipLevel, brotliQuality) }

    override fun after(ctx: Context) {
        val response = ctx.response
        if (response.bodyLength < minLength || !compressible(response.contentType)) return
        if (response.hasHeader(CONTENT_ENCODING)) return

        val request = ctx.request
        val index = request.head.findHeader(request.input, ACCEPT_ENCODING)
        if (index < 0) return
        val input = request.input
        val start = request.head.headerValueStart(index)
        val length = request.head.headerValueLength(index)

        val set = encoders.get()
        val gzipOk = set.gzip != null && AcceptEncoding.accepts(input, start, length, GZIP)
        val brOk = set.brotli != null && AcceptEncoding.accepts(input, start, length, BR)
        val encoder = when {
            brOk && (preferBrotli || !gzipOk) -> set.brotli!!
            gzipOk -> set.gzip!!
            else -> return
        }

        val out = set.out
        out.reset()
        val n = encoder.encode(response.bodyBytes, response.bodyOffset, response.bodyLength, out)
        if (n >= response.bodyLength) return   // it did not help: send it as it was

        response.replaceBody(out.array, 0, n)
        response.headerLine(if (encoder.name == "br") BR_LINE else GZIP_LINE)
        if (vary) response.headerLine(VARY_LINE)
    }

    // The media type, before any parameters, against the list.
    private fun compressible(contentType: ByteArray): Boolean {
        var end = contentType.size
        for (i in contentType.indices) {
            if (contentType[i] == ';'.code.toByte()) {
                end = i
                break
            }
        }
        while (end > 0 && contentType[end - 1] == ' '.code.toByte()) end--
        for (type in types) {
            if (type.size == end && RequestHeadAccess.equalsIgnoreCase(contentType, 0, type)) return true
        }
        return false
    }

    public companion object {
        public val DEFAULT_TYPES: List<String> = listOf(
            "application/json", "text/plain", "text/html", "text/css", "text/javascript", "application/javascript",
            "application/xml", "text/xml", "image/svg+xml",
        )

        private val ACCEPT_ENCODING = "accept-encoding".toByteArray()
        private val CONTENT_ENCODING = "content-encoding".toByteArray()
        private val GZIP = "gzip".toByteArray()
        private val BR = "br".toByteArray()
        private val GZIP_LINE = "Content-Encoding: gzip\r\n".toByteArray()
        private val BR_LINE = "Content-Encoding: br\r\n".toByteArray()
        private val VARY_LINE = "Vary: Accept-Encoding\r\n".toByteArray()
    }
}

/** Accept-Encoding (RFC 9110 12.5.3): codings with optional q-values, `*` for any, q=0 for "not this one". */
internal object AcceptEncoding {
    /** Whether the list at `[start, start + length)` of [input] accepts [coding] (lower case). */
    fun accepts(input: ByteArray, start: Int, length: Int, coding: ByteArray): Boolean {
        var named = -1.0   // q of the coding itself, -1 when not listed
        var star = -1.0    // q of *, -1 when not listed
        var at = start
        val end = start + length
        while (at < end) {
            while (at < end && (input[at] == ' '.code.toByte() || input[at] == '\t'.code.toByte() || input[at] == ','.code.toByte())) at++
            val tokenStart = at
            while (at < end && input[at] != ','.code.toByte() && input[at] != ';'.code.toByte() &&
                input[at] != ' '.code.toByte() && input[at] != '\t'.code.toByte()
            ) {
                at++
            }
            val tokenLength = at - tokenStart
            var q = 1.0
            while (at < end && input[at] != ','.code.toByte()) {
                if (input[at] == ';'.code.toByte()) q = parameterQ(input, at + 1, end, q)
                at++
            }
            if (tokenLength == coding.size && RequestHeadAccess.equalsIgnoreCase(input, tokenStart, coding)) {
                named = q
            } else if (tokenLength == 1 && input[tokenStart] == '*'.code.toByte()) {
                star = q
            }
        }
        return if (named >= 0) named > 0 else star > 0
    }

    // "q=0.5" in the parameter at `at`; anything else leaves q as it was.
    private fun parameterQ(input: ByteArray, from: Int, end: Int, q: Double): Double {
        var at = from
        while (at < end && input[at] == ' '.code.toByte()) at++
        if (at + 1 >= end || (input[at] != 'q'.code.toByte() && input[at] != 'Q'.code.toByte()) || input[at + 1] != '='.code.toByte()) return q
        at += 2
        val valueStart = at
        while (at < end && input[at] != ',' .code.toByte() && input[at] != ';'.code.toByte() && input[at] != ' '.code.toByte()) at++
        return String(input, valueStart, at - valueStart, Charsets.ISO_8859_1).toDoubleOrNull() ?: q
    }
}
