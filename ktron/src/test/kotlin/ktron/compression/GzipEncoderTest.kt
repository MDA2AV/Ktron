package ktron.compression

import ktron.glyph11.ByteBuilder
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GzipEncoderTest {
    private val bodies = listOf(json(25), json(40), json(50))

    @Test
    fun `every level round-trips through GZIPInputStream`() {
        for (level in 0..12) GzipEncoder(level).use { e ->
            assertEquals("gzip", e.name)
            for (b in bodies) {
                val gz = roundTrip(e, b)
                assertEquals(0x1f, gz[0].toInt() and 0xff)
                assertEquals(0x8b, gz[1].toInt() and 0xff)
                assertEquals(8, gz[2].toInt(), "CM is deflate")
                if (level > 0) assertTrue(gz.size < b.size / 3, "level $level: ${gz.size} of ${b.size}")
            }
        }
    }

    @Test
    fun `an instance gives the same bytes call after call`() {
        GzipEncoder(6).use { e ->
            val first = roundTrip(e, bodies[2])
            roundTrip(e, bodies[0])
            assertContentEquals(first, roundTrip(e, bodies[2]))
        }
    }

    @Test
    fun `empty, incompressible and growing inputs`() {
        GzipEncoder(1).use { e ->
            roundTrip(e, ByteArray(0))
            roundTrip(e, ByteArray(1) { 'x'.code.toByte() })
            val noise = random(100_000)
            val gz = roundTrip(e, noise)
            assertTrue(gz.size < noise.size + 100, "stored blocks cost little: ${gz.size}")
            // Past the first reservation, then far past it, then small again on the grown buffers.
            roundTrip(e, json(200))
            roundTrip(e, random(3 shl 20))
            roundTrip(e, bodies[0])
        }
    }

    @Test
    fun `a closed encoder refuses, and closing twice is fine`() {
        val e = GzipEncoder()
        roundTrip(e, bodies[0])
        e.close()
        e.close()
        assertFailsWith<IllegalStateException> { e.encode(bodies[0], 0, bodies[0].size, ByteBuilder()) }
    }

    @Test
    fun `bad levels and bad slices are refused`() {
        assertFailsWith<IllegalArgumentException> { GzipEncoder(13) }
        assertFailsWith<IllegalArgumentException> { GzipEncoder(-1) }
        GzipEncoder().use { e ->
            assertFailsWith<IndexOutOfBoundsException> { e.encode(ByteArray(10), 5, 6, ByteBuilder()) }
            assertFailsWith<IndexOutOfBoundsException> { e.encode(ByteArray(10), -1, 2, ByteBuilder()) }
        }
    }

    @Test
    fun `the zlib fallback round-trips at every level`() {
        for (level in 0..9) DeflaterGzipEncoder(level).use { e ->
            for (b in bodies) roundTrip(e, b)
            roundTrip(e, ByteArray(0))
            roundTrip(e, random(70_000))
        }
        assertFailsWith<IllegalArgumentException> { DeflaterGzipEncoder(10) }
    }
}
