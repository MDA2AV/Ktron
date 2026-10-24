package ktron.compression.bench

import ktron.compression.BrotliEnc
import ktron.compression.ContentEncoder
import ktron.compression.LINKER
import ktron.compression.Libc
import ktron.compression.Libdeflate
import ktron.compression.MEM
import ktron.compression.NativeBuffer
import ktron.compression.library
import ktron.glyph11.ByteBuilder
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.lang.foreign.ValueLayout.JAVA_LONG_UNALIGNED
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType

// The designs the committed encoders were measured against, kept so the comparison can be rerun.

/** libdeflate through a critical downcall on heap segments: no copy in, compressed straight into the scratch. */
class GzipCriticalEncoder(level: Int) : ContentEncoder, AutoCloseable {
    override val name: String get() = "gzip"
    private val compressor = Libdeflate.allocCompressor(level)
    private var scratch = ByteArray(0)
    private var scratchSegment: MemorySegment = MemorySegment.ofArray(scratch)
    private var fits = -1

    override fun encode(input: ByteArray, offset: Int, length: Int, output: ByteBuilder): Int {
        if (length > fits) {
            val longest = maxOf(length, 16 * 1024)
            scratch = ByteArray(Libdeflate.gzipCompressBound(compressor, longest.toLong()).toInt())
            scratchSegment = MemorySegment.ofArray(scratch)
            fits = longest
        }
        val source = MemorySegment.ofArray(input).asSlice(offset.toLong(), length.toLong())
        val n = (GZIP_COMPRESS_HEAP.invokeExact(compressor, source, length.toLong(), scratchSegment, scratch.size.toLong()) as Long).toInt()
        check(n > 0)
        output.append(scratch, 0, n)
        return n
    }

    override fun close() = Libdeflate.freeCompressor(compressor)

    companion object {
        private val GZIP_COMPRESS_HEAP = LINKER.downcallHandle(
            library("libdeflate.so.0", "libdeflate0").find("libdeflate_gzip_compress").orElseThrow(),
            FunctionDescriptor.of(JAVA_LONG, JAVA_LONG, ADDRESS, JAVA_LONG, ADDRESS, JAVA_LONG),
            Linker.Option.critical(true),
        )
    }
}

/**
 * Brotli's instance API: create, set quality, lgwin and the size hint, one FINISH, destroy. With [bump], the
 * state's memory comes from a block this encoder keeps (upcalls to [Bump]) instead of malloc and free.
 */
class BrotliStreamEncoder(private val quality: Int, private val lgwin: Int, bump: Boolean) : ContentEncoder, AutoCloseable {
    override val name: String get() = "br"
    private val source = NativeBuffer()
    private val target = NativeBuffer()
    private var scratch = ByteArray(0)
    private var fits = -1
    private val slots = Libc.malloc(32) // available_in, next_in, available_out, next_out
    val allocator: Bump? = if (bump) Bump() else null

    override fun encode(input: ByteArray, offset: Int, length: Int, output: ByteBuilder): Int {
        if (length > fits) {
            val longest = maxOf(length, 16 * 1024)
            val bound = BrotliEnc.maxCompressedSize(longest.toLong())
            source.reserve(longest.toLong())
            target.reserve(bound)
            scratch = ByteArray(bound.toInt())
            fits = longest
        }
        MemorySegment.copy(input, offset, MEM, JAVA_BYTE, source.address, length)
        val state = BrotliEnc.createInstance(allocator?.allocStub ?: 0L, allocator?.freeStub ?: 0L, 0L)
        check(state != 0L)
        BrotliEnc.setParameter(state, BrotliEnc.PARAM_QUALITY, quality)
        BrotliEnc.setParameter(state, BrotliEnc.PARAM_LGWIN, lgwin)
        BrotliEnc.setParameter(state, BrotliEnc.PARAM_SIZE_HINT, length)
        MEM.set(JAVA_LONG_UNALIGNED, slots, length.toLong())
        MEM.set(JAVA_LONG_UNALIGNED, slots + 8, source.address)
        MEM.set(JAVA_LONG_UNALIGNED, slots + 16, target.capacity)
        MEM.set(JAVA_LONG_UNALIGNED, slots + 24, target.address)
        val ok = BrotliEnc.compressStream(state, BrotliEnc.OPERATION_FINISH, slots, slots + 8, slots + 16, slots + 24, 0L)
        val finished = BrotliEnc.isFinished(state)
        BrotliEnc.destroyInstance(state)
        allocator?.reset()
        check(ok == 1 && finished == 1) { "BrotliEncoderCompressStream did not finish" }
        val n = (target.capacity - MEM.get(JAVA_LONG_UNALIGNED, slots + 16)).toInt()
        MemorySegment.copy(MEM, JAVA_BYTE, target.address, scratch, 0, n)
        output.append(scratch, 0, n)
        return n
    }

    override fun close() {
        source.free()
        target.free()
        Libc.free(slots)
        allocator?.free()
    }
}

/**
 * A bump allocator brotli's alloc_func and free_func call back into: one stream's allocations are carved from
 * one block, and the next stream starts over in the same memory, so its pages stay mapped and warm. What
 * does not fit goes to malloc, and the block grows to cover it next time. An upcall must not throw.
 */
class Bump {
    private var base = 0L
    private var capacity = 0L
    private var top = 0L
    private var spilled = 0L
    var allocs = 0L
        private set
    val allocStub: Long
    val freeStub: Long
    private val arena = Arena.ofShared()

    init {
        val lookup = MethodHandles.lookup()
        val j = java.lang.Long.TYPE
        val alloc = lookup.findVirtual(Bump::class.java, "alloc", MethodType.methodType(j, j, j)).bindTo(this)
        val free = lookup.findVirtual(Bump::class.java, "release", MethodType.methodType(Void.TYPE, j, j)).bindTo(this)
        allocStub = LINKER.upcallStub(alloc, FunctionDescriptor.of(JAVA_LONG, JAVA_LONG, JAVA_LONG), arena).address()
        freeStub = LINKER.upcallStub(free, FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_LONG), arena).address()
    }

    fun alloc(@Suppress("UNUSED_PARAMETER") opaque: Long, size: Long): Long {
        allocs++
        val rounded = (size + 63) and -64L
        if (top + rounded <= capacity) {
            val p = base + top
            top += rounded
            return p
        }
        spilled += rounded
        return RAW_MALLOC.invokeExact(size) as Long
    }

    fun release(@Suppress("UNUSED_PARAMETER") opaque: Long, address: Long) {
        if (address != 0L && (address < base || address >= base + capacity)) RAW_FREE.invokeExact(address)
    }

    fun reset() {
        if (spilled > 0) {
            val grown = top + spilled
            Libc.free(base)
            base = Libc.malloc(grown)
            capacity = grown
        }
        top = 0L
        spilled = 0L
    }

    fun free() {
        Libc.free(base)
        arena.close()
    }

    companion object {
        private val libc = LINKER.defaultLookup()
        private val RAW_MALLOC = LINKER.downcallHandle(libc.find("malloc").orElseThrow(), FunctionDescriptor.of(JAVA_LONG, JAVA_LONG))
        private val RAW_FREE = LINKER.downcallHandle(libc.find("free").orElseThrow(), FunctionDescriptor.ofVoid(JAVA_LONG))
    }
}
