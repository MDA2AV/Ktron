package ktron.compression

import ktron.glyph11.ByteBuilder
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.foreign.ValueLayout.JAVA_LONG_UNALIGNED
import java.util.Objects

/**
 * Brotli on libbrotlienc (libbrotlienc.so.1), one BrotliEncoderCompress per body. [quality] is 0-11 and
 * [lgwin] the window's log2, 10-24.
 *
 * The default quality, 0, is json-comp's best brotli setting, though gzip at level 1 beats it; at 0 the
 * window does not change the output of bodies that size, so lgwin stays brotli's own default. At quality 5-9
 * an lgwin of 16 picks smaller hashers; above 16, quality 9 mallocs a 32 MiB table per call, which glibc
 * maps fresh each time (about 10x slower on json-comp's bodies).
 *
 * The body is copied into a native buffer the instance reuses and compressed into another, so a call
 * allocates nothing on the JVM heap once the buffers fit the largest body seen. Brotli itself mallocs and
 * frees its state inside each call: it has no reset. [close] frees the native side.
 */
class BrotliEncoder(val quality: Int = 0, val lgwin: Int = 22) : ContentEncoder, AutoCloseable {
    override val name: String get() = "br"

    // Before any property allocates: a refused setting must not leak.
    init {
        require(quality in 0..11) { "brotli's qualities are 0-11, not $quality" }
        require(lgwin in 10..24) { "brotli's lgwin is 10-24, not $lgwin" }
    }

    private val source = NativeBuffer()
    private val target = NativeBuffer()
    private var scratch = ByteArray(0)
    private var open = true

    // The longest input the target buffer holds the BrotliEncoderMaxCompressedSize of.
    private var fits = -1

    // BrotliEncoderCompress's in/out size_t.
    private val encodedSize: Long

    init {
        reserve(0) // libbrotlienc loads here, as libdeflate does in GzipEncoder, not on the first body
        encodedSize = Libc.malloc(8)
    }

    override fun encode(input: ByteArray, offset: Int, length: Int, output: ByteBuilder): Int {
        Objects.checkFromIndexSize(offset, length, input.size)
        check(open) { "closed" }
        if (length > fits) reserve(length)
        MemorySegment.copy(input, offset, MEM, JAVA_BYTE, source.address, length)
        MEM.set(JAVA_LONG_UNALIGNED, encodedSize, target.capacity)
        val ok = BrotliEnc.compress(quality, lgwin, BrotliEnc.MODE_GENERIC, length.toLong(), source.address, encodedSize, target.address)
        check(ok == 1) { "BrotliEncoderCompress failed on $length bytes" }
        val n = MEM.get(JAVA_LONG_UNALIGNED, encodedSize).toInt()
        MemorySegment.copy(MEM, JAVA_BYTE, target.address, scratch, 0, n)
        output.append(scratch, 0, n)
        return n
    }

    // A target of at least BrotliEncoderMaxCompressedSize also lets brotli fall back to an uncompressed stream.
    private fun reserve(length: Int) {
        val longest = maxOf(length, minOf(2L * maxOf(fits, 0), Int.MAX_VALUE.toLong()).toInt(), 16 * 1024)
        val bound = BrotliEnc.maxCompressedSize(longest.toLong())
        check(bound > 0) { "BrotliEncoderMaxCompressedSize overflows at $longest bytes" }
        source.reserve(longest.toLong())
        target.reserve(bound)
        scratch = ByteArray(Math.toIntExact(bound))
        fits = longest
    }

    override fun close() {
        if (!open) return
        open = false
        source.free()
        target.free()
        Libc.free(encodedSize)
        scratch = ByteArray(0)
        fits = -1
    }
}
