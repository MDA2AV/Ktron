package ktron.glyph11.validation

import ktron.glyph11.ByteSpan
import ktron.glyph11.protocol.BinaryRequest
import java.nio.ByteBuffer

/**
 * Helpers for Content-Length-framed body reading.
 */
public object ContentLengthBodyReader {

    private val ContentLengthName: ByteArray = "content-length".toByteArray(Charsets.US_ASCII)

    /**
     * Returns true if the buffer contains at least [contentLength] bytes.
     */
    public fun hasCompleteBody(body: ByteBuffer, contentLength: Long): Boolean = body.remaining() >= contentLength

    /**
     * Parses the Content-Length header value from the request. Returns -1 if absent or invalid.
     */
    public fun parseContentLength(request: BinaryRequest): Long {
        val headers = request.headers

        for (i in 0 until headers.count) {
            if (!asciiEqualsIgnoreCase(ByteSpan(headers.keyAt(i)), ContentLengthName))
                continue

            val value = ByteSpan(headers.valueAt(i))

            // Trim OWS
            var start = 0
            while (start < value.length && (value.at(start) == ' '.code || value.at(start) == '\t'.code))
                start++
            var end = value.length
            while (end > start && (value.at(end - 1) == ' '.code || value.at(end - 1) == '\t'.code))
                end--

            // Handle comma-separated: take first segment
            val comma = value.indexOfByte(','.code, start, end)
            if (comma >= 0)
                end = comma

            // Parse digits
            var result = 0L
            if (end - start == 0) return -1
            for (j in start until end) {
                val b = value.at(j)
                if (b < '0'.code || b > '9'.code) return -1
                result = result * 10 + (b - '0'.code)
                if (result < 0) return -1 // overflow
            }

            return result
        }

        return -1
    }

    private fun asciiEqualsIgnoreCase(a: ByteSpan, b: ByteArray): Boolean {
        if (a.length != b.size) return false

        for (i in 0 until a.length) {
            if ((a.at(i) or 0x20) != (b[i].toInt() and 0xFF)) return false
        }

        return true
    }

}
