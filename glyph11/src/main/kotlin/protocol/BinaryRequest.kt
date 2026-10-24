package ktron.glyph11.protocol

import ktron.glyph11.EmptyBuffer
import ktron.glyph11.ViewPool
import java.nio.ByteBuffer

/**
 * Holds the parsed components of an HTTP/1.1 request header.
 *
 * All byte-level fields are read-only [ByteBuffer] views on the original input (their content, from
 * position to limit, is exactly the field bytes).
 *
 * Reuse instances across requests by calling [clear] between parses. The views on array-backed input
 * are recycled by [clear] and re-pointed to the fields of the next request, so fields that need to
 * outlive [clear] have to be copied. Call [close] when the instance is no longer needed to release
 * the storage used by [headers], [queryParameters] and the views.
 */
public class BinaryRequest : AutoCloseable {

    private val headerList = KeyValueList()

    private val queryList = KeyValueList()

    /** The views handed out for the fields of the parsed request. */
    internal val views = ViewPool()

    /** HTTP version string, e.g. "HTTP/1.1". Set by UltraHardenedParser only. */
    public var version: ByteBuffer = EmptyBuffer
        internal set

    /** HTTP method, e.g. "GET", "POST". */
    public var method: ByteBuffer = EmptyBuffer
        internal set

    /** Request path without the query string, e.g. "/api/users". */
    public var path: ByteBuffer = EmptyBuffer
        internal set

    /** Parsed query parameters as key-value pairs. */
    public val queryParameters: KeyValueList
        get() = queryList

    /** Parsed HTTP headers as key-value pairs. */
    public val headers: KeyValueList
        get() = headerList

    /** Request body bytes. Not populated by the header parser. */
    public var body: ByteBuffer = EmptyBuffer
        internal set

    /**
     * Resets the request for reuse. Clears headers and query parameters
     * but keeps the underlying storage allocated. The views handed out for
     * the fields so far are recycled by the next parse.
     */
    public fun clear() {
        version = EmptyBuffer
        method = EmptyBuffer
        path = EmptyBuffer
        body = EmptyBuffer

        headerList.clear()
        queryList.clear()

        views.recycle()
    }

    /**
     * Releases the storage used by [headers], [queryParameters] and the views.
     * The instance should not be used after closing.
     */
    override fun close() {
        headerList.close()
        queryList.close()

        views.release()
    }

}
