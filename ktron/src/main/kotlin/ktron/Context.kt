package ktron

import com.ghost.serialization.Ghost
import com.ghost.serialization.InternalGhostApi
import com.ghost.serialization.writer.bytes.FlatByteArrayWriter
import com.ghost.serialization.writer.bytes.GhostJsonWriter
import ioxide.Reactor
import ioxide.RingTimer
import ktron.glyph11.RequestHead

/**
 * One request in flight: what arrived, what goes back, and the route's parameters. One per connection, reused for
 * every request on it; everything a handler reads from it is valid until the handler returns.
 */
public class Context internal constructor(@PublishedApi internal val connection: Http1Connection) {
    public val request: Request = Request(this)
    public val response: Response = Response()

    /** For middleware to hand a value to the handler, or to its own [Middleware.after]. Cleared per request. */
    public var state: Any? = null

    /** The reactor this request runs on: its thread is the one every suspension resumes on. */
    public val reactor: Reactor get() = connection.reactor

    /** Waits [milliseconds] on the reactor's ring (IORING_OP_TIMEOUT): no thread is held while it does. */
    public suspend fun delay(milliseconds: Long) {
        connection.timer().delay(milliseconds)
    }

    internal fun reset() {
        state = null
        response.reset()
    }
}

/** The request, read in place from the connection's buffer: path, query, headers and body are offsets, not copies. */
public class Request internal constructor(private val ctx: Context) {
    internal var input: ByteArray = EMPTY
    internal lateinit var head: RequestHead
    internal val params = RouteParams()

    /** The request method, one of the [Method] constants. */
    public var method: Int = Method.UNKNOWN
        internal set

    internal var bodyArray: ByteArray = EMPTY
    internal var bodyStart = 0

    /** Body bytes, decoded when it came chunked. */
    public var bodyLength: Int = 0
        internal set

    public val methodName: String get() = Method.name(method)

    public fun path(): String = String(input, head.pathStart, head.pathLength, Charsets.ISO_8859_1)

    /** The raw query string, without '?': empty when there is none. */
    public fun queryString(): String = String(input, head.queryStart, head.queryLength, Charsets.ISO_8859_1)

    // ── route parameters ──

    public fun param(name: String): String? {
        val i = params.indexOf(name)
        return if (i < 0) null else String(input, params.starts[i], params.lengths[i], Charsets.UTF_8)
    }

    /** The parameter as a decimal Long, parsed in place; [fallback] when absent or not a number. */
    public fun paramLong(name: String, fallback: Long = 0): Long {
        val i = params.indexOf(name)
        return if (i < 0) fallback else Ascii.parseLong(input, params.starts[i], params.starts[i] + params.lengths[i]) ?: fallback
    }

    public fun paramInt(name: String, fallback: Int = 0): Int = paramLong(name, fallback.toLong()).toIntOrFallback(fallback)

    // ── query ──

    public val queryCount: Int get() = head.queryCount

    public fun queryKey(index: Int): String = String(input, head.queryKeyStart(index), head.queryKeyLength(index), Charsets.UTF_8)

    public fun queryValue(index: Int): String = String(input, head.queryValueStart(index), head.queryValueLength(index), Charsets.UTF_8)

    /** The query value at [index] as a decimal Long, parsed in place; [fallback] when it is not a number. */
    public fun queryLong(index: Int, fallback: Long = 0): Long {
        val start = head.queryValueStart(index)
        return Ascii.parseLong(input, start, start + head.queryValueLength(index)) ?: fallback
    }

    public fun query(name: String): String? {
        val i = head.findQuery(input, name.toByteArray())
        return if (i < 0) null else queryValue(i)
    }

    /** The named query value as a decimal Long, parsed in place; [fallback] when absent or not a number. */
    public fun queryLong(name: ByteArray, fallback: Long = 0): Long {
        val i = head.findQuery(input, name)
        return if (i < 0) fallback else queryLong(i, fallback)
    }

    public fun queryLong(name: String, fallback: Long = 0): Long = queryLong(name.toByteArray(), fallback)

    public fun queryInt(name: String, fallback: Int = 0): Int = queryLong(name, fallback.toLong()).toIntOrFallback(fallback)

    // ── headers ──

    public val headerCount: Int get() = head.headerCount

    public fun headerName(index: Int): String = String(input, head.headerNameStart(index), head.headerNameLength(index), Charsets.ISO_8859_1)

    public fun headerValue(index: Int): String = String(input, head.headerValueStart(index), head.headerValueLength(index), Charsets.ISO_8859_1)

    /** The first header named [name], matched case-insensitively. Pass [lowerName] bytes to look up without encoding. */
    public fun header(name: String): String? = header(name.lowercase().toByteArray())

    public fun header(lowerName: ByteArray): String? {
        val i = head.findHeader(input, lowerName)
        return if (i < 0) null else headerValue(i)
    }

    /** Whether a header named [lowerName] has a comma-separated element equal to [lowerToken], case-insensitively. */
    public fun headerHasToken(lowerName: ByteArray, lowerToken: ByteArray): Boolean {
        for (i in 0 until head.headerCount) {
            if (head.headerNameLength(i) == lowerName.size && RequestHeadAccess.equalsIgnoreCase(input, head.headerNameStart(i), lowerName) &&
                Tokens.contains(input, head.headerValueStart(i), head.headerValueLength(i), lowerToken)
            ) {
                return true
            }
        }
        return false
    }

    // ── body ──

    /** The body's bytes live in [bodyBytes] from [bodyOffset] for [bodyLength]; valid until the handler returns. */
    public val bodyBytes: ByteArray get() = bodyArray
    public val bodyOffset: Int get() = bodyStart

    public fun bodyText(): String = String(bodyArray, bodyStart, bodyLength, Charsets.UTF_8)

    /** The body as JSON, deserialized by Ghost into [T]. */
    public inline fun <reified T : Any> json(): T {
        val scratch = jsonScratch()
        return Ghost.decodeFromBytes(scratch, T::class, bodyLength)
    }

    // Ghost reads from offset 0, so the body is copied to the front of a reused array first.
    @PublishedApi
    internal fun jsonScratch(): ByteArray {
        val scratch = ctx.connection.scratch
        scratch.reset()
        scratch.append(bodyArray, bodyStart, bodyLength)
        return scratch.array
    }

    /** The body as an application/x-www-form-urlencoded form, decoded. */
    public fun form(): Map<String, String> = Forms.parse(bodyArray, bodyStart, bodyLength)

    private fun Long.toIntOrFallback(fallback: Int): Int = if (this in Int.MIN_VALUE..Int.MAX_VALUE) toInt() else fallback

    internal companion object {
        val EMPTY = ByteArray(0)
    }
}

/**
 * The response being built: status, headers and body, sent once the handler and the middleware are done. The body
 * is a view - over the connection's own buffers when a handler sets it with [text] or [json], or over the
 * handler's array with [bytes], which must then stay unchanged until the request completes.
 */
public class Response internal constructor() {
    public var status: Int = 200

    internal var contentType: ByteArray = Wire.TEXT_PLAIN
    internal val headers = Buffer(0)
    internal var bodyArray: ByteArray = Request.EMPTY
    internal var bodyStart = 0
    internal var bodyCount = 0
    internal val ownBody = Buffer(0)

    // Ghost writes JSON into this connection's own buffer; made on the first JSON response.
    @PublishedApi
    internal var jsonBuffer: FlatByteArrayWriter? = null
    @PublishedApi
    internal var jsonWriter: GhostJsonWriter? = null

    /** Close the connection once this response is out. */
    public var close: Boolean = false

    public val bodyLength: Int get() = bodyCount
    public val bodyBytes: ByteArray get() = bodyArray
    public val bodyOffset: Int get() = bodyStart

    internal fun reset() {
        status = 200
        contentType = Wire.TEXT_PLAIN
        headers.reset()
        bodyArray = Request.EMPTY
        bodyStart = 0
        bodyCount = 0
        close = false
    }

    public fun status(code: Int): Response {
        status = code
        return this
    }

    /** Adds a header line. Names and values are checked: a CR, LF or NUL in either is refused. */
    public fun header(name: String, value: String): Response {
        require(name.isNotEmpty() && name.none { it == '\r' || it == '\n' || it == '\u0000' || it == ':' || it == ' ' }) { "Bad header name '$name'" }
        require(value.none { it == '\r' || it == '\n' || it == '\u0000' }) { "Bad header value for '$name'" }
        val line = "$name: $value\r\n".toByteArray()
        headers.append(line, 0, line.size)
        return this
    }

    /** Adds a header line already encoded as "Name: value\r\n" - for middleware, which add the same lines on every request. */
    public fun headerLine(line: ByteArray): Response {
        headers.append(line, 0, line.size)
        return this
    }

    public fun contentType(type: String): Response {
        contentType = type.toByteArray()
        return this
    }

    /** Sets an encoded Content-Type value without encoding it per request. */
    public fun contentType(type: ByteArray): Response {
        contentType = type
        return this
    }

    public fun text(value: String, status: Int = 200): Response {
        val bytes = value.toByteArray()
        ownBody.reset()
        ownBody.append(bytes, 0, bytes.size)
        return ownBody(Wire.TEXT_PLAIN, status)
    }

    /** A decimal number as a text/plain body, formatted without allocating. */
    public fun text(value: Long, status: Int = 200): Response {
        ownBody.reset()
        ownBody.setLength(Ascii.writeLong(value, ownBody.ensure(Ascii.MAX_LONG_BYTES), 0))
        return ownBody(Wire.TEXT_PLAIN, status)
    }

    /** [length] bytes of [array] from [offset] as the body. They are read when the response is sent, not copied now. */
    public fun bytes(array: ByteArray, offset: Int = 0, length: Int = array.size - offset, contentType: ByteArray = Wire.OCTET_STREAM, status: Int = 200): Response {
        this.status = status
        this.contentType = contentType
        bodyArray = array
        bodyStart = offset
        bodyCount = length
        return this
    }

    /** An empty body. */
    public fun empty(status: Int): Response {
        this.status = status
        bodyArray = Request.EMPTY
        bodyStart = 0
        bodyCount = 0
        return this
    }

    /** [value] serialized to JSON by Ghost, straight into this connection's buffer: no array per response. */
    @OptIn(InternalGhostApi::class)
    public inline fun <reified T : Any> json(value: T, status: Int = 200): Response {
        val buffer = jsonBuffer ?: FlatByteArrayWriter().also {
            jsonBuffer = it
            jsonWriter = GhostJsonWriter(it)
        }
        val writer = jsonWriter!!
        buffer.reset()
        writer.reset()   // its separator state outlives a document, as Ghost's own pool resets it
        Ghost.resolveSerializer<T>().serialize(writer, value)
        return jsonBody(buffer.array, buffer.size, status)
    }

    @PublishedApi
    internal fun jsonBody(array: ByteArray, length: Int, status: Int): Response {
        this.status = status
        contentType = Wire.APPLICATION_JSON
        bodyArray = array
        bodyStart = 0
        bodyCount = length
        return this
    }

    private fun ownBody(type: ByteArray, status: Int): Response {
        this.status = status
        contentType = type
        bodyArray = ownBody.array
        bodyStart = 0
        bodyCount = ownBody.length
        return this
    }

    /** Replaces the body with [length] bytes of [array] - for middleware that transform it, like compression. */
    public fun replaceBody(array: ByteArray, offset: Int, length: Int) {
        bodyArray = array
        bodyStart = offset
        bodyCount = length
    }

    /** Whether the response already carries a header line starting "[lowerName]:", case-insensitively. */
    public fun hasHeader(lowerName: ByteArray): Boolean {
        val bytes = headers.array
        var at = 0
        val end = headers.length
        while (at < end) {
            var lineEnd = at
            while (lineEnd < end && bytes[lineEnd] != '\n'.code.toByte()) lineEnd++
            if (lineEnd - at > lowerName.size && bytes[at + lowerName.size] == ':'.code.toByte() &&
                RequestHeadAccess.equalsIgnoreCase(bytes, at, lowerName)
            ) {
                return true
            }
            at = lineEnd + 1
        }
        return false
    }
}

/** The parts of [RequestHead]'s companion the framework reuses. */
internal object RequestHeadAccess {
    fun equalsIgnoreCase(input: ByteArray, at: Int, lower: ByteArray): Boolean {
        for (i in lower.indices) {
            var c = input[at + i].toInt()
            if (c in 'A'.code..'Z'.code) c += 32
            if (c != lower[i].toInt()) return false
        }
        return true
    }
}

/** Comma-separated header values, as Connection and Accept-Encoding have them. */
internal object Tokens {
    /** Whether the list at `[start, start + length)` holds an element equal to [lowerToken], ignoring case, OWS and parameters. */
    fun contains(input: ByteArray, start: Int, length: Int, lowerToken: ByteArray): Boolean {
        var at = start
        val end = start + length
        while (at < end) {
            while (at < end && (input[at] == ' '.code.toByte() || input[at] == '\t'.code.toByte() || input[at] == ','.code.toByte())) at++
            var tokenEnd = at
            while (tokenEnd < end && input[tokenEnd] != ','.code.toByte() && input[tokenEnd] != ';'.code.toByte() &&
                input[tokenEnd] != ' '.code.toByte() && input[tokenEnd] != '\t'.code.toByte()
            ) {
                tokenEnd++
            }
            if (tokenEnd - at == lowerToken.size && RequestHeadAccess.equalsIgnoreCase(input, at, lowerToken)) return true
            while (tokenEnd < end && input[tokenEnd] != ','.code.toByte()) tokenEnd++
            at = tokenEnd + 1
        }
        return false
    }
}

/** application/x-www-form-urlencoded. */
internal object Forms {
    fun parse(bytes: ByteArray, start: Int, length: Int): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        if (length == 0) return result
        for (pair in String(bytes, start, length, Charsets.ISO_8859_1).split('&')) {
            if (pair.isEmpty()) continue
            val equals = pair.indexOf('=')
            val key = if (equals < 0) pair else pair.substring(0, equals)
            val value = if (equals < 0) "" else pair.substring(equals + 1)
            result.putIfAbsent(decode(key), decode(value))
        }
        return result
    }

    private fun decode(text: String): String = java.net.URLDecoder.decode(text, Charsets.UTF_8)
}
