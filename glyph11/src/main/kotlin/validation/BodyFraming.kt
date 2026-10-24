package ktron.glyph11.validation

/**
 * Describes the body framing strategy indicated by an HTTP/1.1 message's headers.
 */
public enum class BodyFraming {

    /** No body (no Transfer-Encoding, no Content-Length, or Content-Length == 0). */
    None,

    /** Known-size body - use [BodyFramingResult.contentLength]. */
    ContentLength,

    /** Chunked transfer-encoding - use [ktron.glyph11.parser.ChunkedBodyStream]. */
    Chunked,

    /**
     * Responses only: the body runs until the peer closes the connection (RFC 9112 6.3). A
     * request can never be framed this way - a client that closed its send side to delimit a body
     * would have no way left to read the answer.
     */
    UntilClose,

}

/**
 * Result of [BodyFramingDetector.detectBodyFraming]:
 * the framing kind plus the content length when applicable.
 */
public class BodyFramingResult private constructor(

    /** The detected framing kind. */
    public val framing: BodyFraming,

    /**
     * The parsed Content-Length value. Meaningful only when
     * [framing] is [BodyFraming.ContentLength].
     */
    public val contentLength: Long,

) {

    public companion object {

        /** No body. */
        public val NoBody: BodyFramingResult
            get() = BodyFramingResult(BodyFraming.None, 0)

        /** Content-Length-framed body. */
        public fun forContentLength(length: Long): BodyFramingResult = BodyFramingResult(BodyFraming.ContentLength, length)

        /** Chunked transfer-encoding. */
        public val ForChunked: BodyFramingResult
            get() = BodyFramingResult(BodyFraming.Chunked, 0)

        /** Body delimited by connection close - responses only. */
        public val ForUntilClose: BodyFramingResult
            get() = BodyFramingResult(BodyFraming.UntilClose, 0)

    }

}
