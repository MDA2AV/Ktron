package ktron

import ioxide.Memory
import ioxide.Reactor
import ioxide.RecvItem
import ioxide.RecvSnapshot
import ioxide.RingTimer
import ioxide.TcpConnection
import ioxide.tls.TlsService
import ioxide.tls.TlsSession
import ktron.glyph11.ByteBuilder
import ktron.glyph11.ChunkedDecoder
import ktron.glyph11.Glyph11
import ktron.glyph11.HttpParseException
import ktron.glyph11.RequestHead
import ktron.glyph11.parser.ParserLimits
import java.io.IOException
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * One HTTP/1.1 connection, start to finish, on the reactor that accepted it. Received bytes are copied into a
 * buffer of the connection's own and the provided buffers go straight back, so a waiting handler never holds one;
 * requests are parsed in place there, answered in order, and their responses staged in the write slab and sent
 * with one flush per batch. Nothing flushes while a handler runs: the slab is the handler's until it returns.
 *
 * Pooled per reactor, so a new connection costs nothing it did not cost the last one.
 */
internal class Http1Connection(private val state: ReactorState) {
    private val app = state.app
    private val limits = ParserLimits(maxTotalHeaderBytes = app.options.maxHeadBytes)

    internal lateinit var conn: TcpConnection
    private var tls: TlsSession? = null

    private val carry = Buffer(0)
    private var consumed = 0
    private val head = RequestHead()
    private val chunked = ByteBuilder()
    // A chunked body that trickles in is decoded once, each read going on where the last stopped.
    private val chunkedDecoder = ChunkedDecoder()
    internal val scratch = Buffer(0)
    private val ctx = Context(this)
    private var timerInstance: RingTimer? = null

    // An async route reached by process(), run by serve().
    private var pendingRoute: Route? = null
    private var pendingLength = 0
    private var pendingClose = false
    private var continued = false

    val reactor: Reactor get() = state.reactor

    fun timer(): RingTimer = timerInstance ?: RingTimer(state.reactor).also { timerInstance = it }

    suspend fun serve(connection: TcpConnection) {
        conn = connection
        try {
            val tlsService = state.tls
            if (tlsService != null && connection.listenerPort == app.options.tls!!.port) {
                val session = tlsService.accept(connection)
                tls = session
                // A request can ride in with the handshake's last flight.
                val early = session.drainPlaintext()
                appendNative(session.plaintext, early)
            }

            var outcome = process()
            while (true) {
                while (outcome == ASYNC || outcome == FLUSH) {
                    if (outcome == ASYNC) {
                        runAsync(pendingRoute!!)
                        outcome = if (finish(pendingLength, pendingClose || ctx.response.close)) CLOSE else process()
                    } else {
                        connection.flush()
                        outcome = process()
                    }
                }

                if (connection.writtenBytes > 0) connection.flush()
                if (outcome == CLOSE) {
                    connection.shutdownWrite()
                    return
                }

                compact()
                val snapshot = connection.read()
                feed(snapshot)
                // The peer's FIN ends the connection: what it sent before cannot be answered any more.
                if (snapshot.isClosed) return
                outcome = process()
            }
        } catch (e: IOException) {
            // A failed handshake or a broken record ends the connection, as the peer going away does.
        } finally {
            tls?.close()
            tls = null
            state.release(this)
        }
    }

    internal fun recycle() {
        carry.reset()
        consumed = 0
        chunkedDecoder.reset()
        continued = false
        pendingRoute = null
        ctx.reset()
    }

    // ── requests ─────────────────────────────────────────────────────────────────────────────────────────

    // Answers every complete request held, in order: ASYNC when it reached a suspending route, its request ready to
    // run; FLUSH when it wants the slab sent before going on; NEED_MORE when the rest has not arrived; CLOSE when
    // the connection ends after what is staged.
    private fun process(): Int {
        while (true) {
            val start = consumed
            val available = carry.length - start
            if (available == 0) return NEED_MORE
            val input = carry.array

            val headLength = try {
                Glyph11.parse(input, start, available, head, limits)
            } catch (e: HttpParseException) {
                return refuse(e.statusCode)
            }
            if (headLength == 0) return if (available > app.options.maxHeadBytes) refuse(431) else NEED_MORE

            val request = ctx.request
            var bodyWire = 0
            when (head.bodyFraming) {
                RequestHead.BODY_LENGTH -> {
                    if (head.contentLength > app.options.maxBodyBytes) return refuse(413)
                    val length = head.contentLength.toInt()
                    if (available - headLength < length) return awaitBody(input)
                    request.bodyArray = input
                    request.bodyStart = start + headLength
                    request.bodyLength = length
                    bodyWire = length
                }
                RequestHead.BODY_CHUNKED -> {
                    val wire = try {
                        chunkedDecoder.decode(input, start + headLength, available - headLength, chunked)
                    } catch (e: HttpParseException) {
                        return refuse(e.statusCode)
                    }
                    if (wire == 0) {
                        return if (available - headLength > 2L * app.options.maxBodyBytes + 4096) refuse(413) else awaitBody(input)
                    }
                    if (chunked.length > app.options.maxBodyBytes) return refuse(413)
                    request.bodyArray = chunked.array
                    request.bodyStart = 0
                    request.bodyLength = chunked.length
                    bodyWire = wire
                }
                else -> {
                    request.bodyArray = Request.EMPTY
                    request.bodyStart = 0
                    request.bodyLength = 0
                }
            }

            val requestLength = headLength + bodyWire
            ctx.reset()
            request.input = input
            request.head = head
            request.method = Method.of(input, head.methodStart, head.methodLength)
            val wantsClose = wantsClose(input)

            if (request.method == Method.UNKNOWN) {
                ctx.response.empty(501)
            } else {
                val route = app.router.match(input, head.pathStart, head.pathLength, request.method, request.params)
                when {
                    route == null -> {
                        ctx.response.empty(app.router.missStatus)
                        if (app.router.missStatus == 405) ctx.response.headerLine(app.router.allowed)
                    }
                    route.asyncHandler != null -> {
                        pendingRoute = route
                        pendingLength = requestLength
                        pendingClose = wantsClose
                        return ASYNC
                    }
                    else -> runSync(route)
                }
            }

            if (finish(requestLength, wantsClose || ctx.response.close)) return CLOSE
            if (conn.writtenBytes >= FLUSH_AT) return FLUSH
        }
    }

    // The body is still on its way. A client that asked to be told it may send it is told, once.
    private fun awaitBody(input: ByteArray): Int {
        if (!continued && ctx.request.let { it.input = input; it.head = head; it.headerHasToken(EXPECT, CONTINUE_100) }) {
            continued = true
            send(Wire.CONTINUE, 0, Wire.CONTINUE.size)
            return FLUSH
        }
        return NEED_MORE
    }

    // HTTP/1.1 keeps the connection unless it says close; HTTP/1.0 closes it unless it says keep-alive.
    private fun wantsClose(input: ByteArray): Boolean {
        val request = ctx.request
        if (request.headerHasToken(CONNECTION, CLOSE_TOKEN)) return true
        val http10 = head.versionLength == 8 && input[head.versionStart + 7] == '0'.code.toByte()
        return http10 && !request.headerHasToken(CONNECTION, KEEP_ALIVE)
    }

    private fun runSync(route: Route) {
        val middleware = app.middlewareArray
        var ran = 0
        try {
            var proceed = true
            while (ran < middleware.size) {
                val go = middleware[ran].before(ctx)
                ran++
                if (!go) {
                    proceed = false
                    break
                }
            }
            if (proceed) route.handler!!.handle(ctx)
        } catch (e: Throwable) {
            fault(e)
        }
        afterAll(ran)
    }

    private suspend fun runAsync(route: Route) {
        val middleware = app.middlewareArray
        var ran = 0
        try {
            var proceed = true
            while (ran < middleware.size) {
                val go = middleware[ran].before(ctx)
                ran++
                if (!go) {
                    proceed = false
                    break
                }
            }
            if (proceed) route.asyncHandler!!.handle(ctx)
        } catch (e: Throwable) {
            fault(e)
        }
        afterAll(ran)
    }

    private fun afterAll(ran: Int) {
        val middleware = app.middlewareArray
        for (i in ran - 1 downTo 0) {
            try {
                middleware[i].after(ctx)
            } catch (e: Throwable) {
                fault(e)
            }
        }
    }

    private fun fault(e: Throwable) {
        app.reportFault(e)
        ctx.response.reset()
        ctx.response.empty(500)
        ctx.response.close = true
    }

    // Writes the response and moves past its request; true when the connection closes after it.
    private fun finish(requestLength: Int, close: Boolean): Boolean {
        writeResponse(close)
        consumed += requestLength
        continued = false
        pendingRoute = null
        return close
    }

    // ── responses ────────────────────────────────────────────────────────────────────────────────────────

    private fun writeResponse(close: Boolean) {
        val response = ctx.response
        val bodyLength = response.bodyCount
        val headBuffer = state.headScratch
        headBuffer.reset()
        val out = headBuffer.ensure(HEAD_BASE + response.headers.length + response.contentType.size)
        var at = put(out, 0, Wire.statusLine(response.status))
        val status = response.status
        if (status >= 200 && status != 204 && status != 304) {
            if (bodyLength > 0) {
                at = put(out, at, Wire.CONTENT_TYPE)
                at = put(out, at, response.contentType)
                at = put(out, at, Wire.CRLF)
            }
            at = put(out, at, Wire.CONTENT_LENGTH)
            at = Ascii.writeLong(bodyLength.toLong(), out, at)
            at = put(out, at, Wire.CRLF)
        }
        if (response.headers.length > 0) {
            response.headers.array.copyInto(out, at, 0, response.headers.length)
            at += response.headers.length
        }
        if (close) at = put(out, at, Wire.CONNECTION_CLOSE)
        if (app.options.dateHeader) at = put(out, at, state.dateLine())
        at = put(out, at, Wire.CRLF)

        // HEAD answers with GET's headers and no body.
        val sent = if (ctx.request.method == Method.HEAD) 0 else bodyLength
        val session = tls
        if (session == null || session.kernelTx) {
            send(out, 0, at)
            if (sent > 0) send(response.bodyArray, response.bodyStart, sent)
        } else if (at + sent <= TLS_COALESCE) {
            // One record for head and body rather than one each.
            val staging = state.tlsStaging
            staging.reset()
            staging.append(out, 0, at)
            staging.append(response.bodyArray, response.bodyStart, sent)
            session.write(conn, staging.array, 0, staging.length)
        } else {
            session.write(conn, out, 0, at)
            if (sent > 0) session.write(conn, response.bodyArray, response.bodyStart, sent)
        }
    }

    // Stages bytes for the peer: as they are, or encrypted when BoringSSL owns this connection's writes.
    private fun send(array: ByteArray, offset: Int, length: Int) {
        val session = tls
        if (session == null) conn.write(array, offset, length) else session.write(conn, array, offset, length)
    }

    // A request that will not be served: its status, and the connection closes.
    private fun refuse(status: Int): Int {
        val bytes = Wire.refusal(status)
        send(bytes, 0, bytes.size)
        return CLOSE
    }

    private fun put(out: ByteArray, at: Int, bytes: ByteArray): Int {
        bytes.copyInto(out, at)
        return at + bytes.size
    }

    // ── bytes in ─────────────────────────────────────────────────────────────────────────────────────────

    private fun feed(snapshot: RecvSnapshot) {
        val item = state.item
        val session = tls
        while (conn.tryGetItem(snapshot, item)) {
            if (!item.hasBuffer) continue
            if (session == null) {
                appendNative(item.address, item.length)
            } else {
                val n = session.decrypt(item.address, item.length)   // sets plaintext: read it after
                appendNative(session.plaintext, n)
            }
            conn.returnBuffer(item)
        }
    }

    private fun appendNative(address: Long, count: Int) {
        if (count == 0) return
        val array = carry.ensure(count)
        Memory.copy(address, array, carry.length, count)
        carry.setLength(carry.length + count)
    }

    private fun compact() {
        if (consumed > 0) {
            carry.consume(consumed)
            consumed = 0
        }
    }

    private companion object {
        const val NEED_MORE = 0
        const val ASYNC = 1
        const val FLUSH = 2
        const val CLOSE = 3

        // Pipelined responses past this are sent before more are staged, so a batch cannot grow the slab unbounded.
        const val FLUSH_AT = 64 * 1024
        const val TLS_COALESCE = 16 * 1024
        const val HEAD_BASE = 160

        val CONNECTION = "connection".toByteArray()
        val CLOSE_TOKEN = "close".toByteArray()
        val KEEP_ALIVE = "keep-alive".toByteArray()
        val EXPECT = "expect".toByteArray()
        val CONTINUE_100 = "100-continue".toByteArray()
    }
}

/** What one reactor shares across its connections: scratch buffers, the TLS context, and the connection pool. */
internal class ReactorState(val reactor: Reactor, val app: Ktron, val tls: TlsService?) {
    val item = RecvItem()
    val headScratch = Buffer(512)
    val tlsStaging = Buffer(0)
    private val pool = ArrayDeque<Http1Connection>()

    fun acquire(): Http1Connection = pool.removeLastOrNull() ?: Http1Connection(this)

    fun release(connection: Http1Connection) {
        connection.recycle()
        if (pool.size < POOL_MAX) pool.addLast(connection)
    }

    // "Date: <IMF-fixdate>\r\n", made once a second.
    private var dateSecond = -1L
    private var date = ByteArray(0)

    fun dateLine(): ByteArray {
        val second = System.currentTimeMillis() / 1000
        if (second != dateSecond) {
            dateSecond = second
            date = ("Date: " + DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now(ZoneOffset.UTC)) + "\r\n").toByteArray()
        }
        return date
    }

    private companion object {
        const val POOL_MAX = 1024
    }
}
