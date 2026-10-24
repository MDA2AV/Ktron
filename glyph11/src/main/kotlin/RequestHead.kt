package ktron.glyph11

/**
 * A request head parsed by [Glyph11.parse], as offsets into the array it was parsed from: nothing is copied, and
 * once the arrays have grown to the largest request seen nothing is allocated per parse. One per connection,
 * reused for every request; valid until the next parse or until the input bytes move.
 */
public class RequestHead {
    /** The head's bytes, CRLFCRLF included: the body starts this far from the parse offset. */
    public var headLength: Int = 0
        internal set

    public var methodStart: Int = 0
        internal set
    public var methodLength: Int = 0
        internal set

    /** The target's path, without its query. */
    public var pathStart: Int = 0
        internal set
    public var pathLength: Int = 0
        internal set

    /** The raw query, without its '?': zero-length when the target has none. */
    public var queryStart: Int = 0
        internal set
    public var queryLength: Int = 0
        internal set

    public var versionStart: Int = 0
        internal set
    public var versionLength: Int = 0
        internal set

    /** How the body is framed: [BODY_NONE], [BODY_LENGTH] ([contentLength] bytes) or [BODY_CHUNKED]. */
    public var bodyFraming: Int = BODY_NONE
        internal set
    public var contentLength: Long = 0
        internal set

    public var headerCount: Int = 0
        internal set
    private var headers = IntArray(4 * 16)

    public fun headerNameStart(i: Int): Int = headers[4 * i]
    public fun headerNameLength(i: Int): Int = headers[4 * i + 1]
    public fun headerValueStart(i: Int): Int = headers[4 * i + 2]
    public fun headerValueLength(i: Int): Int = headers[4 * i + 3]

    public var queryCount: Int = 0
        internal set
    private var query = IntArray(4 * 8)

    public fun queryKeyStart(i: Int): Int = query[4 * i]
    public fun queryKeyLength(i: Int): Int = query[4 * i + 1]
    public fun queryValueStart(i: Int): Int = query[4 * i + 2]
    public fun queryValueLength(i: Int): Int = query[4 * i + 3]

    internal fun addHeader(nameStart: Int, nameLength: Int, valueStart: Int, valueLength: Int) {
        val at = 4 * headerCount
        if (at + 4 > headers.size) headers = headers.copyOf(headers.size * 2)
        headers[at] = nameStart
        headers[at + 1] = nameLength
        headers[at + 2] = valueStart
        headers[at + 3] = valueLength
        headerCount++
    }

    internal fun addQuery(keyStart: Int, keyLength: Int, valueStart: Int, valueLength: Int) {
        val at = 4 * queryCount
        if (at + 4 > query.size) query = query.copyOf(query.size * 2)
        query[at] = keyStart
        query[at + 1] = keyLength
        query[at + 2] = valueStart
        query[at + 3] = valueLength
        queryCount++
    }

    public fun reset() {
        headLength = 0
        methodStart = 0
        methodLength = 0
        pathStart = 0
        pathLength = 0
        queryStart = 0
        queryLength = 0
        versionStart = 0
        versionLength = 0
        bodyFraming = BODY_NONE
        contentLength = 0
        headerCount = 0
        queryCount = 0
    }

    /** The index of the first header named [lowerName] (ASCII lower case), compared case-insensitively; -1 if none. */
    public fun findHeader(input: ByteArray, lowerName: ByteArray): Int {
        for (i in 0 until headerCount) {
            if (headerNameLength(i) == lowerName.size && equalsIgnoreCase(input, headerNameStart(i), lowerName)) return i
        }
        return -1
    }

    /** The index of the first query parameter whose key is exactly [key]; -1 if none. */
    public fun findQuery(input: ByteArray, key: ByteArray): Int {
        for (i in 0 until queryCount) {
            if (queryKeyLength(i) == key.size && regionEquals(input, queryKeyStart(i), key)) return i
        }
        return -1
    }

    public companion object {
        public const val BODY_NONE: Int = 0
        public const val BODY_LENGTH: Int = 1
        public const val BODY_CHUNKED: Int = 2

        internal fun equalsIgnoreCase(input: ByteArray, at: Int, lower: ByteArray): Boolean {
            for (i in lower.indices) {
                var c = input[at + i].toInt()
                if (c in 'A'.code..'Z'.code) c += 32
                if (c != lower[i].toInt()) return false
            }
            return true
        }

        internal fun regionEquals(input: ByteArray, at: Int, expected: ByteArray): Boolean {
            for (i in expected.indices) if (input[at + i] != expected[i]) return false
            return true
        }
    }
}

/** A growable byte buffer the chunked decoder appends a body's payload to. */
public class ByteBuilder(initialCapacity: Int = 0) {
    public var array: ByteArray = ByteArray(initialCapacity)
        private set
    public var length: Int = 0
        private set

    public fun reset() {
        length = 0
    }

    public fun append(source: ByteArray, offset: Int, count: Int) {
        if (array.size - length < count) array = array.copyOf(maxOf(length + count, array.size * 2, 64))
        source.copyInto(array, length, offset, offset + count)
        length += count
    }
}
