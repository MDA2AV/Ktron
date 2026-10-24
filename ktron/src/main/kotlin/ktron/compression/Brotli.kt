package ktron.compression

import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG

// brotli/encode.h, brotli 1.1.0 (Ubuntu noble's libbrotli1). BROTLI_BOOL and the enums are int, sizes size_t.
internal object BrotliEnc {
    private val lib = library("libbrotlienc.so.1", "libbrotli1")

    // BROTLI_BOOL BrotliEncoderCompress(int quality, int lgwin, BrotliEncoderMode mode, size_t input_size,
    //     const uint8_t *input_buffer, size_t *encoded_size, uint8_t *encoded_buffer)
    private val COMPRESS = lib.downcall(
        "BrotliEncoderCompress",
        FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG),
    )

    // size_t BrotliEncoderMaxCompressedSize(size_t input_size): 0 when it would overflow
    private val MAX_COMPRESSED_SIZE = lib.downcall("BrotliEncoderMaxCompressedSize", FunctionDescriptor.of(JAVA_LONG, JAVA_LONG))

    // BrotliEncoderState *BrotliEncoderCreateInstance(brotli_alloc_func, brotli_free_func, void *opaque)
    private val CREATE_INSTANCE = lib.downcall("BrotliEncoderCreateInstance", FunctionDescriptor.of(JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG))

    // BROTLI_BOOL BrotliEncoderSetParameter(BrotliEncoderState *, BrotliEncoderParameter, uint32_t value)
    private val SET_PARAMETER = lib.downcall("BrotliEncoderSetParameter", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_INT))

    // BROTLI_BOOL BrotliEncoderCompressStream(BrotliEncoderState *, BrotliEncoderOperation, size_t *available_in,
    //     const uint8_t **next_in, size_t *available_out, uint8_t **next_out, size_t *total_out)
    private val COMPRESS_STREAM = lib.downcall(
        "BrotliEncoderCompressStream",
        FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG),
    )

    // BROTLI_BOOL BrotliEncoderIsFinished(BrotliEncoderState *)
    private val IS_FINISHED = lib.downcall("BrotliEncoderIsFinished", FunctionDescriptor.of(JAVA_INT, JAVA_LONG))

    // void BrotliEncoderDestroyInstance(BrotliEncoderState *)
    private val DESTROY_INSTANCE = lib.downcall("BrotliEncoderDestroyInstance", FunctionDescriptor.ofVoid(JAVA_LONG))

    // uint32_t BrotliEncoderVersion(void): 0xMMMmmmppp
    private val VERSION = lib.downcall("BrotliEncoderVersion", FunctionDescriptor.of(JAVA_INT))

    const val MODE_GENERIC = 0
    const val MODE_TEXT = 1

    const val PARAM_MODE = 0
    const val PARAM_QUALITY = 1
    const val PARAM_LGWIN = 2
    const val PARAM_SIZE_HINT = 5

    const val OPERATION_FINISH = 2

    fun compress(quality: Int, lgwin: Int, mode: Int, inputSize: Long, input: Long, encodedSize: Long, encoded: Long): Int =
        COMPRESS.invokeExact(quality, lgwin, mode, inputSize, input, encodedSize, encoded) as Int

    fun maxCompressedSize(inputSize: Long): Long = MAX_COMPRESSED_SIZE.invokeExact(inputSize) as Long

    fun createInstance(alloc: Long, free: Long, opaque: Long): Long = CREATE_INSTANCE.invokeExact(alloc, free, opaque) as Long

    fun setParameter(state: Long, param: Int, value: Int): Int = SET_PARAMETER.invokeExact(state, param, value) as Int

    fun compressStream(state: Long, op: Int, availableIn: Long, nextIn: Long, availableOut: Long, nextOut: Long, totalOut: Long): Int =
        COMPRESS_STREAM.invokeExact(state, op, availableIn, nextIn, availableOut, nextOut, totalOut) as Int

    fun isFinished(state: Long): Int = IS_FINISHED.invokeExact(state) as Int

    fun destroyInstance(state: Long) {
        DESTROY_INSTANCE.invokeExact(state)
    }

    fun version(): String {
        val v = VERSION.invokeExact() as Int
        return "${v ushr 24}.${(v ushr 12) and 0xFFF}.${v and 0xFFF}"
    }
}

// brotli/decode.h: only the one-shot decoder, which the tests and the benchmark check every output with.
internal object BrotliDec {
    private val lib = library("libbrotlidec.so.1", "libbrotli1")

    // BrotliDecoderResult BrotliDecoderDecompress(size_t encoded_size, const uint8_t *encoded_buffer,
    //     size_t *decoded_size, uint8_t *decoded_buffer): 1 is BROTLI_DECODER_RESULT_SUCCESS
    private val DECOMPRESS = lib.downcall("BrotliDecoderDecompress", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG))

    /** The decoded stream, or null when it is not a whole brotli stream of at most [limit] bytes. */
    fun decompress(encoded: ByteArray, offset: Int, length: Int, limit: Int): ByteArray? {
        val src = Libc.malloc(maxOf(length, 1).toLong())
        val dst = Libc.malloc(limit + 1L)
        val size = Libc.malloc(8)
        try {
            MemorySegment.copy(encoded, offset, MEM, JAVA_BYTE, src, length)
            MEM.set(JAVA_LONG, size, limit + 1L)
            val rc = DECOMPRESS.invokeExact(length.toLong(), src, size, dst) as Int
            val n = MEM.get(JAVA_LONG, size)
            if (rc != 1 || n > limit) return null
            val out = ByteArray(n.toInt())
            MemorySegment.copy(MEM, JAVA_BYTE, dst, out, 0, n.toInt())
            return out
        } finally {
            Libc.free(src)
            Libc.free(dst)
            Libc.free(size)
        }
    }
}
