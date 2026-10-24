package ktron.glyph11.bench

import ktron.glyph11.ByteBuilder
import ktron.glyph11.ChunkOutcome
import ktron.glyph11.ChunkedFuzz
import ktron.glyph11.FuzzCase
import ktron.glyph11.Glyph11FuzzTests
import ktron.glyph11.HttpParseException
import ktron.glyph11.Outcome
import ktron.glyph11.Outcomes
import ktron.glyph11.RequestFuzz
import ktron.glyph11.RequestHead
import ktron.glyph11.Vectors
import ktron.glyph11.parser.ParserLimits
import java.util.Random
import kotlin.system.exitProcess

/*
 * Differential runs over the tests' corpus and fuzz generators.
 *
 *   Differential kotlin <iterations> <seed>   the fuzz tests at any length: the old parser against Glyph11.parse
 *   Differential native <iterations> <seed>   Glyph11.parse against the C core over FFM: every corpus request whose
 *                                             verdict (accepted, waiting, refused and its status) or fields differ,
 *                                             then the fuzz cases' differences, grouped
 */
fun main(args: Array<String>) {
    val mode = args.getOrElse(0) { "native" }
    val iterations = args.getOrElse(1) { "1000000" }.toInt()
    val seed = args.getOrElse(2) { "1" }.toLong()
    when (mode) {
        "kotlin" -> {
            System.setProperty("glyph11.fuzz.iterations", "$iterations")
            System.setProperty("glyph11.fuzz.seed", "$seed")
            val tests = Glyph11FuzzTests()
            tests.`random and mutated requests parse the same`()
            tests.`random and mutated chunked bodies decode the same`()
            println("no differences")
        }
        "native" -> native(iterations, seed)
        else -> {
            System.err.println("usage: Differential kotlin|native <iterations> <seed>")
            exitProcess(2)
        }
    }
}

/** A verdict without the message, which the C core words differently. */
private fun verdict(o: Outcome): String = if (o.kind == Outcome.REFUSED) "refused ${o.status}" else o.kind

private class Parsers {
    val head = RequestHead()
    private val natives = HashMap<ParserLimits, NativeParser>()

    fun kotlin(input: ByteArray, offset: Int, length: Int, limits: ParserLimits): Outcome =
        Outcomes.glyph11(input, offset, length, limits, head)

    fun native(input: ByteArray, offset: Int, length: Int, limits: ParserLimits): Outcome {
        val parser = natives.getOrPut(limits) { NativeParser(limits) }
        return Outcomes.outcome(input, head) { parser.parse(input, offset, length, head) }
    }
}

/** How two outcomes differ: their verdicts, or the first field that does. */
private fun difference(kotlin: Outcome, native: Outcome): String? {
    if (verdict(kotlin) != verdict(native)) return "${verdict(kotlin)} / C ${verdict(native)}"
    if (kotlin.kind != Outcome.ACCEPTED) return null
    return when {
        kotlin.headLength != native.headLength -> "head length"
        kotlin.method != native.method || kotlin.path != native.path || kotlin.version != native.version -> "request line"
        kotlin.query != native.query -> "query"
        kotlin.headers != native.headers -> "headers"
        kotlin.params != native.params -> "query parameters"
        kotlin.framing != native.framing || kotlin.contentLength != native.contentLength -> "framing"
        else -> null
    }
}

private fun native(iterations: Int, seed: Long) {
    if (!NativeGlyph11.available) {
        System.err.println("no C core: -Dglyph11.lib=<libglyph11.so>")
        exitProcess(2)
    }
    val p = Parsers()

    println("## Corpus: every request whose verdict or fields differ (Kotlin / C)")
    println()
    var corpusDiffs = 0
    for (v in Vectors.requests) {
        val k = p.kotlin(v.bytes, 0, v.bytes.size, v.limits)
        val c = p.native(v.bytes, 0, v.bytes.size, v.limits)
        val d = difference(k, c) ?: continue
        corpusDiffs++
        val limits = Vectors.describe(v.limits).let { if (it == "-") "" else " limits $it" }
        println("- ${v.source}$limits `${Vectors.escape(v.bytes).take(140)}`: $d" + (if (k.kind == Outcome.REFUSED) " (\"${k.message}\")" else ""))
    }
    println()
    println("$corpusDiffs of ${Vectors.requests.size} corpus requests differ")
    println()

    // the fuzz cases, grouped by how they differ, with the shortest example of each
    val fuzz = RequestFuzz(Random(seed))
    val groups = HashMap<String, MutableList<FuzzCase>>()
    val kotlinMessages = HashMap<String, MutableSet<String>>()
    var differing = 0
    for (i in 0 until iterations) {
        val case = fuzz.next()
        val k = p.kotlin(case.input, case.offset, case.length, case.limits)
        val c = p.native(case.input, case.offset, case.length, case.limits)
        val d = difference(k, c) ?: continue
        differing++
        groups.getOrPut(d) { ArrayList() }.add(case)
        k.message?.let { kotlinMessages.getOrPut(d) { HashSet() }.add(it) }
    }
    println("## Fuzz: $differing of $iterations cases differ (seed $seed), by difference")
    println()
    for ((d, cases) in groups.entries.sortedByDescending { it.value.size }) {
        val shortest = cases.minBy { it.length }
        val limits = Vectors.describe(shortest.limits).let { if (it == "-") "" else " limits $it" }
        println("- $d: ${cases.size} cases, e.g.$limits `${Vectors.escape(shortest.input, shortest.offset, shortest.offset + shortest.length).take(160)}`")
        kotlinMessages[d]?.let { println("  Kotlin refuses with: " + it.sorted().joinToString("; ")) }
    }
    println()

    // what the binding adds: the core itself waits on a head that cannot end within the limit
    val limits = ParserLimits.Default.copy(maxTotalHeaderBytes = 64)
    val unterminated = ("GET / HTTP/1.1\r\nHost: x\r\nX-Pad: " + "a".repeat(200)).toByteArray(Charsets.ISO_8859_1)
    val raw = NativeParser(limits).status(unterminated, 0, unterminated.size)
    println("## Raw core on ${unterminated.size} unterminated bytes, max_total_header_bytes 64: status $raw " +
        "(1 = INCOMPLETE; Glyph11.parse refuses it with 431, and so does NativeParser)")
    println()

    chunked(iterations / 2, seed)
}

/** The core's one-shot chunked decode against Glyph11.decodeChunked: the corpus, then fuzzed bodies. */
private fun chunked(iterations: Int, seed: Long) {
    val parser = NativeParser()
    val into = ByteBuilder()
    fun native(input: ByteArray, offset: Int, length: Int): ChunkOutcome = try {
        val n = parser.decodeChunked(input, offset, length, into)
        if (n == 0) ChunkOutcome(Outcome.INCOMPLETE)
        else ChunkOutcome(Outcome.ACCEPTED, n, String(into.array, 0, into.length, Charsets.ISO_8859_1))
    } catch (e: HttpParseException) {
        ChunkOutcome(Outcome.REFUSED, status = e.statusCode)
    }
    fun verdict(o: ChunkOutcome) = if (o.kind == Outcome.ACCEPTED) "decoded" else o.kind
    fun difference(k: ChunkOutcome, c: ChunkOutcome): String? = when {
        verdict(k) != verdict(c) -> "${verdict(k)} / C ${verdict(c)}"
        k.kind == Outcome.ACCEPTED && (k.wire != c.wire || k.payload != c.payload) -> "decoded differently"
        else -> null
    }

    println("## Chunked corpus: every body whose verdict or payload differs (Kotlin / C)")
    println()
    var corpusDiffs = 0
    for (body in Vectors.chunked) {
        val k = Outcomes.glyph11Chunked(body, 0, body.size, ByteBuilder())
        val d = difference(k, native(body, 0, body.size)) ?: continue
        corpusDiffs++
        println("- `${Vectors.escape(body).take(120)}`: $d" + (if (k.kind == Outcome.REFUSED) " (\"${k.message}\")" else ""))
    }
    println()
    println("$corpusDiffs of ${Vectors.chunked.size} corpus bodies differ")
    println()

    val fuzz = ChunkedFuzz(Random(seed + 1))
    val groups = HashMap<String, MutableList<FuzzCase>>()
    val messages = HashMap<String, MutableSet<String>>()
    var differing = 0
    for (i in 0 until iterations) {
        val case = fuzz.next()
        val k = Outcomes.glyph11Chunked(case.input, case.offset, case.length, ByteBuilder())
        val d = difference(k, native(case.input, case.offset, case.length)) ?: continue
        differing++
        groups.getOrPut(d) { ArrayList() }.add(case)
        k.message?.let { messages.getOrPut(d) { HashSet() }.add(it) }
    }
    println("## Chunked fuzz: $differing of $iterations bodies differ (seed ${seed + 1}), by difference")
    println()
    for ((d, cases) in groups.entries.sortedByDescending { it.value.size }) {
        val shortest = cases.minBy { it.length }
        println("- $d: ${cases.size} bodies, e.g. `${Vectors.escape(shortest.input, shortest.offset, shortest.offset + shortest.length).take(160)}`")
        messages[d]?.let { println("  Kotlin refuses with: " + it.sorted().joinToString("; ")) }
    }
}
