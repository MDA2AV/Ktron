package ktron.compression.bench

import java.nio.file.Files
import java.nio.file.Path

/**
 * json-comp's three bodies, built from HttpArena's dataset.json the way the profile's Python reference does:
 * `json.dumps({'items': [dict(it, total=it['price'] * it['quantity'] * m) for it in src[:N]], 'count': N},
 * separators=(',', ':'))`, so fields in dataset order, `total` last, and ensure_ascii's escapes.
 */
object Bodies {
    val shapes = listOf(25 to 4, 40 to 8, 50 to 6)

    fun load(dataset: Path): List<ByteArray> {
        @Suppress("UNCHECKED_CAST")
        val items = JsonReader(Files.readString(dataset)).value() as List<Map<String, Any?>>
        return shapes.map { (count, m) -> body(items, count, m) }
    }

    fun body(items: List<Map<String, Any?>>, count: Int, m: Int): ByteArray {
        val out = StringBuilder()
        out.append("{\"items\":[")
        for (i in 0 until count) {
            if (i > 0) out.append(',')
            val item = LinkedHashMap(items[i])
            val price = item["price"] as? Long ?: error("item $i: price is not an integer")
            val quantity = item["quantity"] as? Long ?: error("item $i: quantity is not an integer")
            item["total"] = price * quantity * m
            write(out, item)
        }
        out.append("],\"count\":").append(count).append('}')
        return out.toString().toByteArray(Charsets.US_ASCII)
    }

    private fun write(out: StringBuilder, v: Any?) {
        when (v) {
            null -> out.append("null")
            is Boolean -> out.append(v)
            is Long -> out.append(v)
            is String -> string(out, v)
            is List<*> -> {
                out.append('[')
                v.forEachIndexed { i, e -> if (i > 0) out.append(','); write(out, e) }
                out.append(']')
            }
            is Map<*, *> -> {
                out.append('{')
                var first = true
                for ((k, e) in v) {
                    if (!first) out.append(',')
                    first = false
                    string(out, k as String)
                    out.append(':')
                    write(out, e)
                }
                out.append('}')
            }
            // Python's float repr is not reproduced: the dataset has none, and a body that differs must not pass.
            else -> error("unsupported JSON value ${v::class}")
        }
    }

    // ensure_ascii: everything outside printable ASCII as \uXXXX, the short escapes where JSON has them.
    private fun string(out: StringBuilder, s: String) {
        out.append('"')
        for (c in s) {
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c == '\t' -> out.append("\\t")
                c == '\b' -> out.append("\\b")
                c == '\u000c' -> out.append("\\f")
                c < ' ' || c > '~' -> out.append("\\u").append(String.format("%04x", c.code))
                else -> out.append(c)
            }
        }
        out.append('"')
    }
}

/** Enough JSON for dataset.json: objects keep their key order, integers stay Long. */
private class JsonReader(private val s: String) {
    private var i = 0

    fun value(): Any? {
        skip()
        val v = when (val c = s[i]) {
            '{' -> obj()
            '[' -> arr()
            '"' -> str()
            't' -> literal("true", true)
            'f' -> literal("false", false)
            'n' -> literal("null", null)
            else -> if (c == '-' || c.isDigit()) num() else error("unexpected '$c' at $i")
        }
        skip()
        return v
    }

    private fun obj(): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        i++
        skip()
        if (s[i] == '}') { i++; return m }
        while (true) {
            skip()
            val k = str()
            skip()
            expect(':')
            m[k] = value()
            if (s[i] == ',') { i++; continue }
            expect('}')
            return m
        }
    }

    private fun arr(): List<Any?> {
        val l = ArrayList<Any?>()
        i++
        skip()
        if (s[i] == ']') { i++; return l }
        while (true) {
            l.add(value())
            if (s[i] == ',') { i++; continue }
            expect(']')
            return l
        }
    }

    private fun str(): String {
        expect('"')
        val b = StringBuilder()
        while (true) {
            val c = s[i++]
            when (c) {
                '"' -> return b.toString()
                '\\' -> when (val e = s[i++]) {
                    'n' -> b.append('\n'); 'r' -> b.append('\r'); 't' -> b.append('\t')
                    'b' -> b.append('\b'); 'f' -> b.append('\u000c')
                    'u' -> { b.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                    else -> b.append(e)
                }
                else -> b.append(c)
            }
        }
    }

    private fun num(): Any {
        val start = i
        while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
        val t = s.substring(start, i)
        return t.toLongOrNull() ?: t.toDouble()
    }

    private fun literal(word: String, v: Any?): Any? {
        check(s.startsWith(word, i)) { "expected $word at $i" }
        i += word.length
        return v
    }

    private fun expect(c: Char) {
        check(s[i] == c) { "expected '$c' at $i, got '${s[i]}'" }
        i++
    }

    private fun skip() {
        while (i < s.length && s[i].isWhitespace()) i++
    }
}
