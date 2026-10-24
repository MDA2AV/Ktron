package ktron.glyph11.bench

import ktron.glyph11.ByteBuilder
import ktron.glyph11.Glyph11
import ktron.glyph11.HttpParseException
import ktron.glyph11.RequestHead
import ktron.glyph11.parser.ParserLimits
import java.lang.management.ManagementFactory
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/*
 * The parser benchmark: ns and bytes allocated per call, by request shape, for each implementation of the offset API.
 *
 *   Bench [--impls adapter,kotlin,native] [--shapes get,...] [--forks 3] [--runs 10] [--warmup-ms 3000] [--run-ms 250]
 *
 * Every measurement runs in a JVM of its own (a fork), so no shape or implementation shares a call site's profile with
 * another; the forks inherit this process's CPU affinity, run it under `taskset -c 14`. A fork warms up, then times
 * --runs runs and reports their median; the table shows the median of the forks' medians, and their spread.
 */

/** One implementation of the offset API, as the shapes call it. */
internal abstract class Parser {
    abstract fun parse(input: ByteArray, offset: Int, length: Int, head: RequestHead): Int
    abstract fun decodeChunked(input: ByteArray, offset: Int, length: Int, into: ByteBuilder): Int
}

internal class AdapterParser : Parser() {
    private val limits = ParserLimits.Default
    override fun parse(input: ByteArray, offset: Int, length: Int, head: RequestHead): Int =
        LegacyGlyph11.parse(input, offset, length, head, limits)
    override fun decodeChunked(input: ByteArray, offset: Int, length: Int, into: ByteBuilder): Int =
        LegacyGlyph11.decodeChunked(input, offset, length, into)
}

internal class KotlinParser : Parser() {
    private val limits = ParserLimits.Default
    override fun parse(input: ByteArray, offset: Int, length: Int, head: RequestHead): Int =
        Glyph11.parse(input, offset, length, head, limits)
    override fun decodeChunked(input: ByteArray, offset: Int, length: Int, into: ByteBuilder): Int =
        Glyph11.decodeChunked(input, offset, length, into)
}

internal object Requests {
    private fun resource(name: String): ByteArray =
        Requests::class.java.getResourceAsStream("/requests/$name")!!.use { it.readBytes() }

    val get: ByteArray = resource("get.raw")
    val postCl: ByteArray = resource("post_cl.raw")
    val postChunked: ByteArray = resource("post_chunked.raw")
    val pipeline: ByteArray = resource("pipeline.raw")

    /** A GET as a desktop browser sends it for a script: about 600 bytes, 12 headers. */
    val browser: ByteArray = (
        "GET /assets/js/app.min.js?v=20261024&lang=en HTTP/1.1\r\n" +
            "Host: www.example.com\r\n" +
            "Connection: keep-alive\r\n" +
            "sec-ch-ua: \"Chromium\";v=\"130\", \"Google Chrome\";v=\"130\", \"Not?A_Brand\";v=\"99\"\r\n" +
            "User-Agent: Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36\r\n" +
            "Accept: */*\r\n" +
            "Sec-Fetch-Site: same-origin\r\n" +
            "Sec-Fetch-Mode: no-cors\r\n" +
            "Sec-Fetch-Dest: script\r\n" +
            "Referer: https://www.example.com/dashboard/overview?tab=traffic\r\n" +
            "Accept-Encoding: gzip, deflate, br, zstd\r\n" +
            "Accept-Language: en-US,en;q=0.9,pt;q=0.8\r\n" +
            "Cookie: sid=8f14e45fceea167a5a36dedd4bea2543; theme=dark; consent=1\r\n" +
            "\r\n"
        ).toByteArray(Charsets.ISO_8859_1)

    /** post_cl.raw's head with a Transfer-Encoding too: refused only once its last header is read. */
    val refusedTeCl: ByteArray = (
        "POST /baseline11?a=13&b=42 HTTP/1.1\r\nHost: localhost:8080\r\nContent-Length: 2\r\n" +
            "Transfer-Encoding: chunked\r\n\r\n"
        ).toByteArray(Charsets.ISO_8859_1)

    /** post_cl.raw's head with a NUL in a last header's value: a refusal the line checks find, in their order. */
    val refusedValue: ByteArray = (
        "POST /baseline11?a=13&b=42 HTTP/1.1\r\nHost: localhost:8080\r\nContent-Length: 2\r\n" +
            "X-Trace: abc\u0000def\r\n\r\n"
        ).toByteArray(Charsets.ISO_8859_1)

    const val PIPELINE_DEPTH: Int = 16
    const val TRICKLE_CHUNKS: Int = 256
    const val TRICKLE_READS: Int = 64

    // A connection's receive buffer: the request at its start, room behind it, as Ktron's per-connection array has.
    const val BUFFER: Int = 16 * 1024

    fun inBuffer(request: ByteArray): ByteArray = request.copyInto(ByteArray(maxOf(BUFFER, request.size)))
}

/** A request shape: [run] performs [ops] operations and returns a checksum of their results. */
internal abstract class Shape(val parser: Parser) {
    val head = RequestHead()
    abstract fun run(ops: Int): Long
    abstract val describe: String
}

/** Parses one request head, again and again. */
internal class ParseShape(parser: Parser, private val request: ByteArray) : Shape(parser) {
    private val buffer = Requests.inBuffer(request)
    override fun run(ops: Int): Long {
        var sum = 0L
        val buffer = buffer
        val length = request.size
        val head = head
        val parser = parser
        for (i in 0 until ops) {
            sum += parser.parse(buffer, 0, length, head) + head.headerCount
        }
        return sum
    }
    override val describe: String get() = "${request.size} B"
}

/** Refuses [request], a head whose error shows only near its end: the whole head parsed, then a 400. */
internal class RefusedShape(parser: Parser, private val request: ByteArray) : Shape(parser) {
    private val buffer = Requests.inBuffer(request)
    override fun run(ops: Int): Long {
        var sum = 0L
        val buffer = buffer
        val length = request.size
        val head = head
        val parser = parser
        for (i in 0 until ops) {
            sum += try {
                parser.parse(buffer, 0, length, head)
            } catch (e: HttpParseException) {
                e.statusCode
            }
        }
        return sum
    }
    override val describe: String get() = "${request.size} B, 400"
}

/** Decodes the chunked body of post_chunked.raw. */
internal class ChunkedShape(parser: Parser) : Shape(parser) {
    private val request = Requests.postChunked
    private val buffer = Requests.inBuffer(request)
    private val bodyStart = Glyph11.parse(request, 0, request.size, RequestHead())
    private val into = ByteBuilder(64)
    override fun run(ops: Int): Long {
        var sum = 0L
        val buffer = buffer
        val start = bodyStart
        val length = request.size - start
        val into = into
        val parser = parser
        for (i in 0 until ops) {
            sum += parser.decodeChunked(buffer, start, length, into) + into.length
        }
        return sum
    }
    override val describe: String get() = "${request.size - bodyStart} B body"
}

/**
 * A chunked body that trickles in: TRICKLE_CHUNKS chunks of 32 bytes, delivered in TRICKLE_READS reads, decoded after
 * each read. One op is the whole body, every read. [resume] decodes with a ChunkedDecoder, else with decodeChunked.
 */
internal class TrickleShape(parser: Parser, private val resume: Boolean) : Shape(parser) {
    private val body = StringBuilder().apply {
        repeat(Requests.TRICKLE_CHUNKS) { i -> append("20\r\n").append(('a' + i % 26).toString().repeat(32)).append("\r\n") }
        append("0\r\n\r\n")
    }.toString().toByteArray(Charsets.ISO_8859_1)
    private val buffer = Requests.inBuffer(body)
    private val reads = IntArray(Requests.TRICKLE_READS) { (it + 1) * body.size / Requests.TRICKLE_READS }
    private val into = ByteBuilder(body.size)
    private val decoder = ktron.glyph11.ChunkedDecoder()

    override fun run(ops: Int): Long {
        var sum = 0L
        val buffer = buffer
        val into = into
        for (i in 0 until ops) {
            for (length in reads) {
                val n = if (resume) decoder.decode(buffer, 0, length, into) else parser.decodeChunked(buffer, 0, length, into)
                sum += n
            }
            sum += into.length
        }
        return sum
    }
    override val describe: String get() = "${body.size} B in ${Requests.TRICKLE_READS} reads" + if (resume) ", ChunkedDecoder" else ""
}

/** Parses PIPELINE_DEPTH pipelined requests back to back, as one read delivers them: one op is the whole batch. */
internal class PipelinedShape(parser: Parser) : Shape(parser) {
    private val batch = ByteArray(Requests.pipeline.size * Requests.PIPELINE_DEPTH).also { batch ->
        for (i in 0 until Requests.PIPELINE_DEPTH) Requests.pipeline.copyInto(batch, i * Requests.pipeline.size)
    }
    private val buffer = Requests.inBuffer(batch)
    override fun run(ops: Int): Long {
        var sum = 0L
        val buffer = buffer
        val end = batch.size
        val head = head
        val parser = parser
        for (i in 0 until ops) {
            var at = 0
            while (at < end) {
                val n = parser.parse(buffer, at, end - at, head)
                at += n
                sum += n + head.headerCount
            }
        }
        return sum
    }
    override val describe: String get() = "${Requests.PIPELINE_DEPTH} x ${Requests.pipeline.size} B"
}

internal val SHAPES = listOf(
    "get", "post_cl", "post_chunked", "chunked_body", "browser", "pipelined16", "refused_te_cl", "refused_value",
    "chunked_trickle", "chunked_trickle_resume",
)

/** ChunkedDecoder is the Kotlin parser's: only it runs the resuming shape. */
internal fun applies(impl: String, shape: String): Boolean = shape != "chunked_trickle_resume" || impl == "kotlin"
internal val IMPLS = listOf("adapter", "kotlin", "native")

internal fun parserFor(impl: String): Parser = when (impl) {
    "adapter" -> AdapterParser()
    "kotlin" -> KotlinParser()
    "native" -> NativeParser()
    else -> error("unknown implementation $impl")
}

internal fun shapeFor(shape: String, parser: Parser): Shape = when (shape) {
    "get" -> ParseShape(parser, Requests.get)
    "post_cl" -> ParseShape(parser, Requests.postCl)
    "post_chunked" -> ParseShape(parser, Requests.postChunked)
    "chunked_body" -> ChunkedShape(parser)
    "browser" -> ParseShape(parser, Requests.browser)
    "pipelined16" -> PipelinedShape(parser)
    "refused_te_cl" -> RefusedShape(parser, Requests.refusedTeCl)
    "refused_value" -> RefusedShape(parser, Requests.refusedValue)
    "chunked_trickle" -> TrickleShape(parser, resume = false)
    "chunked_trickle_resume" -> TrickleShape(parser, resume = true)
    else -> error("unknown shape $shape")
}

// ---- One measurement, in this JVM ----

private val threadBean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean

private fun child(impl: String, shapeName: String, runs: Int, warmupMs: Long, runMs: Long) {
    val shape = shapeFor(shapeName, parserFor(impl))
    var sink = 0L

    // warm up for warmupMs, timing the last batches to size a run at about runMs
    var ops = 1_000
    val warmupEnd = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(warmupMs)
    var nsPerOp = 0.0
    while (true) {
        val t0 = System.nanoTime()
        sink += shape.run(ops)
        val t1 = System.nanoTime()
        nsPerOp = (t1 - t0).toDouble() / ops
        if (t1 >= warmupEnd) break
        if (t1 - t0 < 20_000_000) ops *= 2
    }
    val opsPerRun = maxOf(1_000L, (TimeUnit.MILLISECONDS.toNanos(runMs) / nsPerOp).toLong()).toInt()

    val ns = DoubleArray(runs)
    val bytes = DoubleArray(runs)
    for (r in 0 until runs) {
        val a0 = threadBean.currentThreadAllocatedBytes
        val t0 = System.nanoTime()
        sink += shape.run(opsPerRun)
        val t1 = System.nanoTime()
        val a1 = threadBean.currentThreadAllocatedBytes
        ns[r] = (t1 - t0).toDouble() / opsPerRun
        bytes[r] = (a1 - a0).toDouble() / opsPerRun
    }
    ns.sort()
    bytes.sort()
    println("RESULT $impl $shapeName ${ns[runs / 2]} ${ns[0]} ${ns[runs - 1]} ${bytes[runs / 2]} $opsPerRun sink=$sink")
}

// ---- The driver: forks a JVM per measurement, collects the table ----

private class Result(val impl: String, val shape: String, val ns: Double, val bytes: Double)

private fun forkOne(impl: String, shape: String, runs: Int, warmupMs: Long, runMs: Long): Result? {
    val java = ProcessHandle.current().info().command().orElse("java")
    val command = mutableListOf(java)
    command += listOf("-Xms1g", "-Xmx1g", "-XX:+UseSerialGC", "-XX:+AlwaysPreTouch", "--enable-native-access=ALL-UNNAMED")
    System.getProperty("glyph11.lib")?.let { command += "-Dglyph11.lib=$it" }
    command += listOf("-cp", System.getProperty("java.class.path"), "ktron.glyph11.bench.BenchKt")
    command += listOf("--child", impl, shape, "$runs", "$warmupMs", "$runMs")
    val process = ProcessBuilder(command).redirectErrorStream(true).start()
    val out = process.inputStream.bufferedReader().readText()
    process.waitFor()
    val line = out.lines().firstOrNull { it.startsWith("RESULT ") }
    if (line == null) {
        System.err.println("$impl/$shape failed:\n$out")
        return null
    }
    val f = line.split(' ')
    return Result(impl, shape, f[3].toDouble(), f[6].toDouble())
}

private fun median(values: List<Double>): Double = values.sorted().let { it[it.size / 2] }

fun main(args: Array<String>) {
    if (args.isNotEmpty() && args[0] == "--child") {
        child(args[1], args[2], args[3].toInt(), args[4].toLong(), args[5].toLong())
        return
    }

    var impls = IMPLS
    var shapes = SHAPES
    var forks = 3
    var runs = 10
    var warmupMs = 3_000L
    var runMs = 250L
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--impls" -> impls = args[++i].split(',')
            "--shapes" -> shapes = args[++i].split(',')
            "--forks" -> forks = args[++i].toInt()
            "--runs" -> runs = args[++i].toInt()
            "--warmup-ms" -> warmupMs = args[++i].toLong()
            "--run-ms" -> runMs = args[++i].toLong()
            else -> { System.err.println("unknown argument ${args[i]}"); exitProcess(2) }
        }
        i++
    }
    if ("native" in impls && !NativeGlyph11.available) {
        System.err.println("no C core (-Dglyph11.lib=<libglyph11.so>), skipping native")
        impls = impls - "native"
    }

    checkAgreement(impls, shapes)

    val results = mutableListOf<Result>()
    for (fork in 1..forks) {
        for (shape in shapes) for (impl in impls) {
            if (!applies(impl, shape)) continue
            val r = forkOne(impl, shape, runs, warmupMs, runMs) ?: continue
            results += r
            System.err.println("fork $fork: $impl $shape ${"%.1f".format(r.ns)} ns ${"%.1f".format(r.bytes)} B")
        }
    }

    val affinity = runCatching {
        ProcessBuilder("taskset", "-cp", "${ProcessHandle.current().pid()}").start().inputStream.bufferedReader().readText().trim()
    }.getOrDefault("?")
    println()
    println("JVM ${System.getProperty("java.vm.version")}, $affinity, forks=$forks runs=$runs warmup=${warmupMs}ms run=${runMs}ms")
    println()
    println("| shape | " + impls.joinToString(" | ") { "$it ns/call" } + " | " + impls.joinToString(" | ") { "$it B/call" } + " |")
    println("|---|" + impls.joinToString("") { "---:|" } + impls.joinToString("") { "---:|" })
    for (shape in shapes) {
        val describe = shapeFor(shape, KotlinParser()).describe
        val cells = impls.map { impl ->
            val ns = results.filter { it.impl == impl && it.shape == shape }.map { it.ns }
            if (ns.isEmpty()) "-" else "%.1f (%.1f-%.1f)".format(median(ns), ns.min(), ns.max())
        }
        val alloc = impls.map { impl ->
            val b = results.filter { it.impl == impl && it.shape == shape }.map { it.bytes }
            if (b.isEmpty()) "-" else "%.1f".format(median(b))
        }
        println("| $shape ($describe) | " + cells.joinToString(" | ") + " | " + alloc.joinToString(" | ") + " |")
    }
}

/** Every implementation must agree on what each shape parses to before its speed means anything. */
private fun checkAgreement(impls: List<String>, shapes: List<String>) {
    for (shape in shapes) {
        val outcomes = impls.filter { applies(it, shape) }.map { impl ->
            val s = shapeFor(shape, parserFor(impl))
            val sum = s.run(3)
            "${s.head.headLength}/${s.head.headerCount}/${s.head.queryCount}/${s.head.bodyFraming}/${s.head.contentLength}/$sum"
        }
        require(outcomes.distinct().size == 1) { "implementations disagree on $shape: $outcomes" }
    }
}
