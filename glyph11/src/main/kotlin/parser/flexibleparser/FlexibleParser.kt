package ktron.glyph11.parser.flexibleparser

import ktron.glyph11.ByteSpan
import ktron.glyph11.EmptyBuffer
import ktron.glyph11.HttpParseException
import ktron.glyph11.parser.ParserConstants
import ktron.glyph11.protocol.BinaryRequest
import java.nio.ByteBuffer

/**
 * A fast, minimal-validation HTTP/1.1 header parser.
 * Optimized for throughput; silently skips malformed header lines
 * and does not enforce RFC token/field-value rules.
 *
 * For security-sensitive workloads, use [ktron.glyph11.parser.ultrahardened.UltraHardenedParser] instead.
 */
public object FlexibleParser {

    // ---- Line terminators ----
    private val Crlf = ParserConstants.Crlf
    private val CrlfCrlf = ParserConstants.CrlfCrlf

    // ---- Special bytes ----
    private const val Space = 0x20 // ' '
    private const val Question = 0x3F // '?'
    private const val QuerySeparator = 0x26 // '&'
    private const val Equal = 0x3D // '='
    private const val Colon = 0x3A // ':'

    /**
     * Tries to extract a complete HTTP/1.1 header block (request line + headers).
     *
     * All parsed fields are read-only [ByteBuffer] slices of [input] (zero-copy). The input is read
     * from its position until its limit; its position and limit are not modified.
     *
     * @param input Contiguous buffer containing the full HTTP request header.
     * @param request Target to populate with parsed method, path, headers, etc.
     * @return Number of bytes consumed (header + terminators - 1), or -1 if the `\r\n\r\n`
     * terminator was not found and more data is needed.
     */
    public fun tryExtractFullHeader(input: ByteBuffer, request: BinaryRequest): Int {
        val span = ByteSpan(input, request.views)

        val headerEnd = span.indexOf(CrlfCrlf)
        if (headerEnd < 0) return -1

        val requestLineEnd = span.indexOf(Crlf)
        if (requestLineEnd < 0) throw HttpParseException("Invalid HTTP/1.1 request line.")

        val firstSpaceIndex = span.indexOfByte(Space, 0, requestLineEnd)
        if (firstSpaceIndex < 0) throw HttpParseException("Invalid request line.")

        val secondSpaceIndex = span.indexOfByte(Space, firstSpaceIndex + 1, requestLineEnd)
        if (secondSpaceIndex < 0) throw HttpParseException("Invalid request line.")

        request.method = span.slice(0, firstSpaceIndex)

        val urlStart = firstSpaceIndex + 1
        val urlLen = secondSpaceIndex - urlStart

        val queryStartRel = span.indexOfByte(Question, urlStart, urlStart + urlLen) // '?'
        val queryStartIndex = if (queryStartRel < 0) -1 else queryStartRel - urlStart

        if (queryStartIndex >= 0) {
            // Route is path portion only
            request.path = span.slice(urlStart, queryStartIndex)

            // Query part after '?'
            val queryAbsStart = urlStart + queryStartIndex + 1
            val queryLen = urlLen - (queryStartIndex + 1)
            val queryEnd = queryAbsStart + queryLen

            var cur = 0
            while (cur < queryLen) {
                val pairAbsStart = queryAbsStart + cur

                val ampAbs = span.indexOfByte(QuerySeparator, queryAbsStart + cur, queryEnd) // '&'
                val amp = if (ampAbs < 0) -1 else ampAbs - (queryAbsStart + cur)
                val pairLen = if (amp < 0) queryLen - cur else amp

                val eqAbs = span.indexOfByte(Equal, pairAbsStart, pairAbsStart + pairLen) // '='
                val eq = if (eqAbs < 0) -1 else eqAbs - pairAbsStart

                if (eq > 0) {
                    request.queryParameters.add(
                        span.slice(pairAbsStart, eq),
                        span.slice(pairAbsStart + eq + 1, pairLen - (eq + 1))
                    )
                } else if (eq < 0 && pairLen > 0) {
                    request.queryParameters.add(
                        span.slice(pairAbsStart, pairLen),
                        EmptyBuffer
                    )
                }

                cur += pairLen + (if (amp < 0) 0 else 1)
            }
        } else {
            request.path = span.slice(urlStart, urlLen)
        }

        var lineStart = requestLineEnd + 2

        while (true) {
            val lineEndAbs = span.indexOf(Crlf, lineStart, span.length)
            if (lineEndAbs < 0) throw HttpParseException("Invalid headers.")

            val lineLen = lineEndAbs - lineStart

            if (lineLen == 0)
                break

            val colonAbs = span.indexOfByte(Colon, lineStart, lineStart + lineLen)
            val colon = if (colonAbs < 0) -1 else colonAbs - lineStart
            if (colon > 0) {
                val keyAbsStart = lineStart
                var valAbsStart = lineStart + colon + 1

                while (valAbsStart < lineStart + lineLen) {
                    val b = span.at(valAbsStart)
                    if (b != ' '.code && b != '\t'.code) break
                    valAbsStart++
                }

                val valLen = (lineStart + lineLen) - valAbsStart

                request.headers.add(
                    span.slice(keyAbsStart, colon),
                    span.slice(valAbsStart, valLen)
                )
            }

            lineStart += lineLen + 2
        }

        return headerEnd + 3
    }

}
