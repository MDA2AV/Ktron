package ktron

import com.ghost.serialization.annotations.GhostSerialization
import java.io.InputStream
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@GhostSerialization
data class Greeting(val message: String, val n: Int)

/** Ktron end to end, over raw sockets: what goes on the wire, byte for byte where it matters. */
class KtronTest {
    private class Reply(val status: Int, val headers: Map<String, String>, val body: String)

    private fun withApp(configure: (Ktron) -> Unit, body: (port: Int) -> Unit) {
        val app = Ktron(KtronOptions(port = 0, reactors = 1))
        configure(app)
        val server = app.start()
        try {
            body(server.port)
        } finally {
            server.close()
        }
    }

    // Opens a connection, sends [pieces] with [pauseMs] between them, and reads [responses] replies.
    private fun exchange(port: Int, vararg pieces: String, responses: Int = 1, pauseMs: Long = 0): List<Reply> =
        Socket("127.0.0.1", port).use { socket ->
            socket.tcpNoDelay = true
            socket.soTimeout = 5_000
            val out = socket.getOutputStream()
            for ((i, piece) in pieces.withIndex()) {
                out.write(piece.toByteArray(Charsets.ISO_8859_1))
                out.flush()
                if (pauseMs > 0 && i < pieces.lastIndex) Thread.sleep(pauseMs)
            }
            val input = socket.getInputStream()
            List(responses) { read(input) }
        }

    private fun readHead(input: InputStream): String {
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) {
            val b = input.read()
            check(b >= 0) { "connection ended inside a response head: $head" }
            head.append(b.toChar())
        }
        return head.toString()
    }

    private fun read(input: InputStream): Reply {
        val head = readHead(input)
        val lines = head.trimEnd().split("\r\n")
        val status = lines[0].split(' ')[1].toInt()
        val headers = lines.drop(1).associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
        val length = headers["content-length"]?.toInt() ?: 0
        val body = if (status == 100) "" else String(input.readNBytes(length), Charsets.UTF_8)
        return Reply(status, headers, body)
    }

    private fun get(path: String, extra: String = "") = "GET $path HTTP/1.1\r\nHost: t\r\n$extra\r\n"

    @Test
    fun `routes, parameters and the query reach the handler`() = withApp({ app ->
        app.get("/users/{id}") { ctx -> ctx.response.text("id=${ctx.request.param("id")} x=${ctx.request.query("x")} n=${ctx.request.paramInt("id")}") }
        app.get("/files/{*path}") { ctx -> ctx.response.text(ctx.request.param("path")!!) }
        app.get("/") { ctx -> ctx.response.text("root") }
    }) { port ->
        assertEquals("id=42 x=1 n=42", exchange(port, get("/users/42?x=1"))[0].body)
        assertEquals("a/b/c.txt", exchange(port, get("/files/a/b/c.txt"))[0].body)
        assertEquals("root", exchange(port, get("/"))[0].body)
        assertEquals("id=7 x=null n=7", exchange(port, get("/users/7/"))[0].body)
    }

    @Test
    fun `misses are 404, a wrong method 405 with Allow, HEAD answers like GET without a body, an unknown method 501`() = withApp({ app ->
        app.get("/thing") { ctx -> ctx.response.text("thing") }
        app.post("/thing") { ctx -> ctx.response.text("posted") }
    }) { port ->
        assertEquals(404, exchange(port, get("/nope"))[0].status)
        val wrong = exchange(port, "DELETE /thing HTTP/1.1\r\nHost: t\r\n\r\n")[0]
        assertEquals(405, wrong.status)
        assertEquals("GET, HEAD, POST", wrong.headers["allow"])
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 5_000
            // HEAD carries GET's Content-Length and no body: the GET after it on the same connection reads cleanly.
            socket.getOutputStream().write("HEAD /thing HTTP/1.1\r\nHost: t\r\n\r\n${get("/thing")}".toByteArray())
            val input = socket.getInputStream()
            val head = readHead(input)
            assertTrue(head.startsWith("HTTP/1.1 200"), head)
            assertTrue(head.contains("Content-Length: 5"), head)
            assertEquals("thing", read(input).body)
        }
        assertEquals(501, exchange(port, "BREW /thing HTTP/1.1\r\nHost: t\r\n\r\n")[0].status)
    }

    @Test
    fun `middleware run in order, see the finished response, and can short-circuit`() {
        val order = ArrayList<String>()
        withApp({ app ->
            app.use(object : Middleware {
                override fun before(ctx: Context): Boolean = true.also { order.add("a-before") }
                override fun after(ctx: Context) {
                    order.add("a-after")
                    ctx.response.header("X-Seen", ctx.response.status.toString())
                }
            })
            app.use(object : Middleware {
                override fun before(ctx: Context): Boolean {
                    order.add("b-before")
                    if (ctx.request.header("authorization") == null) {
                        ctx.response.empty(401)
                        return false
                    }
                    return true
                }
                override fun after(ctx: Context) {
                    order.add("b-after")
                }
            })
            app.get("/secret") { ctx ->
                order.add("handler")
                ctx.response.text("s")
            }
        }) { port ->
            val refused = exchange(port, get("/secret"))[0]
            assertEquals(401, refused.status)
            assertEquals("401", refused.headers["x-seen"])
            assertEquals(listOf("a-before", "b-before", "b-after", "a-after"), order.toList())

            order.clear()
            val allowed = exchange(port, get("/secret", "Authorization: x\r\n"))[0]
            assertEquals("s", allowed.body)
            assertEquals(listOf("a-before", "b-before", "handler", "b-after", "a-after"), order.toList())
        }
    }

    @Test
    fun `an async handler waits on the ring and answers in order with what follows it`() = withApp({ app ->
        app.getAsync("/wait/{ms}") { ctx ->
            val ms = ctx.request.paramLong("ms")
            ctx.delay(ms)
            ctx.response.text(ms)
        }
        app.get("/now") { ctx -> ctx.response.text("now") }
    }) { port ->
        val started = System.nanoTime()
        val replies = exchange(port, get("/wait/60") + get("/now"), responses = 2)
        val ms = (System.nanoTime() - started) / 1_000_000
        assertEquals(listOf("60", "now"), replies.map { it.body })
        assertTrue(ms >= 60, "answered after $ms ms")
    }

    @Test
    fun `pipelined requests are answered in order`() = withApp({ app ->
        app.get("/n/{i}") { ctx -> ctx.response.text("n" + ctx.request.param("i")) }
    }) { port ->
        val replies = exchange(port, (1..20).joinToString("") { get("/n/$it") }, responses = 20)
        assertEquals((1..20).map { "n$it" }, replies.map { it.body })
    }

    @Test
    fun `a request split across reads is reassembled`() = withApp({ app ->
        app.post("/sum") { ctx -> ctx.response.text(ctx.request.queryLong("a") + ctx.request.bodyText().toLong()) }
    }) { port ->
        val reply = exchange(port, "POST /sum?a=1", "3 HTTP/1.1\r\nHost: t\r\nContent-Le", "ngth: 2\r\n\r\n4", "2", pauseMs = 30)[0]
        assertEquals("55", reply.body)
    }

    @Test
    fun `bodies come by Content-Length and chunked, and Expect 100-continue is answered`() = withApp({ app ->
        app.post("/echo") { ctx -> ctx.response.bytes(ctx.request.bodyBytes, ctx.request.bodyOffset, ctx.request.bodyLength) }
    }) { port ->
        assertEquals("hello", exchange(port, "POST /echo HTTP/1.1\r\nHost: t\r\nContent-Length: 5\r\n\r\nhello")[0].body)
        assertEquals("hello world", exchange(port, "POST /echo HTTP/1.1\r\nHost: t\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nhello\r\n6\r\n world\r\n0\r\n\r\n")[0].body)

        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 5_000
            val out = socket.getOutputStream()
            out.write("POST /echo HTTP/1.1\r\nHost: t\r\nExpect: 100-continue\r\nContent-Length: 4\r\n\r\n".toByteArray())
            val input = socket.getInputStream()
            assertEquals(100, read(input).status)
            out.write("body".toByteArray())
            assertEquals("body", read(input).body)
        }
    }

    @Test
    fun `Connection close is honoured and HTTP 1_0 closes unless kept alive`() = withApp({ app ->
        app.get("/x") { ctx -> ctx.response.text("x") }
    }) { port ->
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 5_000
            socket.getOutputStream().write(get("/x", "Connection: close\r\n").toByteArray())
            val input = socket.getInputStream()
            val reply = read(input)
            assertEquals("close", reply.headers["connection"])
            assertEquals(-1, input.read())
        }
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 5_000
            socket.getOutputStream().write("GET /x HTTP/1.0\r\nHost: t\r\n\r\n".toByteArray())
            val input = socket.getInputStream()
            read(input)
            assertEquals(-1, input.read())
        }
        val kept = exchange(port, "GET /x HTTP/1.0\r\nHost: t\r\nConnection: keep-alive\r\n\r\n" + get("/x"), responses = 2)
        assertNull(kept[0].headers["connection"])
    }

    @Test
    fun `malformed and oversized requests are refused with their status and closed`() = withApp({ app ->
        app.post("/x") { ctx -> ctx.response.text("x") }
    }) { port ->
        val smuggled = exchange(port, "POST /x HTTP/1.1\r\nHost: t\r\nContent-Length: 1\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n\r\n")[0]
        assertEquals(400, smuggled.status)
        assertEquals("close", smuggled.headers["connection"])
        assertEquals(431, exchange(port, "GET /x HTTP/1.1\r\nHost: t\r\nX-Big: " + "a".repeat(40_000) + "\r\n\r\n")[0].status)
        assertEquals(413, exchange(port, "POST /x HTTP/1.1\r\nHost: t\r\nContent-Length: 2000000\r\n\r\n")[0].status)
    }

    @Test
    fun `a handler that throws gets a 500 and the connection closes`() {
        val faults = ArrayList<Throwable>()
        withApp({ app ->
            app.onFault = { faults.add(it) }
            app.get("/boom") { throw IllegalStateException("boom") }
        }) { port ->
            val reply = exchange(port, get("/boom"))[0]
            assertEquals(500, reply.status)
            assertEquals("close", reply.headers["connection"])
            assertEquals("boom", faults.single().message)
        }
    }

    @Test
    fun `JSON goes out through Ghost and comes back in`() = withApp({ app ->
        app.get("/greet/{n}") { ctx -> ctx.response.json(Greeting("hi", ctx.request.paramInt("n"))) }
        app.post("/greet") { ctx ->
            val greeting = ctx.request.json<Greeting>()
            ctx.response.json(greeting.copy(n = greeting.n + 1))
        }
    }) { port ->
        val out = exchange(port, get("/greet/3") + get("/greet/4"), responses = 2)
        assertEquals("""{"message":"hi","n":3}""", out[0].body)
        assertEquals("""{"message":"hi","n":4}""", out[1].body)
        assertEquals("application/json", out[0].headers["content-type"])
        val body = """{"message":"yo","n":41}"""
        val back = exchange(port, "POST /greet HTTP/1.1\r\nHost: t\r\nContent-Type: application/json\r\nContent-Length: ${body.length}\r\n\r\n$body")[0]
        assertEquals("""{"message":"yo","n":42}""", back.body)
    }

    @Test
    fun `routes are fixed once running`() {
        val app = Ktron(KtronOptions(port = 0, reactors = 1))
        app.get("/a") { ctx -> ctx.response.text("a") }
        app.start().use {
            assertFalse(runCatching { app.get("/b") { ctx -> ctx.response.text("b") } }.isSuccess)
        }
    }
}
