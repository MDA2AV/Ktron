package ktron.compression

import ktron.glyph11.ByteBuilder
import java.util.Objects
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * gzip on java.util.zip's Deflater (the JDK's zlib), [level] 0-9: no native library to install, so a fallback
 * where libdeflate is missing, and the baseline [GzipEncoder] is measured against. Raw deflate between a
 * 10-byte header (no name, no mtime) and the CRC-32 and length trailer, as RFC 1952 frames it.
 *
 * The default, 3, is zlib's best json-comp setting; libdeflate's level 1 is both faster and smaller.
 */
class DeflaterGzipEncoder(val level: Int = 3) : ContentEncoder, AutoCloseable {
    override val name: String get() = "gzip"

    init {
        require(level in 0..9) { "zlib's levels are 0-9, not $level" }
    }

    private val deflater = Deflater(level, true)
    private val crc = CRC32()
    private var scratch = ByteArray(16 * 1024)
    private var open = true

    override fun encode(input: ByteArray, offset: Int, length: Int, output: ByteBuilder): Int {
        Objects.checkFromIndexSize(offset, length, input.size)
        check(open) { "closed" }
        // zlib's deflateBound for raw deflate, plus the gzip framing: one deflate call fits whatever the input.
        val bound = 10 + length + (length shr 12) + (length shr 14) + (length shr 25) + 13 + 8
        if (scratch.size < bound) scratch = ByteArray(maxOf(bound, scratch.size * 2))
        var out = scratch
        out[0] = 0x1f
        out[1] = 0x8b.toByte()
        out[2] = 8 // CM: deflate
        out[3] = 0 // FLG: no name, comment or extra
        out[4] = 0; out[5] = 0; out[6] = 0; out[7] = 0 // MTIME: none
        out[8] = if (level == 9) 2 else if (level == 1) 4 else 0 // XFL: slowest, fastest
        out[9] = 0xff.toByte() // OS: unknown
        deflater.reset()
        deflater.setInput(input, offset, length)
        deflater.finish()
        var n = 10
        while (!deflater.finished()) {
            if (n == out.size - 8) {
                out = out.copyOf(out.size * 2)
                scratch = out
            }
            n += deflater.deflate(out, n, out.size - 8 - n)
        }
        crc.reset()
        crc.update(input, offset, length)
        putIntLe(out, n, crc.value.toInt())
        putIntLe(out, n + 4, length)
        n += 8
        output.append(out, 0, n)
        return n
    }

    private fun putIntLe(out: ByteArray, at: Int, v: Int) {
        out[at] = v.toByte()
        out[at + 1] = (v ushr 8).toByte()
        out[at + 2] = (v ushr 16).toByte()
        out[at + 3] = (v ushr 24).toByte()
    }

    override fun close() {
        open = false
        deflater.end()
    }
}
