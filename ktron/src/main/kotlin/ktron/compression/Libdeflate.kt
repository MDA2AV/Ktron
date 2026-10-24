package ktron.compression

import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG

// libdeflate.h, libdeflate 1.19 (Ubuntu noble's libdeflate0): sizes are size_t, the compressor an opaque pointer.
internal object Libdeflate {
    private val lib = library("libdeflate.so.0", "libdeflate0")

    // struct libdeflate_compressor *libdeflate_alloc_compressor(int compression_level)
    private val ALLOC = lib.downcall("libdeflate_alloc_compressor", FunctionDescriptor.of(JAVA_LONG, JAVA_INT))

    // size_t libdeflate_gzip_compress(struct libdeflate_compressor *, const void *in, size_t in_nbytes,
    //                                 void *out, size_t out_nbytes_avail): 0 when the output did not fit
    private val GZIP_COMPRESS = lib.downcall(
        "libdeflate_gzip_compress",
        FunctionDescriptor.of(JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG),
    )

    // size_t libdeflate_gzip_compress_bound(struct libdeflate_compressor *, size_t in_nbytes)
    private val GZIP_COMPRESS_BOUND = lib.downcall("libdeflate_gzip_compress_bound", FunctionDescriptor.of(JAVA_LONG, JAVA_LONG, JAVA_LONG))

    // void libdeflate_free_compressor(struct libdeflate_compressor *)
    private val FREE_COMPRESSOR = lib.downcall("libdeflate_free_compressor", FunctionDescriptor.ofVoid(JAVA_LONG))

    fun allocCompressor(level: Int): Long {
        val c = ALLOC.invokeExact(level) as Long
        if (c == 0L) throw OutOfMemoryError("libdeflate_alloc_compressor($level)")
        return c
    }

    fun gzipCompress(compressor: Long, input: Long, inputLength: Long, output: Long, outputCapacity: Long): Long =
        GZIP_COMPRESS.invokeExact(compressor, input, inputLength, output, outputCapacity) as Long

    fun gzipCompressBound(compressor: Long, inputLength: Long): Long = GZIP_COMPRESS_BOUND.invokeExact(compressor, inputLength) as Long

    fun freeCompressor(compressor: Long) {
        FREE_COMPRESSOR.invokeExact(compressor)
    }
}
