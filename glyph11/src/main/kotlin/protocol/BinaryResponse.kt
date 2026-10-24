package ktron.glyph11.protocol

import ktron.glyph11.EmptyBuffer
import java.nio.ByteBuffer

/**
 * Holds the parsed components of an HTTP/1.1 response header.
 *
 * All byte-level fields are read-only [ByteBuffer] slices of the original input (their content is
 * exactly the field bytes).
 *
 * Reuse instances across responses by calling [clear] between parses. Call [close] when the
 * instance is no longer needed to release the storage used by [headers].
 */
public class BinaryResponse : AutoCloseable {

    private val headerList = KeyValueList()

    /** HTTP version string, e.g. "HTTP/1.1". Set by UltraHardenedParser only. */
    public var version: ByteBuffer = EmptyBuffer
        internal set

    /**
     * Status code as the three bytes that arrived, e.g. "404". [status] is the same
     * value already parsed, and is what callers normally want.
     */
    public var statusCode: ByteBuffer = EmptyBuffer
        internal set

    /** Status code as an integer, 100-599. */
    public var status: Int = 0
        internal set

    /**
     * Reason phrase, e.g. "Not Found". Empty is legal and common: HTTP/2 and HTTP/3 have no
     * reason phrase at all, so anything translating from them emits none.
     */
    public var reasonPhrase: ByteBuffer = EmptyBuffer
        internal set

    /** Parsed HTTP headers as key-value pairs. */
    public val headers: KeyValueList
        get() = headerList

    /** Response body bytes. Not populated by the header parser. */
    public var body: ByteBuffer = EmptyBuffer
        internal set

    /**
     * Resets the response for reuse. Clears headers but keeps the underlying
     * storage allocated.
     */
    public fun clear() {
        version = EmptyBuffer
        statusCode = EmptyBuffer
        status = 0
        reasonPhrase = EmptyBuffer
        body = EmptyBuffer

        headerList.clear()
    }

    /**
     * Releases the storage used by [headers].
     * The instance should not be used after closing.
     */
    override fun close() {
        headerList.close()
    }

}
