package ktron.glyph11.validation

import ktron.glyph11.ByteSpan
import ktron.glyph11.HttpParseException
import java.nio.ByteBuffer

/**
 * Validates chunked transfer-encoding body format per RFC 9112 7.1.
 */
public object ChunkedBodyValidator {

    private const val MaxChunkExtensionBytes = 4096

    /**
     * Validates chunked body format (from the buffer's position until its limit; position and limit
     * are not modified).
     *
     * @return The number of bytes consumed if the body is valid and complete, or -1 if it is
     * incomplete (more data is needed).
     * @throws ktron.glyph11.HttpParseException Thrown if the body is malformed.
     */
    public fun tryValidate(body: ByteBuffer): Int {
        val data = ByteSpan(body)
        val length = data.length

        var pos = 0

        while (pos < length) {
            // --- Parse chunk size (hex digits) ---

            // Reject leading whitespace
            if (pos < length && (data.at(pos) == ' '.code || data.at(pos) == '\t'.code))
                throw HttpParseException("Leading whitespace in chunk size.")

            // Reject negative sign
            if (pos < length && data.at(pos) == '-'.code)
                throw HttpParseException("Negative chunk size.")

            // Reject 0x prefix
            if (pos + 1 < length && data.at(pos) == '0'.code &&
                (data.at(pos + 1) == 'x'.code || data.at(pos + 1) == 'X'.code)
            )
                throw HttpParseException("Hex prefix in chunk size.")

            // Read hex digits
            var chunkSize = 0L
            var digitCount = 0
            while (pos < length && isHexDigit(data.at(pos))) {
                if (digitCount >= 16) // overflow guard: 16 hex digits = 64 bits
                    throw HttpParseException("Chunk size overflow.")

                chunkSize = (chunkSize shl 4) or hexVal(data.at(pos)).toLong()
                // 16 digits reach 2^64 - 1, a negative Long: such a size is refused, not cut to its low bits
                if (chunkSize < 0)
                    throw HttpParseException("Chunk size overflow.")
                digitCount++
                pos++
            }

            if (digitCount == 0) {
                if (pos < length)
                    throw HttpParseException("Invalid character in chunk size.")
                return -1 // incomplete
            }

            // Reject underscore after digits (e.g., "5_0")
            if (pos < length && data.at(pos) == '_'.code)
                throw HttpParseException("Underscore in chunk size.")

            // --- Parse optional chunk extensions (;token=value) ---
            if (pos < length && data.at(pos) == ';'.code) {
                val extStart = pos
                pos++ // skip ';'

                while (pos < length) {
                    if (pos - extStart > MaxChunkExtensionBytes)
                        throw HttpParseException("Chunk extension too large.")

                    // Check for NUL
                    if (data.at(pos) == 0)
                        throw HttpParseException("NUL byte in chunk extension.")

                    // bare LF
                    if (data.at(pos) == '\n'.code)
                        throw HttpParseException("Bare LF in chunk extension.")

                    // CRLF ends the extension
                    if (data.at(pos) == '\r'.code)
                        break

                    // Another extension
                    if (data.at(pos) == ';'.code) {
                        pos++
                        continue
                    }

                    pos++
                }
            }

            // --- Expect CRLF after chunk size (+ extensions) ---
            if (pos + 1 >= length)
                return -1 // incomplete

            if (data.at(pos) == '\n'.code)
                throw HttpParseException("Bare LF after chunk size.")

            if (data.at(pos) != '\r'.code || data.at(pos + 1) != '\n'.code)
                throw HttpParseException("Missing CRLF after chunk size.")

            pos += 2

            // --- Last chunk (size == 0): parse optional trailers ---
            if (chunkSize == 0L) {
                // Trailers: zero or more header lines, terminated by CRLF
                while (true) {
                    if (pos >= length)
                        return -1 // incomplete

                    // Empty line = end of trailers
                    if (pos + 1 < length && data.at(pos) == '\r'.code && data.at(pos + 1) == '\n'.code) {
                        pos += 2
                        return pos
                    }

                    // Bare LF as end?
                    if (data.at(pos) == '\n'.code)
                        throw HttpParseException("Bare LF in chunked trailer.")

                    // Read trailer line until CRLF
                    var foundCrlf = false
                    while (pos < length) {
                        if (data.at(pos) == '\n'.code && (pos == 0 || data.at(pos - 1) != '\r'.code))
                            throw HttpParseException("Bare LF in chunked trailer.")

                        if (pos + 1 < length && data.at(pos) == '\r'.code && data.at(pos + 1) == '\n'.code) {
                            pos += 2
                            foundCrlf = true
                            break
                        }

                        pos++
                    }

                    if (!foundCrlf)
                        return -1 // incomplete trailer line
                }
            }

            // --- Read chunk data (exactly chunkSize bytes) ---
            if (chunkSize > length - pos)
                return -1 // incomplete

            // Check for NUL-spill: data claims to extend beyond what was sent
            // (handled by the length check above)

            pos += chunkSize.toInt()

            // --- Expect CRLF after chunk data ---
            if (pos + 1 >= length)
                return -1 // incomplete

            if (data.at(pos) == '\n'.code && (pos == 0 || data.at(pos - 1) != '\r'.code))
                throw HttpParseException("Bare LF after chunk data.")

            if (data.at(pos) != '\r'.code || data.at(pos + 1) != '\n'.code)
                throw HttpParseException("Missing CRLF after chunk data.")

            pos += 2
        }

        return -1 // incomplete
    }

    private fun isHexDigit(b: Int): Boolean =
        (b >= '0'.code && b <= '9'.code) ||
            (b >= 'a'.code && b <= 'f'.code) ||
            (b >= 'A'.code && b <= 'F'.code)

    private fun hexVal(b: Int): Int = when (b) {
        in '0'.code..'9'.code -> b - '0'.code
        in 'a'.code..'f'.code -> b - 'a'.code + 10
        in 'A'.code..'F'.code -> b - 'A'.code + 10
        else -> 0
    }

}
