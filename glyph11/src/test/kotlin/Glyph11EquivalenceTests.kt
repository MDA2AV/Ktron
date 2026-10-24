package ktron.glyph11

import ktron.glyph11.parser.ParserLimits
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Every request the corpus holds (src/test/resources/vectors: this module's tests, Glyph11 upstream's C and C# tests,
 * HttpArena's requests, and edge cases for each check) parses to the same outcome through Glyph11.parse as through
 * the port's UltraHardenedParser and BodyFramingDetector: accepted or not, the status and message it is refused
 * with, and every field's bytes. Each is parsed whole, at an offset inside bytes that would complete or end it, and
 * cut at every length. The chunked bodies likewise through decodeChunked and the ChunkedBodyStream loop.
 */
class Glyph11EquivalenceTests {
    private val head = RequestHead() // one head for every parse: nothing may leak from one into the next
    private val into = ByteBuilder()

    private class Mismatches {
        val found = mutableListOf<String>()
        var checked = 0
        fun check(what: String, expected: Any, actual: Any) {
            checked++
            if (expected != actual && found.size < 20) found += "$what\n    expected $expected\n    actual   $actual"
        }
        fun assertNone() {
            if (found.isNotEmpty()) fail("${found.size}+ of $checked differ:\n" + found.joinToString("\n"))
        }
    }

    // what surrounds the request in its array: a parser that reads outside its range finds a head's end, or its
    // continuation, there
    private val surroundings = listOf(
        "" to "",
        "\r\n\r\n" to "\r\n\r\n",
        "GET / HTTP/1.1\r\n" to " HTTP/1.1\r\nHost: x\r\n\r\n",
        "\u0000\u0000\u0000" to "\u0000".repeat(16),
        "x" to "\r\nHost: y\r\n\r\n\r\n",
    ).map { (before, after) -> before.toByteArray(Charsets.ISO_8859_1) to after.toByteArray(Charsets.ISO_8859_1) }

    private fun compareParse(m: Mismatches, input: ByteArray, offset: Int, length: Int, limits: ParserLimits, label: String) {
        val expected = Outcomes.reference(input, offset, length, limits)
        val what = "$label [${Vectors.escape(input, offset, offset + length)}] limits ${Vectors.describe(limits)}"
        m.check("parse $what", expected, Outcomes.glyph11(input, offset, length, limits, head))
        m.check("exact $what", expected, Outcomes.exact(input, offset, length, limits, head))
    }

    @Test
    fun `every corpus request parses the same, alone and inside other bytes`() {
        val m = Mismatches()
        for (v in Vectors.requests) {
            for ((before, after) in surroundings) {
                val input = before + v.bytes + after
                compareParse(m, input, before.size, v.bytes.size, v.limits, v.source)
            }
        }
        m.assertNone()
        assertTrue(Vectors.requests.size > 400)
    }

    @Test
    fun `every prefix of every corpus request parses the same`() {
        val m = Mismatches()
        for (v in Vectors.requests) {
            // the bytes past the cut are the rest of the request, as in a buffer that has not all been filled yet
            for (length in 0..v.bytes.size) compareParse(m, v.bytes, 0, length, v.limits, "${v.source} cut at $length")
        }
        m.assertNone()
    }

    @Test
    fun `every corpus request parses the same under tight limits`() {
        val m = Mismatches()
        val tight = listOf(
            ParserLimits.Default.copy(maxHeaderCount = 1, maxQueryParameterCount = 1),
            ParserLimits.Default.copy(maxHeaderNameLength = 4, maxHeaderValueLength = 9, maxMethodLength = 3),
            ParserLimits.Default.copy(maxUrlLength = 8, maxTotalHeaderBytes = 64),
            ParserLimits.Default.copy(maxTotalHeaderBytes = 40),
            ParserLimits(0, 0, 0, 0, 0, 0, 0, 0),
        )
        for (v in Vectors.requests) for (limits in tight) compareParse(m, v.bytes, 0, v.bytes.size, limits, v.source)
        m.assertNone()
    }

    @Test
    fun `pipelined corpus requests parse the same one after another`() {
        val m = Mismatches()
        val accepted = Vectors.requests.filter {
            it.limits == ParserLimits.Default && Outcomes.reference(it.bytes, 0, it.bytes.size, it.limits).kind == Outcome.ACCEPTED
        }
        // each accepted head, cut to its length, followed by the next: parse from where the previous one ended
        val heads = accepted.map { r ->
            val n = Outcomes.reference(r.bytes, 0, r.bytes.size, r.limits).headLength
            r.bytes.copyOf(n)
        }
        val batch = heads.reduce { a, b -> a + b }
        var at = 0
        while (at < batch.size) {
            val expected = Outcomes.reference(batch, at, batch.size - at, ParserLimits.Default)
            val actual = Outcomes.glyph11(batch, at, batch.size - at, ParserLimits.Default, head)
            m.check("pipelined at $at", expected, actual)
            if (expected.kind != Outcome.ACCEPTED) break
            at += expected.headLength
        }
        m.assertNone()
        assertTrue(at == batch.size, "stopped at $at of ${batch.size}")
    }

    @Test
    fun `every corpus chunked body decodes the same, cut at every length`() {
        val m = Mismatches()
        for (body in Vectors.chunked) {
            for ((before, after) in surroundings) {
                val input = before + body + after
                m.check("decode [${Vectors.escape(body)}]", Outcomes.referenceChunked(input, before.size, body.size),
                    Outcomes.glyph11Chunked(input, before.size, body.size, into))
            }
            for (length in 0..body.size) {
                m.check("decode [${Vectors.escape(body, 0, length)}]", Outcomes.referenceChunked(body, 0, length),
                    Outcomes.glyph11Chunked(body, 0, length, into))
            }
        }
        m.assertNone()
        assertTrue(Vectors.chunked.size > 100)
    }

    @Test
    fun `the corpus covers acceptance, waiting and each status`() {
        val outcomes = Vectors.requests.map { Outcomes.reference(it.bytes, 0, it.bytes.size, it.limits) }
        val kinds = outcomes.groupingBy { if (it.kind == Outcome.REFUSED) "refused ${it.status}" else it.kind }.eachCount()
        for (k in listOf(Outcome.ACCEPTED, Outcome.INCOMPLETE, "refused 400", "refused 431", "refused 505")) {
            assertTrue((kinds[k] ?: 0) > 0, "no $k in the corpus: $kinds")
        }
        // every refusal message the parser has, but the unreachable ones
        val messages = outcomes.mapNotNull { it.message }.toSet()
        assertTrue(messages.size >= 33, "only ${messages.size} messages: $messages")
    }
}
