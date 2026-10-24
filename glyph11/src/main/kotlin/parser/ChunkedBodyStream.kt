package ktron.glyph11.parser

import ktron.glyph11.ByteSpan
import ktron.glyph11.HttpParseException
import java.nio.ByteBuffer

/**
 * Result of a single [ChunkedBodyStream.tryReadChunk] call.
 */
public enum class ChunkResult {

    /** Incomplete data - wait for more bytes from the connection. */
    NeedMoreData,

    /** A complete chunk's decoded payload is available. */
    Chunk,

    /** Terminal chunk (and any trailers) consumed - body is done. */
    Completed,

}

/**
 * Stateful streaming parser for chunked transfer-encoding (RFC 9112 7.1).
 * Validates chunks incrementally so the caller can advance its read buffer
 * after each call without re-parsing from byte 0.
 *
 * Upstream this is a mutable struct; use [copy] to obtain an independent snapshot of the state.
 */
public class ChunkedBodyStream {

    private enum class Phase {
        ChunkSize,
        Extension,
        HeaderCrlf,
        ChunkData,
        DataCrlf,
        Trailers,
        Complete,
    }

    private var phase: Phase = Phase.ChunkSize // starts at ChunkSize

    private var chunkSize: Long = 0 // parsed size for current chunk

    private var remaining: Long = 0 // bytes left in ChunkData phase

    private var digitCount: Int = 0 // hex digits seen in ChunkSize

    private var extensionBytes: Int = 0 // extension bytes (4096 limit)

    /** Bytes consumed from the input by the last call. */
    public var bytesConsumed: Int = 0
        private set

    /** Offset (relative to the input position) of the chunk payload of the last call (valid for [ChunkResult.Chunk]). */
    public var dataOffset: Int = 0
        private set

    /** Length of the chunk payload of the last call (valid for [ChunkResult.Chunk]). */
    public var dataLength: Int = 0
        private set

    /**
     * Reads the next chunk from [input] (from its position until its limit; the buffer's position
     * and limit are not modified). Call in a loop after each read from the connection.
     *
     * Results of the call are available via [bytesConsumed], [dataOffset] and [dataLength]:
     *
     * - [ChunkResult.Chunk]: payload at input[dataOffset until dataOffset + dataLength], advance by [bytesConsumed].
     * - [ChunkResult.Completed]: terminal chunk consumed, body is done.
     * - [ChunkResult.NeedMoreData]: incomplete, wait for more data.
     *
     * @param input The unprocessed bytes from the connection.
     * @throws ktron.glyph11.HttpParseException Thrown on malformed chunked encoding.
     */
    public fun tryReadChunk(input: ByteBuffer): ChunkResult {
        bytesConsumed = 0
        dataOffset = 0
        dataLength = 0

        val data = ByteSpan(input)
        val length = data.length

        var pos = 0

        // Snapshot state so we can roll back on NeedMoreData.
        // The caller re-presents the same bytes, so partial state must not persist.
        val snapshotPhase = phase
        val snapshotChunkSize = chunkSize
        val snapshotRemaining = remaining
        val snapshotDigitCount = digitCount
        val snapshotExtensionBytes = extensionBytes

        fun rollback() {
            phase = snapshotPhase
            chunkSize = snapshotChunkSize
            remaining = snapshotRemaining
            digitCount = snapshotDigitCount
            extensionBytes = snapshotExtensionBytes
        }

        while (pos < length) {
            when (phase) {
                Phase.ChunkSize -> {
                    val c = data.at(pos)

                    // Reject leading whitespace (only at start of size, i.e. no digits yet)
                    if (digitCount == 0 && (c == ' '.code || c == '\t'.code))
                        throw HttpParseException("Leading whitespace in chunk size.")

                    // Reject negative sign
                    if (digitCount == 0 && c == '-'.code)
                        throw HttpParseException("Negative chunk size.")

                    // Reject 0x prefix
                    if (digitCount == 1 && chunkSize == 0L &&
                        (c == 'x'.code || c == 'X'.code)
                    )
                        throw HttpParseException("Hex prefix in chunk size.")

                    if (isHexDigit(c)) {
                        if (digitCount >= 16)
                            throw HttpParseException("Chunk size overflow.")

                        chunkSize = (chunkSize shl 4) or hexVal(c).toLong()
                        // 16 digits reach 2^64 - 1, a negative Long: such a size is refused, not cut to its low bits
                        if (chunkSize < 0)
                            throw HttpParseException("Chunk size overflow.")
                        digitCount++
                        pos++
                        continue
                    }

                    // Non-hex character
                    if (digitCount == 0)
                        throw HttpParseException("Invalid character in chunk size.")

                    // Reject underscore after digits
                    if (c == '_'.code)
                        throw HttpParseException("Underscore in chunk size.")

                    if (c == ';'.code) {
                        extensionBytes = 0
                        phase = Phase.Extension
                        pos++ // skip ';'
                        continue
                    }

                    if (c == '\n'.code)
                        throw HttpParseException("Bare LF after chunk size.")

                    if (c == '\r'.code) {
                        phase = Phase.HeaderCrlf
                        pos++
                        continue
                    }

                    throw HttpParseException("Missing CRLF after chunk size.")
                }

                Phase.Extension -> {
                    val c = data.at(pos)

                    if (extensionBytes > MaxChunkExtensionBytes)
                        throw HttpParseException("Chunk extension too large.")

                    if (c == 0)
                        throw HttpParseException("NUL byte in chunk extension.")

                    if (c == '\n'.code)
                        throw HttpParseException("Bare LF in chunk extension.")

                    if (c == '\r'.code) {
                        if (extensionBytes == 0)
                            throw HttpParseException("Bare semicolon with no chunk extension name.")

                        phase = Phase.HeaderCrlf
                        pos++
                        continue
                    }

                    extensionBytes++
                    pos++
                    continue
                }

                Phase.HeaderCrlf -> {
                    // CR was already consumed, expect LF
                    if (data.at(pos) != '\n'.code)
                        throw HttpParseException("Missing CRLF after chunk size.")

                    pos++

                    if (chunkSize == 0L) {
                        // Terminal chunk - move to trailers
                        phase = Phase.Trailers
                        continue
                    }

                    remaining = chunkSize
                    phase = Phase.ChunkData
                    dataOffset = pos
                    continue
                }

                Phase.ChunkData -> {
                    val available = length - pos
                    if (available < remaining) {
                        rollback()
                        return ChunkResult.NeedMoreData
                    }

                    // All chunk data is present
                    dataOffset = pos
                    dataLength = chunkSize.toInt()
                    pos += remaining.toInt()
                    remaining = 0
                    phase = Phase.DataCrlf
                    continue
                }

                Phase.DataCrlf -> {
                    if (pos + 1 >= length) {
                        rollback()
                        return ChunkResult.NeedMoreData
                    }

                    if (data.at(pos) == '\n'.code && (pos == 0 || data.at(pos - 1) != '\r'.code))
                        throw HttpParseException("Bare LF after chunk data.")

                    if (data.at(pos) != '\r'.code || data.at(pos + 1) != '\n'.code)
                        throw HttpParseException("Missing CRLF after chunk data.")

                    pos += 2

                    // Reset per-chunk state
                    phase = Phase.ChunkSize
                    chunkSize = 0
                    digitCount = 0
                    extensionBytes = 0

                    bytesConsumed = pos
                    return ChunkResult.Chunk
                }

                Phase.Trailers -> {
                    // Check for empty line = end of trailers
                    if (pos + 1 < length &&
                        data.at(pos) == '\r'.code && data.at(pos + 1) == '\n'.code
                    ) {
                        pos += 2
                        phase = Phase.Complete
                        bytesConsumed = pos
                        return ChunkResult.Completed
                    }

                    if (data.at(pos) == '\n'.code)
                        throw HttpParseException("Bare LF in chunked trailer.")

                    // Read a trailer line until CRLF
                    while (pos < length) {
                        if (data.at(pos) == '\n'.code && (pos == 0 || data.at(pos - 1) != '\r'.code))
                            throw HttpParseException("Bare LF in chunked trailer.")

                        if (pos + 1 < length &&
                            data.at(pos) == '\r'.code && data.at(pos + 1) == '\n'.code
                        ) {
                            pos += 2
                            // Back to top of Trailers to check for another trailer or empty line
                            break
                        }

                        pos++
                    }

                    if (pos >= length) {
                        rollback()
                        return ChunkResult.NeedMoreData
                    }

                    continue
                }

                Phase.Complete -> {
                    bytesConsumed = 0
                    return ChunkResult.Completed
                }
            }
        }

        // Ran out of input
        rollback()
        return ChunkResult.NeedMoreData
    }

    /**
     * Returns a copy of the current state (replaces the copy semantics of the upstream struct).
     */
    public fun copy(): ChunkedBodyStream {
        val clone = ChunkedBodyStream()

        clone.phase = phase
        clone.chunkSize = chunkSize
        clone.remaining = remaining
        clone.digitCount = digitCount
        clone.extensionBytes = extensionBytes

        clone.bytesConsumed = bytesConsumed
        clone.dataOffset = dataOffset
        clone.dataLength = dataLength

        return clone
    }

    private companion object {

        private const val MaxChunkExtensionBytes = 4096

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

}
