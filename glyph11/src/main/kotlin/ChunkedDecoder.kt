package ktron.glyph11

import ktron.glyph11.Ascii.CR
import ktron.glyph11.Ascii.HTAB
import ktron.glyph11.Ascii.LF
import ktron.glyph11.Ascii.SP
import java.util.Objects

/**
 * Decodes one chunked body as it arrives, each call going on from the last complete chunk the previous call found,
 * where [Glyph11.decodeChunked] decodes the whole body again each time: a body that trickles in over n reads costs it
 * O(n) chunk walks per read, O(n^2) in all. The results are decodeChunked's, call for call.
 *
 * Pass the body received so far from its first byte each time, at whatever offset the bytes now sit: the bytes given
 * before must not have changed, and [into] must be the same builder, untouched between the calls, as it holds the
 * payload decoded so far. [reset] before the next body. One per connection; not thread-safe.
 */
public class ChunkedDecoder {
    // the wire bytes of the complete chunks decoded so far, from the body's first byte
    private var decoded = 0

    /** Starts over, for another body (and empties [into] when given). */
    public fun reset(into: ByteBuilder? = null) {
        decoded = 0
        into?.reset()
    }

    /**
     * As [Glyph11.decodeChunked] on the body at `[offset, offset + length)`: the bytes its framing occupied once it
     * has all arrived, with its payload in [into], else 0. Throws [HttpParseException] as decodeChunked does.
     */
    public fun decode(input: ByteArray, offset: Int, length: Int, into: ByteBuilder): Int {
        Objects.checkFromIndexSize(offset, length, input.size)
        if (decoded == 0) into.reset()
        require(decoded <= length) { "the body is shorter than what was decoded of it already" }
        val end = Chunked.decode(input, offset + decoded, offset + length, into)
        if (end >= 0) {
            decoded = 0
            return end - offset
        }
        decoded = -end - 1 - offset
        return 0
    }
}

/** ChunkedBodyStream's state machine as straight-line loops over an array. */
internal object Chunked {
    private const val MAX_CHUNK_EXTENSION = 4096

    private fun refuse(code: Int): Nothing = throw Refusals.exception(code)

    /**
     * Decodes the chunks from [from], which starts a chunk, to [end], appending their payloads to [into]: the index
     * past the body when it is all there; otherwise `-boundary - 1`, where boundary is the start of the first chunk
     * not all there, every chunk before it decoded into [into]. Chunks are decoded independently, so decoding again
     * from a boundary gives what decoding from the body's start would.
     */
    fun decode(input: ByteArray, from: Int, end: Int, into: ByteBuilder): Int {
        val hex = Ascii.HEX
        var pos = from
        while (true) {
            val chunk = pos
            val incomplete = -chunk - 1

            // ---- chunk-size [; extension] CRLF ----
            if (pos >= end) return incomplete
            var size = 0L
            var digits = 0
            var c: Int
            while (true) {
                c = input[pos].toInt() and 0xFF
                val h = hex[c].toInt()
                if (h < 0) break
                if (digits >= 16) refuse(Refusals.CHUNK_OVERFLOW)
                size = (size shl 4) or h.toLong()
                // 16 digits reach 2^64 - 1, a negative Long: refused, not cut to its low bits
                if (size < 0) refuse(Refusals.CHUNK_OVERFLOW)
                digits++
                if (++pos >= end) return incomplete
            }
            if (digits == 0) {
                if (c == SP || c == HTAB) refuse(Refusals.CHUNK_LEADING_WHITESPACE)
                if (c == '-'.code) refuse(Refusals.CHUNK_NEGATIVE)
                refuse(Refusals.CHUNK_SIZE_CHARACTER)
            }
            if (digits == 1 && size == 0L && (c == 'x'.code || c == 'X'.code)) refuse(Refusals.CHUNK_HEX_PREFIX)
            if (c == '_'.code) refuse(Refusals.CHUNK_UNDERSCORE)
            if (c == ';'.code) {
                pos++
                var extension = 0
                while (true) {
                    if (pos >= end) return incomplete
                    val e = input[pos].toInt()
                    if (extension > MAX_CHUNK_EXTENSION) refuse(Refusals.CHUNK_EXTENSION_TOO_LARGE)
                    if (e == 0) refuse(Refusals.CHUNK_EXTENSION_NUL)
                    if (e == LF) refuse(Refusals.CHUNK_EXTENSION_BARE_LF)
                    if (e == CR) {
                        if (extension == 0) refuse(Refusals.CHUNK_BARE_SEMICOLON)
                        break
                    }
                    extension++
                    pos++
                }
            } else if (c == LF) {
                refuse(Refusals.CHUNK_SIZE_BARE_LF)
            } else if (c != CR) {
                refuse(Refusals.CHUNK_SIZE_CRLF)
            }
            // the CR at pos, then its LF
            if (++pos >= end) return incomplete
            if (input[pos].toInt() != LF) refuse(Refusals.CHUNK_SIZE_CRLF)
            pos++

            if (size == 0L) {
                // ---- trailer lines up to an empty line ----
                while (true) {
                    if (pos >= end) return incomplete
                    if (pos + 1 < end && input[pos].toInt() == CR && input[pos + 1].toInt() == LF) return pos + 2
                    if (input[pos].toInt() == LF) refuse(Refusals.TRAILER_BARE_LF)
                    while (true) {
                        if (pos >= end) return incomplete
                        val t = input[pos].toInt()
                        if (t == LF && input[pos - 1].toInt() != CR) refuse(Refusals.TRAILER_BARE_LF)
                        if (t == CR && pos + 1 < end && input[pos + 1].toInt() == LF) {
                            pos += 2
                            break
                        }
                        pos++
                    }
                }
            }

            // ---- chunk-data CRLF ----
            if (size > end - pos) return incomplete
            val data = pos
            pos += size.toInt()
            if (pos + 1 >= end) return incomplete
            val d = input[pos].toInt()
            if (d == LF && input[pos - 1].toInt() != CR) refuse(Refusals.CHUNK_DATA_BARE_LF)
            if (d != CR || input[pos + 1].toInt() != LF) refuse(Refusals.CHUNK_DATA_CRLF)
            pos += 2
            into.append(input, data, size.toInt())
        }
    }
}
