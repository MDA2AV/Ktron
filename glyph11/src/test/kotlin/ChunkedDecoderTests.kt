package ktron.glyph11

import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * ChunkedDecoder fed a body piece by piece answers each call as Glyph11.decodeChunked answers the same prefix, and
 * ends with the same payload: the corpus bodies and fuzzed ones, split at random places, moved between calls.
 */
class ChunkedDecoderTests {
    private fun outcome(call: () -> Int, into: ByteBuilder): ChunkOutcome = try {
        val n = call()
        if (n == 0) ChunkOutcome(Outcome.INCOMPLETE)
        else ChunkOutcome(Outcome.ACCEPTED, n, String(into.array, 0, into.length, Charsets.ISO_8859_1))
    } catch (e: HttpParseException) {
        ChunkOutcome(Outcome.REFUSED, status = e.statusCode, message = e.message)
    }

    /** Feeds [body] in pieces cut at [cuts]; null when every call agrees, else what differs. */
    private fun trickle(body: ByteArray, cuts: IntArray, rnd: Random): String? {
        val decoder = ChunkedDecoder()
        val into = ByteBuilder()
        val reference = ByteBuilder()
        for (length in cuts) {
            // the bytes may move between calls, as when a connection's buffer is compacted
            val before = rnd.nextInt(4)
            val input = ByteArray(before) { 'z'.code.toByte() } + body.copyOf(length) + "\r\n0\r\n\r\n".toByteArray()
            val expected = outcome({ Glyph11.decodeChunked(input, before, length, reference) }, reference)
            val actual = outcome({ decoder.decode(input, before, length, into) }, into)
            if (expected != actual) return "[${Vectors.escape(body)}] cut at $length: expected $expected, got $actual"
            if (expected.kind != Outcome.INCOMPLETE) return null
        }
        return null
    }

    private fun cutsOf(size: Int, rnd: Random): IntArray {
        val cuts = (0..size).filter { it == size || rnd.nextInt(4) == 0 }
        return cuts.toIntArray()
    }

    @Test
    fun `corpus bodies trickled in decode as decodeChunked decodes them whole`() {
        val rnd = Random(11)
        val failures = mutableListOf<String>()
        for (body in Vectors.chunked) {
            // byte by byte, and in random pieces
            trickle(body, IntArray(body.size + 1) { it }, rnd)?.let { failures += it }
            repeat(4) { trickle(body, cutsOf(body.size, rnd), rnd)?.let { failures += it } }
        }
        if (failures.isNotEmpty()) fail(failures.take(10).joinToString("\n"))
    }

    @Test
    fun `fuzzed bodies trickled in decode as decodeChunked decodes them whole`() {
        val rnd = Random(12)
        val fuzz = ChunkedFuzz(Random(13))
        val failures = mutableListOf<String>()
        repeat(30_000) {
            val case = fuzz.next()
            val body = case.input.copyOfRange(case.offset, case.offset + case.length)
            trickle(body, cutsOf(body.size, rnd), rnd)?.let { if (failures.size < 10) failures += it }
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    @Test
    fun `a decoder starts over after a body and after reset`() {
        val decoder = ChunkedDecoder()
        val into = ByteBuilder()
        val first = "5\r\nhello\r\n3\r\nabc\r\n0\r\n\r\n".toByteArray()
        assertEquals(0, decoder.decode(first, 0, 12, into))
        assertEquals(first.size, decoder.decode(first, 0, first.size, into))
        assertEquals("helloabc", String(into.array, 0, into.length))

        val second = "2\r\nhi\r\n0\r\n\r\n".toByteArray()
        assertEquals(second.size, decoder.decode(second, 0, second.size, into))
        assertEquals("hi", String(into.array, 0, into.length))

        // abandoned halfway: reset forgets it
        assertEquals(0, decoder.decode(first, 0, 12, into))
        decoder.reset(into)
        assertEquals(second.size, decoder.decode(second, 0, second.size, into))
        assertEquals("hi", String(into.array, 0, into.length))
    }

    @Test
    fun `a body of many small chunks trickled in byte by byte decodes`() {
        val body = StringBuilder()
        repeat(5_000) { body.append("1\r\n").append('a' + it % 26).append("\r\n") }
        body.append("0\r\n\r\n")
        val bytes = body.toString().toByteArray()
        val decoder = ChunkedDecoder()
        val into = ByteBuilder()
        var n = 0
        for (length in 1..bytes.size) {
            n = decoder.decode(bytes, 0, length, into)
            if (n != 0) {
                assertEquals(bytes.size, length)
                break
            }
        }
        assertEquals(bytes.size, n)
        assertEquals(5_000, into.length)
        assertTrue((0 until 5_000).all { into.array[it] == ('a' + it % 26).code.toByte() })
    }
}
