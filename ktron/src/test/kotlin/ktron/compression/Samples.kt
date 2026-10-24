package ktron.compression

import ktron.glyph11.ByteBuilder
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/** A json-comp shaped body: [count] items of the dataset's fields, compact. */
fun json(count: Int): ByteArray {
    val names = listOf("Alpha Widget", "Pro Valve", "Core Relay", "Edge Link", "Nano Gadget", "Ultra Driver")
    val tags = listOf("sale", "heavy-duty", "popular", "fast", "new", "wireless", "eco", "limited", "premium")
    val r = Random(count)
    val s = StringBuilder("{\"items\":[")
    for (i in 1..count) {
        if (i > 1) s.append(',')
        val price = r.nextInt(1, 500)
        val quantity = r.nextInt(1, 100)
        s.append("{\"id\":$i,\"name\":\"${names[r.nextInt(names.size)]}\",\"category\":\"tools\",\"price\":$price,")
        s.append("\"quantity\":$quantity,\"active\":${r.nextBoolean()},\"tags\":[")
        s.append((0 until r.nextInt(1, 5)).joinToString(",") { "\"${tags[r.nextInt(tags.size)]}\"" })
        s.append("],\"rating\":{\"score\":${r.nextInt(50)},\"count\":${r.nextInt(500)}},\"total\":${price * quantity * 4}}")
    }
    return s.append("],\"count\":$count}").toString().toByteArray()
}

fun random(size: Int): ByteArray = Random(size).nextBytes(size)

fun gunzip(b: ByteArray, offset: Int, length: Int): ByteArray = GZIPInputStream(ByteArrayInputStream(b, offset, length)).readAllBytes()

fun unbrotli(b: ByteArray, offset: Int, length: Int, limit: Int): ByteArray =
    BrotliDec.decompress(b, offset, length, limit) ?: throw AssertionError("not a brotli stream of at most $limit bytes")

/**
 * Encodes `body` into a builder that already holds a prefix, from an offset into a bigger array, and checks the
 * prefix survived, the count is what was appended, and the appended bytes decode to the body. Returns them.
 */
fun roundTrip(encoder: ContentEncoder, body: ByteArray): ByteArray {
    val prefix = "HTTP/1.1 200 OK\r\n\r\n".toByteArray()
    val out = ByteBuilder()
    out.append(prefix, 0, prefix.size)
    val framed = ByteArray(body.size + 11).also { body.copyInto(it, 5) }
    val n = encoder.encode(framed, 5, body.size, out)
    assertEquals(prefix.size + n, out.length, "the returned count is what was appended")
    assertContentEquals(prefix, out.array.copyOf(prefix.size), "what the builder held is kept")
    val decoded = when (encoder.name) {
        "gzip" -> gunzip(out.array, prefix.size, n)
        "br" -> unbrotli(out.array, prefix.size, n, body.size)
        else -> throw AssertionError(encoder.name)
    }
    assertContentEquals(body, decoded)
    return out.array.copyOfRange(prefix.size, prefix.size + n)
}
