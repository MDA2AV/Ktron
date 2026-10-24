package ktron.glyph11.validation

import ktron.glyph11.ByteSpan
import ktron.glyph11.parser.ParserConstants
import ktron.glyph11.protocol.BinaryRequest
import ktron.glyph11.protocol.BinaryResponse
import ktron.glyph11.protocol.KeyValueList
import java.nio.ByteBuffer

/**
 * Inspects parsed headers to determine the body framing strategy.
 * Parser-agnostic - works with any parser tier (Flexible, Hardened, UltraHardened).
 */
public object BodyFramingDetector {

    private val TransferEncodingName: ByteArray = "transfer-encoding".toByteArray(Charsets.US_ASCII)
    private val ContentLengthName: ByteArray = "content-length".toByteArray(Charsets.US_ASCII)
    private val ChunkedValue: ByteArray = "chunked".toByteArray(Charsets.US_ASCII)
    private val HeadMethodName: ByteArray = "head".toByteArray(Charsets.US_ASCII)
    private val ConnectMethodName: ByteArray = "connect".toByteArray(Charsets.US_ASCII)

    /**
     * Inspects the parsed headers in [request] and returns the body
     * framing kind (chunked, content-length, or none) without touching any body bytes.
     * Single pass over headers.
     */
    public fun detectBodyFraming(request: BinaryRequest): BodyFramingResult {
        val scan = scanHeaders(request.headers)

        // Chunked takes priority over Content-Length (RFC 9112 6.1)
        if (scan.hasChunkedTE)
            return BodyFramingResult.ForChunked

        val contentLengthValue = scan.contentLengthValue
        if (contentLengthValue != null && contentLengthValue.hasRemaining()) {
            val cl = parseContentLengthDigits(contentLengthValue)
            if (cl > 0)
                return BodyFramingResult.forContentLength(cl)
        }

        return BodyFramingResult.NoBody
    }

    /**
     * Inspects the parsed headers in [response] and returns the body framing
     * kind. Single pass over headers.
     *
     * Precedence follows RFC 9112 6: the status and method decide whether a body can exist at
     * all, then Transfer-Encoding, then Content-Length, and a response with none of those runs
     * until the connection closes.
     *
     * @param response The parsed response header.
     * @param requestMethod The method of the request that produced this response. It is REQUIRED,
     * and not a convenience: a response to HEAD carries the Content-Length the body would have had
     * and no body, so framing a response by its own headers alone reads the next response as this
     * one's content. Pass the method exactly as sent.
     */
    public fun detectResponseBodyFraming(response: BinaryResponse, requestMethod: ByteBuffer): BodyFramingResult {
        val status = response.status

        // RFC 9112 6.3 - these carry no body whatever the headers claim.
        if ((status >= 100 && status < 200) || status == 204 || status == 304)
            return BodyFramingResult.NoBody

        val method = ByteSpan(requestMethod)

        // A HEAD response is a GET response with the body removed: the framing headers describe a
        // body that is not there.
        if (method.equalsIgnoreCase(0, method.length, HeadMethodName))
            return BodyFramingResult.NoBody

        // A 2xx to CONNECT means the tunnel is open and everything after the header is opaque
        // relay traffic, not an HTTP body.
        if (status >= 200 && status < 300 &&
            method.equalsIgnoreCase(0, method.length, ConnectMethodName)
        )
            return BodyFramingResult.NoBody

        val scan = scanHeaders(response.headers)

        // Chunked takes priority over Content-Length (RFC 9112 6.1)
        if (scan.hasChunkedTE)
            return BodyFramingResult.ForChunked

        val contentLengthValue = scan.contentLengthValue
        if (contentLengthValue != null && contentLengthValue.hasRemaining()) {
            val cl = parseContentLengthDigits(contentLengthValue)
            if (cl > 0)
                return BodyFramingResult.forContentLength(cl)
            if (cl == 0L)
                return BodyFramingResult.NoBody
        }

        // No framing header at all. Unlike a request - which is simply bodyless here - a response
        // runs to end of connection (RFC 9112 6.3), which is also the only framing HTTP/1.0
        // origins ever had.
        return BodyFramingResult.ForUntilClose
    }

    private class HeaderScan(var hasChunkedTE: Boolean, var contentLengthValue: ByteBuffer?)

    private fun scanHeaders(headers: KeyValueList): HeaderScan {
        val scan = HeaderScan(false, null)

        for (i in 0 until headers.count) {
            val name = ByteSpan(headers.keyAt(i))

            if (name.length == 17 && name.equalsIgnoreCase(0, name.length, TransferEncodingName)) {
                val value = trimOws(headers.valueAt(i))
                val valueSpan = ByteSpan(value)
                if (valueSpan.equalsIgnoreCase(0, valueSpan.length, ChunkedValue))
                    scan.hasChunkedTE = true
            } else if (name.length == 14 && name.equalsIgnoreCase(0, name.length, ContentLengthName)) {
                scan.contentLengthValue = trimOws(headers.valueAt(i))
            }
        }

        return scan
    }

    private fun trimOws(value: ByteBuffer): ByteBuffer {
        val span = ByteSpan(value)

        var start = 0
        while (start < span.length && (span.at(start) == ' '.code || span.at(start) == '\t'.code))
            start++
        var end = span.length
        while (end > start && (span.at(end - 1) == ' '.code || span.at(end - 1) == '\t'.code))
            end--

        return span.slice(start, end - start)
    }

    private fun parseContentLengthDigits(input: ByteBuffer): Long {
        val span = ByteSpan(input)
        var length = span.length

        // Handle comma-separated: take first segment, without the OWS before its comma ("5 ,5" is 5)
        val comma = span.indexOfByte(','.code, 0, length)
        if (comma >= 0)
            length = comma
        while (length > 0 && (span.at(length - 1) == ' '.code || span.at(length - 1) == '\t'.code))
            length--

        if (length == 0) return -1

        var result = 0L
        for (j in 0 until length) {
            val b = span.at(j)
            if (b < '0'.code || b > '9'.code) return -1
            result = result * 10 + (b - '0'.code)
            // 2^63 and up: longer than any body may be, so refused as too large rather than read as no body
            if (result < 0) {
                for (k in j + 1 until length) if (span.at(k) < '0'.code || span.at(k) > '9'.code) return -1
                return Long.MAX_VALUE
            }
        }

        return result
    }

}
