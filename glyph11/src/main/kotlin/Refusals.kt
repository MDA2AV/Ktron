package ktron.glyph11

/**
 * Every reason the offset API refuses a request or a chunked body for, each an [HttpParseException] made once: the
 * exceptions are stackless and immutable (no cause, no suppression), so one instance per reason serves every thread
 * and a refusal allocates nothing. The parsers return a reason as a negative code; [Glyph11] throws its exception.
 */
internal object Refusals {
    private val exceptions = ArrayList<HttpParseException>()

    private fun reason(message: String, status: Int = 400): Int {
        exceptions += HttpParseException(message, status)
        return -exceptions.size
    }

    /** The exception for the negative [code]. */
    fun exception(code: Int): HttpParseException = exceptions[-code - 1]

    // ---- the request head: UltraHardenedParser.tryExtractFullHeader's messages and statuses ----
    @JvmField val TOTAL_TOO_LARGE = reason("Total header size exceeds limit.", 431)
    @JvmField val REQUEST_LINE = reason("Invalid HTTP/1.1 request line.")
    @JvmField val BARE_LF = reason("Bare LF detected; only CRLF line endings are allowed.")
    @JvmField val MISSING_METHOD = reason("Invalid request line: missing method.")
    @JvmField val MISSING_VERSION = reason("Invalid request line: missing version.")
    @JvmField val MULTIPLE_SPACES = reason("Multiple spaces in request line.")
    @JvmField val METHOD_LENGTH = reason("Method length exceeds limit.")
    @JvmField val METHOD_TOKEN = reason("Method contains invalid token characters.")
    @JvmField val CONNECT = reason("CONNECT method is not allowed on origin servers.")
    @JvmField val URL_TOO_LONG = reason("URL length exceeds limit.", 431)
    @JvmField val TARGET_CHARACTERS = reason("Request-target contains invalid characters.")
    @JvmField val VERSION = reason("Invalid HTTP version.", 505)
    @JvmField val TOO_MANY_PARAMETERS = reason("Query parameter count exceeds limit.", 431)
    @JvmField val FRAGMENT = reason("Fragment indicator (#) in request-target.")
    @JvmField val BACKSLASH = reason("Backslash in request path.")
    @JvmField val DOUBLE_ENCODING = reason("Double-encoded percent (%25) in path.")
    @JvmField val ENCODED_NULL = reason("Encoded null byte (%00) in path.")
    @JvmField val DOT_SEGMENT = reason("Dot segment in request path.")
    @JvmField val ASTERISK_FORM = reason("Asterisk-form request-target is only valid for OPTIONS.")
    @JvmField val HEADERS = reason("Invalid headers.")
    @JvmField val OBS_FOLD = reason("Obsolete line folding (obs-fold) is not allowed.")
    @JvmField val MISSING_COLON = reason("Malformed header line: missing colon.")
    @JvmField val EMPTY_NAME = reason("Header name is empty.")
    @JvmField val SPACE_BEFORE_COLON = reason("Whitespace between header name and colon is not allowed.")
    @JvmField val NAME_TOO_LONG = reason("Header name length exceeds limit.", 431)
    @JvmField val NAME_TOKEN = reason("Header name contains invalid token characters.")
    @JvmField val VALUE_TOO_LONG = reason("Header value length exceeds limit.", 431)
    @JvmField val VALUE_CHARACTERS = reason("Header value contains invalid characters.")
    @JvmField val TOO_MANY_HEADERS = reason("Header count exceeds limit.", 431)
    @JvmField val CONTENT_LENGTH_FORMAT = reason("Invalid Content-Length format.")
    @JvmField val CONTENT_LENGTH_COMMAS = reason("Conflicting comma-separated Content-Length values.")
    @JvmField val CONTENT_LENGTHS = reason("Conflicting Content-Length headers.")
    @JvmField val TRANSFER_ENCODING = reason("Invalid Transfer-Encoding value; only 'chunked' is accepted.")
    @JvmField val HOST_FORMAT = reason("Invalid Host header: contains '@' or '/'.")
    @JvmField val TE_AND_CL = reason("Both Transfer-Encoding and Content-Length are present.")
    @JvmField val HOST_COUNT = reason("Request must have exactly one Host header.")

    // ---- the chunked body: ChunkedBodyStream's messages ----
    @JvmField val CHUNK_LEADING_WHITESPACE = reason("Leading whitespace in chunk size.")
    @JvmField val CHUNK_NEGATIVE = reason("Negative chunk size.")
    @JvmField val CHUNK_HEX_PREFIX = reason("Hex prefix in chunk size.")
    @JvmField val CHUNK_OVERFLOW = reason("Chunk size overflow.")
    @JvmField val CHUNK_SIZE_CHARACTER = reason("Invalid character in chunk size.")
    @JvmField val CHUNK_UNDERSCORE = reason("Underscore in chunk size.")
    @JvmField val CHUNK_SIZE_BARE_LF = reason("Bare LF after chunk size.")
    @JvmField val CHUNK_SIZE_CRLF = reason("Missing CRLF after chunk size.")
    @JvmField val CHUNK_EXTENSION_TOO_LARGE = reason("Chunk extension too large.")
    @JvmField val CHUNK_EXTENSION_NUL = reason("NUL byte in chunk extension.")
    @JvmField val CHUNK_EXTENSION_BARE_LF = reason("Bare LF in chunk extension.")
    @JvmField val CHUNK_BARE_SEMICOLON = reason("Bare semicolon with no chunk extension name.")
    @JvmField val CHUNK_DATA_BARE_LF = reason("Bare LF after chunk data.")
    @JvmField val CHUNK_DATA_CRLF = reason("Missing CRLF after chunk data.")
    @JvmField val TRAILER_BARE_LF = reason("Bare LF in chunked trailer.")
}
