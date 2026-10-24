package ktron.compression.bench

import ktron.compression.BrotliDec
import ktron.compression.BrotliEnc
import ktron.compression.BrotliEncoder
import ktron.compression.ContentEncoder
import ktron.compression.DeflaterGzipEncoder
import ktron.compression.GzipEncoder
import ktron.glyph11.ByteBuilder
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/*
 * The json-comp microbenchmark: every codec and level on HttpArena's three bodies, one thread, the JVM pinned
 * to one CPU by the caller (taskset). Modes:
 *
 *   table     verify every output, then µs per encode (median of --runs runs) and bytes, ranked by the score
 *             proxy 1 / ((base + t_avg) * (avgBody + head)^2) for base 3 and 6 µs
 *   variants  the committed encoders against the designs in Variants.kt
 *   alloc     JVM heap bytes per encode
 *   leak      malloc'd bytes and RSS across --runs runs of many encodes, and of create/close cycles
 *   cold      per-call time with the caches flushed between calls, for the leading configurations
 *
 * --dataset PATH  dataset.json (default ../../HttpArena-edixoi-kt/data/dataset.json from ktron/)
 * --mode M, --filter PREFIX (cold: a comma list of configurations), --runs N, --run-ms MS, --warmup-ms MS,
 * --csv PATH, --dump DIR (writes the bodies), --trash-mb N (cold: memory walked between calls, default 64)
 */

class Config(val name: String, val make: () -> ContentEncoder)

private const val HEAD = 95.0
private const val LEAGUE_MIN_BPR = 1238.0

private fun configs(): List<Config> {
    val list = ArrayList<Config>()
    for (level in 1..12) list += Config("libdeflate-$level") { GzipEncoder(level) }
    for (level in 1..9) list += Config("zlib-$level") { DeflaterGzipEncoder(level) }
    for (q in 0..11) for (lgwin in intArrayOf(16, 18, 20, 22)) list += Config("br-$q-$lgwin") { BrotliEncoder(q, lgwin) }
    return list
}

private class Options(args: Array<String>) {
    var dataset: Path = Path.of("../../HttpArena-edixoi-kt/data/dataset.json")
    var mode = "table"
    var filter = ""
    var runs = 9
    var runMs = 80L
    var warmupMs = 150L
    var csv: Path? = null
    var dump: Path? = null
    var trashMb = 64

    init {
        var i = 0
        while (i < args.size) {
            val v = args.getOrNull(i + 1) ?: error("${args[i]} needs a value")
            when (args[i]) {
                "--dataset" -> dataset = Path.of(v)
                "--mode" -> mode = v
                "--filter" -> filter = v
                "--runs" -> runs = v.toInt()
                "--run-ms" -> runMs = v.toLong()
                "--warmup-ms" -> warmupMs = v.toLong()
                "--csv" -> csv = Path.of(v)
                "--dump" -> dump = Path.of(v)
                "--trash-mb" -> trashMb = v.toInt()
                else -> error("unknown option ${args[i]}")
            }
            i += 2
        }
    }
}

private var sink = 0L

fun main(args: Array<String>) {
    val o = Options(args)
    val bodies = Bodies.load(o.dataset)
    println("bodies: " + Bodies.shapes.zip(bodies).joinToString { (s, b) -> "/json/${s.first}?m=${s.second} ${b.size} B sha256 ${sha256(b)}" })
    println("brotli ${BrotliEnc.version()}, java ${System.getProperty("java.version")}, cpus ${Runtime.getRuntime().availableProcessors()}")
    o.dump?.let { dir ->
        Files.createDirectories(dir)
        Bodies.shapes.zip(bodies).forEach { (s, b) -> Files.write(dir.resolve("json-${s.first}-${s.second}.json"), b) }
    }
    when (o.mode) {
        "table" -> table(o, bodies)
        "variants" -> variants(o, bodies)
        "alloc" -> alloc(bodies)
        "leak" -> leak(o, bodies)
        "cold" -> cold(o, bodies)
        else -> error("unknown mode ${o.mode}")
    }
    if (sink == 42L) println()
}

private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }.substring(0, 16)

/** Decodes what [encoder] appended after a 7-byte prefix and compares it with [body]; the encoded size. */
fun verify(encoder: ContentEncoder, body: ByteArray): Int {
    val out = ByteBuilder()
    val prefix = "PREFIX:".toByteArray()
    out.append(prefix, 0, prefix.size)
    // An offset into a bigger array, to check the slice is what gets encoded.
    val framed = ByteArray(body.size + 10).also { body.copyInto(it, 3) }
    val n = encoder.encode(framed, 3, body.size, out)
    check(out.length == prefix.size + n) { "${encoder.name}: returned $n, appended ${out.length - prefix.size}" }
    check(out.array.copyOf(prefix.size).contentEquals(prefix)) { "${encoder.name}: overwrote what was already in the builder" }
    val decoded = when (encoder.name) {
        "gzip" -> GZIPInputStream(ByteArrayInputStream(out.array, prefix.size, n)).readAllBytes()
        "br" -> BrotliDec.decompress(out.array, prefix.size, n, body.size) ?: error("not a brotli stream of ${body.size} bytes")
        else -> error(encoder.name)
    }
    check(decoded.contentEquals(body)) { "${encoder.name}: decoded ${decoded.size} bytes differ from the ${body.size}-byte body" }
    return n
}

class Timing(val median: Double, val min: Double, val max: Double)

/** µs per encode of [body]: warmed for [warmupMs], then [runs] runs of about [runMs] each. */
fun time(encoder: ContentEncoder, body: ByteArray, runs: Int, runMs: Long, warmupMs: Long): Timing {
    val out = ByteBuilder(body.size * 2)
    var calls = 0L
    val start = System.nanoTime()
    val warmupNs = warmupMs * 1_000_000
    while (System.nanoTime() - start < warmupNs || calls < 3) {
        out.reset()
        sink += encoder.encode(body, 0, body.size, out)
        calls++
    }
    val perCall = (System.nanoTime() - start) / calls
    val iterations = maxOf(5L, runMs * 1_000_000 / maxOf(perCall, 1))
    val samples = DoubleArray(runs) {
        val t0 = System.nanoTime()
        for (i in 0 until iterations) {
            out.reset()
            sink += encoder.encode(body, 0, body.size, out)
        }
        (System.nanoTime() - t0) / 1000.0 / iterations
    }
    samples.sort()
    return Timing(samples[runs / 2], samples[0], samples[runs - 1])
}

class Row(val config: String, val us: DoubleArray, val spread: DoubleArray, val bytes: IntArray) {
    val tAvg = us.average()
    val bodyAvg = bytes.average()
    val bpr = bodyAvg + HEAD
    fun proxy(base: Double) = 1.0 / ((base + tAvg) * bpr * bpr)
}

private fun warmJit(bodies: List<ByteArray>) {
    // Every encode path compiled before the first measurement, on a mid level of each codec.
    for (e in listOf(GzipEncoder(6), DeflaterGzipEncoder(6), BrotliEncoder(5, 16), BrotliEncoder(1, 18))) {
        for (b in bodies) time(e, b, 1, 200, 300)
        (e as AutoCloseable).close()
    }
}

private fun table(o: Options, bodies: List<ByteArray>) {
    val selected = configs().filter { it.name.startsWith(o.filter) }
    // Correctness first: every configuration, every body, decoded and compared byte for byte.
    for (c in selected) {
        val e = c.make()
        for (b in bodies) verify(e, b)
        (e as AutoCloseable).close()
    }
    println("verified ${selected.size} configurations x ${bodies.size} bodies: every output decodes to its body")
    warmJit(bodies)
    val rows = ArrayList<Row>()
    for (c in selected) {
        val e = c.make()
        val bytes = IntArray(bodies.size) { verify(e, bodies[it]) }
        val timings = bodies.map { time(e, it, o.runs, o.runMs, o.warmupMs) }
        (e as AutoCloseable).close()
        val row = Row(c.name, timings.map { it.median }.toDoubleArray(), timings.map { (it.max - it.min) / it.median }.toDoubleArray(), bytes)
        rows += row
        System.err.printf("%-16s %8.2f %8.2f %8.2f us  %5d %5d %5d B%n", c.name, row.us[0], row.us[1], row.us[2], bytes[0], bytes[1], bytes[2])
    }
    printTable(rows)
    o.csv?.let { path ->
        val csv = StringBuilder("config,us25,us40,us50,spread25,spread40,spread50,b25,b40,b50,t_avg,body_avg,bpr,proxy3,proxy6\n")
        for (r in rows) {
            csv.append("${r.config},${r.us.joinToString(",") { "%.3f".format(it) }},${r.spread.joinToString(",") { "%.3f".format(it) }},")
            csv.append("${r.bytes.joinToString(",")},${"%.3f".format(r.tAvg)},${"%.1f".format(r.bodyAvg)},${"%.1f".format(r.bpr)},")
            csv.append("${r.proxy(3.0)},${r.proxy(6.0)}\n")
        }
        Files.writeString(path, csv)
    }
}

fun printTable(rows: List<Row>) {
    val best3 = rows.maxOf { it.proxy(3.0) }
    val best6 = rows.maxOf { it.proxy(6.0) }
    val ranked6 = rows.sortedByDescending { it.proxy(6.0) }.map { it.config }
    println()
    println("rank config            us/25   us/40   us/50  us/avg    B/25  B/40  B/50   bpr  @3us  @6us(rank)  league min?")
    rows.sortedByDescending { it.proxy(3.0) }.forEachIndexed { i, r ->
        val min = if (r.bpr < LEAGUE_MIN_BPR) "new min, others x%.3f".format((r.bpr / LEAGUE_MIN_BPR) * (r.bpr / LEAGUE_MIN_BPR)) else ""
        println(
            "%3d  %-16s %7.2f %7.2f %7.2f %7.2f   %5d %5d %5d %5.0f %5.3f %5.3f(%2d)   %s".format(
                i + 1, r.config, r.us[0], r.us[1], r.us[2], r.tAvg, r.bytes[0], r.bytes[1], r.bytes[2], r.bpr,
                r.proxy(3.0) / best3, r.proxy(6.0) / best6, ranked6.indexOf(r.config) + 1, min,
            ),
        )
    }
    val noisy = rows.filter { r -> r.spread.any { it > 0.05 } }.map { it.config }
    if (noisy.isNotEmpty()) println("runs spread over 5% (max-min)/median: $noisy")
}

private fun variants(o: Options, bodies: List<ByteArray>) {
    warmJit(bodies)
    val pairs = ArrayList<Config>()
    for (level in intArrayOf(1, 6, 9, 12)) {
        pairs += Config("libdeflate-$level native") { GzipEncoder(level) }
        pairs += Config("libdeflate-$level critical") { GzipCriticalEncoder(level) }
    }
    val bumps = HashMap<String, Bump>()
    for (q in 0..11) for (lgwin in intArrayOf(16, 22)) {
        pairs += Config("br-$q-$lgwin oneshot") { BrotliEncoder(q, lgwin) }
        pairs += Config("br-$q-$lgwin stream") { BrotliStreamEncoder(q, lgwin, false) }
        pairs += Config("br-$q-$lgwin stream+bump") { BrotliStreamEncoder(q, lgwin, true).also { e -> bumps["br-$q-$lgwin"] = e.allocator!! } }
    }
    println("variant                       us/25   us/40   us/50  us/avg  faults/call  allocs/stream")
    for (c in pairs.filter { it.name.startsWith(o.filter) }) {
        val e = c.make()
        for (b in bodies) verify(e, b)
        val us = bodies.map { time(e, it, o.runs, o.runMs, o.warmupMs).median }
        // Page faults per call on the middle body, after the timing has warmed everything.
        val out = ByteBuilder(bodies[1].size * 2)
        val calls = 200
        val f0 = NativeStats.minorFaults()
        val a0 = bumps[c.name.substringBefore(' ')]?.allocs ?: 0L
        repeat(calls) { out.reset(); sink += e.encode(bodies[1], 0, bodies[1].size, out) }
        val faults = (NativeStats.minorFaults() - f0).toDouble() / calls
        val allocs = if (c.name.endsWith("bump")) "%.1f".format(((bumps[c.name.substringBefore(' ')]?.allocs ?: 0L) - a0).toDouble() / calls) else ""
        (e as AutoCloseable).close()
        println("%-28s %7.2f %7.2f %7.2f %7.2f  %10.2f  %s".format(c.name, us[0], us[1], us[2], us.average(), faults, allocs))
    }
}

private fun encodeAll(e: ContentEncoder, bodies: Array<ByteArray>, calls: Int, out: ByteBuilder) {
    // Indexed, not for-in: an iterator the JIT does not scalarize would count against the encoder.
    var i = 0
    while (i < calls) {
        val b = bodies[i % bodies.size]
        out.reset()
        sink += e.encode(b, 0, b.size, out)
        i++
    }
}

private fun alloc(bodies: List<ByteArray>) {
    warmJit(bodies)
    val arr = bodies.toTypedArray()
    val encoders = listOf(
        "libdeflate-1" to GzipEncoder(1), "libdeflate-12" to GzipEncoder(12), "zlib-6" to DeflaterGzipEncoder(6),
        "br-0-16" to BrotliEncoder(0, 16), "br-5-16" to BrotliEncoder(5, 16), "br-11-22" to BrotliEncoder(11, 22),
    )
    val out = ByteBuilder(bodies[2].size * 2)
    val probe = NativeStats.allocatedBytes().let { NativeStats.allocatedBytes() - it } // what the reading itself costs
    for ((name, e) in encoders) {
        val calls = if (name.startsWith("br-11") || name == "libdeflate-12") 600 else 60_000
        encodeAll(e, arr, calls, out)
        val a0 = NativeStats.allocatedBytes()
        encodeAll(e, arr, calls, out)
        val a1 = NativeStats.allocatedBytes()
        println("%-14s %8.3f heap bytes per encode over %d encodes (%d bytes in all)".format(name, (a1 - a0 - probe).toDouble() / calls, calls, a1 - a0 - probe))
        (e as AutoCloseable).close()
    }
}

private fun leak(o: Options, bodies: List<ByteArray>) {
    warmJit(bodies)
    val arr = bodies.toTypedArray()
    val out = ByteBuilder(bodies[2].size * 2)
    fun inUse(): Long {
        System.gc()
        return NativeStats.mallocInUse()
    }
    // Equal runs after a warm one: a leak grows every run by the same bytes per op, noise does not.
    fun check(what: String, n: Int, block: (Int) -> Unit) {
        block(maxOf(n / 4, 1))
        var m = inUse()
        val r0 = NativeStats.rssBytes()
        val perOp = DoubleArray(o.runs) {
            block(n)
            val now = inUse()
            ((now - m).toDouble() / n).also { m = now }
        }
        val r1 = NativeStats.rssBytes()
        println("%-46s %s B/op, RSS %+6d KiB".format(what, perOp.joinToString(" ") { "%+9.2f".format(it) }, (r1 - r0) / 1024))
    }
    println("malloc in use (uordblks + hblkhd) per op, ${o.runs} runs of n ops each:")
    for ((name, make, n) in listOf(
        Triple("libdeflate-1", { GzipEncoder(1) as ContentEncoder }, 200_000),
        Triple("libdeflate-12", { GzipEncoder(12) as ContentEncoder }, 3_000),
        Triple("zlib-6", { DeflaterGzipEncoder(6) as ContentEncoder }, 100_000),
        Triple("br-0-16", { BrotliEncoder(0, 16) as ContentEncoder }, 200_000),
        Triple("br-5-16", { BrotliEncoder(5, 16) as ContentEncoder }, 50_000),
        Triple("br-9-22", { BrotliEncoder(9, 22) as ContentEncoder }, 1_500),
        Triple("br-11-22", { BrotliEncoder(11, 22) as ContentEncoder }, 300),
    ).filter { it.first.startsWith(o.filter) }) {
        val e = make()
        check("$name: n=$n encodes on one encoder", n) { encodeAll(e, arr, it, out) }
        (e as AutoCloseable).close()
        check("$name: n=$n create, encode, close", n) {
            repeat(it) { val c = make(); encodeAll(c, arr, 1, out); (c as AutoCloseable).close() }
        }
    }
}

private fun cold(o: Options, bodies: List<ByteArray>) {
    warmJit(bodies)
    val names = o.filter.split(',').filter { it.isNotEmpty() }
    val all = configs().associateBy { it.name }
    // A walk over --trash-mb between calls: 64 MiB is past the 36 MiB L3, so the encoder's tables start cold.
    val trash = ByteArray(o.trashMb shl 20)
    println("config            warm us/avg  cold us/avg  (per-call median, nanoTime around each encode, ${o.trashMb} MiB walked)")
    for (name in names) {
        val e = (all[name] ?: error("no configuration $name")).make()
        val out = ByteBuilder(bodies[2].size * 2)
        fun perCall(flush: Boolean): Double = bodies.map { b ->
            val samples = LongArray(301) {
                if (flush) { var s = 0; for (i in trash.indices step 64) { trash[i] = (trash[i] + 1).toByte(); s += trash[i] }; sink += s }
                out.reset()
                val t0 = System.nanoTime()
                sink += e.encode(b, 0, b.size, out)
                System.nanoTime() - t0
            }
            samples.sort()
            samples[samples.size / 2] / 1000.0
        }.average()
        perCall(false)
        val warm = perCall(false)
        val cold = perCall(true)
        println("%-16s %12.2f %12.2f".format(name, warm, cold))
        (e as AutoCloseable).close()
    }
}
