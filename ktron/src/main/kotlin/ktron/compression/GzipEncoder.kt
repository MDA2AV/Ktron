package ktron.compression

import ktron.glyph11.ByteBuilder
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.util.Objects

/**
 * gzip on libdeflate (libdeflate.so.0), with one compressor per instance. [level] is libdeflate's 0-12: 1-9
 * track zlib's levels, 10-12 are its near-optimal parsing.
 *
 * The body is copied into a native buffer the instance reuses and compressed into another, so a call
 * allocates nothing once the buffers fit the largest body seen. [close] frees the native side.
 */
class GzipEncoder(val level: Int = 6) : ContentEncoder, AutoCloseable {
    override val name: String get() = "gzip"

    private var compressor = 0L
    private val source = NativeBuffer()
    private val target = NativeBuffer()
    private var scratch = ByteArray(0)

    // The longest input the target buffer holds the gzip_compress_bound of.
    private var fits = -1

    init {
        require(level in 0..12) { "libdeflate's levels are 0-12, not $level" }
        compressor = Libdeflate.allocCompressor(level)
    }

    override fun encode(input: ByteArray, offset: Int, length: Int, output: ByteBuilder): Int {
        Objects.checkFromIndexSize(offset, length, input.size)
        check(compressor != 0L) { "closed" }
        if (length > fits) reserve(length)
        MemorySegment.copy(input, offset, MEM, JAVA_BYTE, source.address, length)
        val n = Libdeflate.gzipCompress(compressor, source.address, length.toLong(), target.address, target.capacity).toInt()
        // Unreachable with the bound reserved, but 0 is libdeflate's "did not fit" and must not become an empty body.
        check(n > 0) { "libdeflate_gzip_compress did not fit $length bytes into ${target.capacity}" }
        MemorySegment.copy(MEM, JAVA_BYTE, target.address, scratch, 0, n)
        output.append(scratch, 0, n)
        return n
    }

    private fun reserve(length: Int) {
        val longest = maxOf(length, minOf(2L * maxOf(fits, 0), Int.MAX_VALUE.toLong()).toInt(), 16 * 1024)
        val bound = Libdeflate.gzipCompressBound(compressor, longest.toLong())
        source.reserve(longest.toLong())
        target.reserve(bound)
        scratch = ByteArray(Math.toIntExact(bound))
        fits = longest
    }

    override fun close() {
        if (compressor != 0L) {
            Libdeflate.freeCompressor(compressor)
            compressor = 0L
        }
        source.free()
        target.free()
        scratch = ByteArray(0)
        fits = -1
    }
}
