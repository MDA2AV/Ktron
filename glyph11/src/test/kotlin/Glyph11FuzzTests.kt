package ktron.glyph11

import ktron.glyph11.parser.ParserLimits
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Seeded random requests and chunked bodies, compared between the old parser and the offset API as the corpus is
 * (see [Glyph11EquivalenceTests]). The generator writes mostly valid requests, with every feature the checks look at,
 * then mutates some at the byte level; the limits are tight in a quarter of the cases, and the input sits at a random
 * offset among other bytes in half of them.
 *
 * -Dglyph11.fuzz.iterations=N (default 200000 requests, 100000 bodies) and -Dglyph11.fuzz.seed=S vary a run.
 */
class Glyph11FuzzTests {
    private val iterations = System.getProperty("glyph11.fuzz.iterations")?.toInt() ?: 200_000
    private val seed = System.getProperty("glyph11.fuzz.seed")?.toLong() ?: 0x6c79_7068_3131L

    @Test
    fun `random and mutated requests parse the same`() {
        val fuzz = RequestFuzz(Random(seed))
        val head = RequestHead()
        val failures = mutableListOf<String>()
        val kinds = HashMap<String, Int>()
        for (i in 0 until iterations) {
            val case = fuzz.next()
            val expected = Outcomes.reference(case.input, case.offset, case.length, case.limits)
            kinds.merge(if (expected.kind == Outcome.REFUSED) "refused ${expected.status}" else expected.kind, 1, Int::plus)
            val actual = Outcomes.glyph11(case.input, case.offset, case.length, case.limits, head)
            val exact = Outcomes.exact(case.input, case.offset, case.length, case.limits, head)
            if ((expected != actual || expected != exact) && failures.size < 10) {
                failures += "#$i [${Vectors.escape(case.input, case.offset, case.offset + case.length)}] at ${case.offset} " +
                    "limits ${Vectors.describe(case.limits)}\n    expected $expected\n    parse    $actual\n    exact    $exact"
            }
        }
        println("requests, seed $seed, $iterations iterations: $kinds")
        if (failures.isNotEmpty()) fail("seed $seed:\n" + failures.joinToString("\n"))
        // the generator must keep reaching every kind of outcome, or the comparison means little
        for (k in listOf(Outcome.ACCEPTED, Outcome.INCOMPLETE, "refused 400", "refused 431", "refused 505")) {
            assertTrue((kinds[k] ?: 0) > iterations / 200, "too few $k: $kinds")
        }
        assertTrue((kinds[Outcome.ACCEPTED] ?: 0) > iterations / 5, "too few accepted: $kinds")
    }

    @Test
    fun `random and mutated chunked bodies decode the same`() {
        val fuzz = ChunkedFuzz(Random(seed + 1))
        val into = ByteBuilder()
        val failures = mutableListOf<String>()
        val kinds = HashMap<String, Int>()
        for (i in 0 until iterations / 2) {
            val case = fuzz.next()
            val expected = Outcomes.referenceChunked(case.input, case.offset, case.length)
            kinds.merge(expected.kind, 1, Int::plus)
            val actual = Outcomes.glyph11Chunked(case.input, case.offset, case.length, into)
            if (expected != actual && failures.size < 10) {
                failures += "#$i [${Vectors.escape(case.input, case.offset, case.offset + case.length)}] at ${case.offset}\n" +
                    "    expected $expected\n    actual   $actual"
            }
        }
        println("chunked bodies, seed ${seed + 1}, ${iterations / 2} iterations: $kinds")
        if (failures.isNotEmpty()) fail("seed ${seed + 1}:\n" + failures.joinToString("\n"))
        for (k in listOf(Outcome.ACCEPTED, Outcome.INCOMPLETE, Outcome.REFUSED)) {
            assertTrue((kinds[k] ?: 0) > iterations / 100, "too few $k: $kinds")
        }
    }
}

/** One generated input: [length] bytes at [offset] of [input], parsed under [limits]. */
class FuzzCase(val input: ByteArray, val offset: Int, val length: Int, val limits: ParserLimits)

/** The byte-level mutations and the placement both generators share. */
abstract class Fuzz(protected val rnd: Random) {
    protected fun chance(p: Double): Boolean = rnd.nextDouble() < p
    protected fun <T> pick(items: List<T>): T = items[rnd.nextInt(items.size)]
    protected fun pick(chars: String): Char = chars[rnd.nextInt(chars.length)]
    protected fun string(chars: String, max: Int, min: Int = 0): String {
        val n = min + rnd.nextInt(max - min + 1)
        return buildString { repeat(n) { append(pick(chars)) } }
    }

    private val interestingBytes = "\r\n \t:?&=/.%#\\025x,;@*-H".map { it.code } + listOf(0x00, 0x7F, 0x80, 0xFF, 0x0B, 0x1F)
    protected abstract val interestingTokens: List<String>

    protected fun latin1(s: String): ByteArray = s.toByteArray(Charsets.ISO_8859_1)

    fun mutate(original: ByteArray): ByteArray {
        var b = original
        repeat(1 + rnd.nextInt(3)) {
            if (b.isEmpty()) return latin1(pick(interestingTokens))
            val at = rnd.nextInt(b.size)
            b = when (rnd.nextInt(8)) {
                0 -> b.copyOf().also { it[at] = rnd.nextInt(256).toByte() }
                1 -> b.copyOf().also { it[at] = pick(interestingBytes).toByte() }
                2 -> b.copyOfRange(0, at) + latin1(pick(interestingTokens)) + b.copyOfRange(at, b.size)
                3 -> b.copyOfRange(0, at) + b.copyOfRange(minOf(b.size, at + 1 + rnd.nextInt(4)), b.size)
                4 -> {
                    val end = minOf(b.size, at + 1 + rnd.nextInt(16))
                    b.copyOfRange(0, end) + b.copyOfRange(at, end) + b.copyOfRange(end, b.size)
                }
                5 -> b.copyOf(rnd.nextInt(b.size + 1))
                6 -> b.copyOf().also { val j = rnd.nextInt(b.size); val t = it[at]; it[at] = it[j]; it[j] = t }
                else -> b.copyOfRange(0, at) + byteArrayOf(pick(interestingBytes).toByte()) + b.copyOfRange(at, b.size)
            }
        }
        return b
    }

    /** [bytes] alone, or at a random offset with other bytes around them that would complete or end them. */
    protected fun place(bytes: ByteArray, limits: ParserLimits): FuzzCase {
        if (chance(0.5)) return FuzzCase(bytes, 0, bytes.size, limits)
        val before = surrounding(rnd.nextInt(24))
        val after = surrounding(rnd.nextInt(40))
        return FuzzCase(before + bytes + after, before.size, bytes.size, limits)
    }

    private fun surrounding(n: Int): ByteArray = when (rnd.nextInt(4)) {
        0 -> ByteArray(n)
        1 -> ByteArray(n) { rnd.nextInt(256).toByte() }
        2 -> latin1("\r\n\r\n".repeat(n / 4 + 1).substring(0, n))
        else -> latin1(" HTTP/1.1\r\nHost: x\r\nContent-Length: 1\r\n\r\n0\r\n\r\n".let { it.repeat(n / it.length + 1).substring(0, n) })
    }
}

class RequestFuzz(rnd: Random) : Fuzz(rnd) {
    private val methods = listOf("GET", "GET", "GET", "GET", "POST", "POST", "PUT", "DELETE", "HEAD", "OPTIONS", "PATCH",
        "TRACE", "get", "Post", "PROPFIND", "M-SEARCH", "OPTIONS", "GET", "POST", "GET")
    private val tchar = "!#$%&'*+-.^_`|~0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
    private val pathChars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~!$&'()*+,;=:@"
    private val queryChars = "abcdefghijklmnopqrstuvwxyzABCDEFG0123456789-._~!$'()*+,;:@/?%#\\"
    private val valueChars = (0x21..0x7E).map { it.toChar() }.joinToString("") + "      \t"
    private val names = listOf("Accept", "User-Agent", "Cookie", "Content-Type", "X-Request-Id", "Accept-Encoding", "Referer",
        "Connection", "Sec-Fetch-Mode", "Accept-Language", "Cache-Control", "Origin", "Pragma", "Upgrade-Insecure-Requests",
        "Hostx", "Hos", "X-Host", "Content-Lengtx", "Transfer-Encodinx", "If-None-Match", "sec-ch-ua", "X-Forwarded-For")
    private val semanticNames = listOf("content-length", "CONTENT-LENGTH", "Content-length", "transfer-encoding",
        "TRANSFER-ENCODING", "host", "HOST")
    override val interestingTokens = listOf("\r\n", "\r\n\r\n", "/..", "/.", "/./", "%25", "%00", "%2", "Host: x\r\n",
        "Content-Length: 5\r\n", "Content-Length: 0\r\n", "Transfer-Encoding: chunked\r\n", "?a=1&b", "&", "=", "HTTP/1.1",
        " HTTP/1.1\r\n", ", 5", " ,5", "\t", "  ", "#", "\\", "*", "\r", "\n", ": ", "\u0080", "OPTIONS", "CONNECT")

    private fun segment(): String = when (rnd.nextInt(60)) {
        0 -> "."
        1 -> ".."
        2, 3 -> "..."
        4, 5, 6 -> "%" + pick(listOf("25", "00", "2e", "2F", "41", "2", "0", "2e", "41", "7E"))
        7, 8 -> ".a"
        else -> string(pathChars, 12)
    }

    private fun pair(): String = when (rnd.nextInt(10)) {
        0 -> ""
        1 -> "=" + string(queryChars, 4)
        2, 3 -> string(queryChars, 6, 1)
        else -> string(queryChars, 8, 1) + "=" + string(queryChars + "=", 8)
    }

    private fun target(): String = when (rnd.nextInt(60)) {
        0 -> "*"
        1 -> "*?" + pair()
        2 -> ""
        3 -> "http://example.com/" + string(pathChars, 10)
        4 -> string(pathChars, 8, 1)
        else -> "/" + (0 until rnd.nextInt(5)).joinToString("/") { segment() } +
            (if (chance(0.5)) "?" + (0 until rnd.nextInt(7)).joinToString("&") { pair() } else "")
    }

    private fun version(): String = when (rnd.nextInt(80)) {
        0 -> "HTTP/2.0"
        1 -> "HTTP/1.2"
        2 -> "http/1.1"
        3 -> "HTTP/1"
        in 4..8 -> "HTTP/1.0"
        else -> "HTTP/1.1"
    }

    private fun contentLength(): String {
        val n = when (rnd.nextInt(10)) {
            0 -> "0"
            1 -> string("0123456789", 20, 19)
            2 -> pick(listOf("9223372036854775807", "9223372036854775808", "18446744073709551615", "18446744073709551616"))
            3 -> "0" + string("0123456789", 3, 1)
            else -> (1 + rnd.nextInt(100000)).toString()
        }
        return when (rnd.nextInt(16)) {
            0 -> "$n, $n"
            1 -> "$n ,$n"
            2 -> "$n,$n,$n"
            3 -> "$n "
            4 -> "$n,"
            5 -> "$n, ${n}1"
            else -> n
        }
    }

    private fun value(): String {
        val core = when (rnd.nextInt(10)) {
            0 -> ""
            1 -> string(valueChars, 200)
            2 -> String(CharArray(1 + rnd.nextInt(6)) { (0x80 + rnd.nextInt(128)).toChar() })
            else -> string(valueChars, 30, 1)
        }
        return (if (chance(0.2)) string(" \t", 3) else "") + core + (if (chance(0.1)) string(" \t", 2) else "")
    }

    private fun header(): String {
        val name = when {
            chance(0.02) -> pick(semanticNames)
            chance(0.7) -> pick(names)
            else -> string(tchar, 20, 1)
        }
        val v = when {
            name.length == 14 && name.lowercase() == "content-length" -> contentLength()
            name.length == 17 && name.lowercase() == "transfer-encoding" ->
                pick(listOf("chunked", "Chunked", " chunked ", "chunked\t", "chunked", "CHUNKED", "gzip, chunked", "identity"))
            else -> value()
        }
        return "$name:" + (if (chance(0.8)) " " else "") + v
    }

    fun request(): ByteArray {
        val sb = StringBuilder()
        sb.append(if (chance(0.95)) pick(methods) else string(tchar, 18, 1)).append(' ').append(target()).append(' ')
            .append(version()).append("\r\n")
        val lines = ArrayList<String>()
        val count = if (chance(0.05)) rnd.nextInt(70) else rnd.nextInt(12)
        repeat(count) { lines += header() }
        val hosts = when (rnd.nextInt(40)) { 0 -> 0; 1 -> 2; else -> 1 }
        repeat(hosts) {
            val host = if (chance(0.95)) pick(listOf("localhost:8080", "example.com", "[::1]:443", "x", "")) else pick(listOf("a@b", "x/y"))
            lines.add(rnd.nextInt(lines.size + 1), "Host: $host")
        }
        // framing: at most one of the two most of the time, both now and then
        val framing = rnd.nextInt(20)
        if (framing < 3 || framing == 19) lines.add(rnd.nextInt(lines.size + 1), "Content-Length: " + contentLength())
        if (framing in 3..4 || framing == 19) lines.add(rnd.nextInt(lines.size + 1), "Transfer-Encoding: chunked")
        for (l in lines) sb.append(l).append("\r\n")
        sb.append("\r\n")
        if (chance(0.3)) sb.append(pick(listOf("hello", "GET / HTTP/1.1\r\nHost: x\r\n\r\n", "0\r\n\r\n", "\r\n")))
        return latin1(sb.toString())
    }

    private fun limits(): ParserLimits {
        if (chance(0.8)) return ParserLimits.Default
        val d = ParserLimits.Default
        return ParserLimits(
            maxHeaderCount = if (chance(0.5)) rnd.nextInt(8) else d.maxHeaderCount,
            maxHeaderNameLength = if (chance(0.5)) rnd.nextInt(20) else d.maxHeaderNameLength,
            maxHeaderValueLength = if (chance(0.5)) rnd.nextInt(40) else d.maxHeaderValueLength,
            maxUrlLength = if (chance(0.5)) rnd.nextInt(40) else d.maxUrlLength,
            maxQueryParameterCount = if (chance(0.5)) rnd.nextInt(5) else d.maxQueryParameterCount,
            maxMethodLength = if (chance(0.5)) rnd.nextInt(10) else d.maxMethodLength,
            maxTotalHeaderBytes = if (chance(0.5)) rnd.nextInt(200) else d.maxTotalHeaderBytes,
        )
    }

    fun next(): FuzzCase {
        val base = if (chance(0.1)) pick(Vectors.requests).bytes else request()
        val bytes = when {
            chance(0.6) -> base
            chance(0.25) -> base.copyOf(rnd.nextInt(base.size + 1))
            else -> mutate(base)
        }
        return place(bytes, limits())
    }
}

class ChunkedFuzz(rnd: Random) : Fuzz(rnd) {
    override val interestingTokens = listOf("\r\n", ";", ";a=b", "0\r\n\r\n", "\r\n\r\n", "x", "_", "-", " ", "\t",
        "ffffffffffffffff", "8000000000000005", "0000000000000005", "7fffffffffffffff", "\n", "\r", "\u0000", "X: y\r\n")

    private fun chunk(): String {
        val data = string("abcdefghij0123456789\r\n", 40)
        val size = Integer.toHexString(data.length).let { h ->
            when (rnd.nextInt(6)) {
                0 -> h.uppercase()
                1 -> "000$h"
                else -> h
            }
        }
        val extension = if (chance(0.15)) ";" + string("abc=1;", 10, 1) else ""
        return "$size$extension\r\n$data\r\n"
    }

    fun body(): ByteArray {
        val sb = StringBuilder()
        repeat(rnd.nextInt(5)) { sb.append(chunk()) }
        sb.append(if (chance(0.9)) "0" else "000").append(if (chance(0.1)) ";x" else "").append("\r\n")
        repeat(if (chance(0.2)) 1 + rnd.nextInt(2) else 0) { sb.append("X-T: ").append(string("abc \t", 6)).append("\r\n") }
        sb.append("\r\n")
        if (chance(0.3)) sb.append(pick(listOf("GET / HTTP/1.1\r\n", "5\r\n", "\r\n")))
        return latin1(sb.toString())
    }

    fun next(): FuzzCase {
        val base = if (chance(0.15)) pick(Vectors.chunked) else body()
        val bytes = when {
            chance(0.4) -> base
            chance(0.2) -> base.copyOf(rnd.nextInt(base.size + 1))
            else -> mutate(base)
        }
        return place(bytes, ParserLimits.Default)
    }
}
