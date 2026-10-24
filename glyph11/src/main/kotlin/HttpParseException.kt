package ktron.glyph11

/**
 * Thrown when an HTTP/1.1 request violates protocol rules during parsing or semantic validation.
 *
 * @param message A description of the protocol violation.
 * @param statusCode HTTP status code to return (default 400).
 */
// Stackless: a refused request is an ordinary outcome, and filling in a stack trace on every one would cost more
// than the parse.
public open class HttpParseException(message: String, public val statusCode: Int = 400) : RuntimeException(message, null, false, false) {

    /** True when the error is a size/count limit breach (-> 431), false for structural errors (-> 400). */
    public val isLimitViolation: Boolean
        get() = statusCode != 400

}
