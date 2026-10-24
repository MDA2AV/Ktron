package ktron.glyph11.parser

/**
 * Configurable resource limits.
 *
 * Every field has a safe default via [Default]. Customize with [copy]:
 * ```
 * val strict = ParserLimits.Default.copy(maxHeaderCount = 50)
 * ```
 *
 * @param maxHeaderCount Maximum number of header fields allowed (default 100).
 * @param maxHeaderNameLength Maximum length of a single header name in bytes (default 256).
 * @param maxHeaderValueLength Maximum length of a single header value in bytes (default 8192).
 * @param maxUrlLength Maximum length of the request URL in bytes (default 8192).
 * @param maxQueryParameterCount Maximum number of query string parameters (default 128).
 * @param maxMethodLength Maximum length of the HTTP method token in bytes (default 16).
 * @param maxReasonPhraseLength Maximum length of a response's reason phrase in bytes (default 512).
 * Responses only; a request has no equivalent field.
 * @param maxTotalHeaderBytes Maximum total size of the header block including request line and
 * terminators (default 1048576).
 */
public data class ParserLimits(
    val maxHeaderCount: Int = 100,
    val maxHeaderNameLength: Int = 256,
    val maxHeaderValueLength: Int = 8192,
    val maxUrlLength: Int = 8192,
    val maxQueryParameterCount: Int = 128,
    val maxMethodLength: Int = 16,
    val maxReasonPhraseLength: Int = 512,
    val maxTotalHeaderBytes: Int = 1_048_576,
) {

    public companion object {

        /** A [ParserLimits] instance with safe production defaults. */
        public val Default: ParserLimits = ParserLimits()

    }

}
