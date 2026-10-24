package ktron.compression

import ktron.glyph11.ByteBuilder
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BrotliEncoderTest {
    private val bodies = listOf(json(25), json(40), json(50))

    @Test
    fun `every quality and window round-trips through libbrotlidec`() {
        for (quality in 0..11) for (lgwin in intArrayOf(10, 16, 18, 20, 22, 24)) BrotliEncoder(quality, lgwin).use { e ->
            assertEquals("br", e.name)
            for (b in bodies) {
                val br = roundTrip(e, b)
                assertTrue(br.size < b.size / 2, "q$quality lgwin $lgwin: ${br.size} of ${b.size}")
            }
        }
    }

    @Test
    fun `an instance gives the same bytes call after call`() {
        BrotliEncoder(5, 16).use { e ->
            val first = roundTrip(e, bodies[2])
            roundTrip(e, bodies[0])
            assertContentEquals(first, roundTrip(e, bodies[2]))
        }
    }

    @Test
    fun `empty, incompressible and growing inputs`() {
        for (quality in intArrayOf(0, 1, 5, 9)) BrotliEncoder(quality, 22).use { e ->
            roundTrip(e, ByteArray(0))
            roundTrip(e, ByteArray(1) { 'x'.code.toByte() })
            val noise = random(100_000)
            val br = roundTrip(e, noise)
            assertTrue(br.size <= BrotliEnc.maxCompressedSize(noise.size.toLong()), "q$quality: ${br.size}")
            roundTrip(e, json(200))
            roundTrip(e, random(3 shl 20))
            roundTrip(e, bodies[0])
        }
    }

    @Test
    fun `a closed encoder refuses, and closing twice is fine`() {
        val e = BrotliEncoder()
        roundTrip(e, bodies[0])
        e.close()
        e.close()
        assertFailsWith<IllegalStateException> { e.encode(bodies[0], 0, bodies[0].size, ByteBuilder()) }
    }

    @Test
    fun `bad parameters and bad slices are refused`() {
        assertFailsWith<IllegalArgumentException> { BrotliEncoder(12, 16) }
        assertFailsWith<IllegalArgumentException> { BrotliEncoder(-1, 16) }
        assertFailsWith<IllegalArgumentException> { BrotliEncoder(5, 9) }
        assertFailsWith<IllegalArgumentException> { BrotliEncoder(5, 25) }
        BrotliEncoder().use { e ->
            assertFailsWith<IndexOutOfBoundsException> { e.encode(ByteArray(10), 5, 6, ByteBuilder()) }
        }
    }
}
