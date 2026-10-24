package ktron

/** Response status lines and content types, encoded once. */
internal object Wire {
    private val reasons = mapOf(
        100 to "Continue", 101 to "Switching Protocols",
        200 to "OK", 201 to "Created", 202 to "Accepted", 204 to "No Content", 206 to "Partial Content",
        301 to "Moved Permanently", 302 to "Found", 303 to "See Other", 304 to "Not Modified", 307 to "Temporary Redirect",
        308 to "Permanent Redirect",
        400 to "Bad Request", 401 to "Unauthorized", 403 to "Forbidden", 404 to "Not Found", 405 to "Method Not Allowed",
        406 to "Not Acceptable", 408 to "Request Timeout", 409 to "Conflict", 411 to "Length Required",
        413 to "Content Too Large", 414 to "URI Too Long", 415 to "Unsupported Media Type", 422 to "Unprocessable Content",
        429 to "Too Many Requests", 431 to "Request Header Fields Too Large",
        500 to "Internal Server Error", 501 to "Not Implemented", 502 to "Bad Gateway", 503 to "Service Unavailable",
        505 to "HTTP Version Not Supported",
    )

    private val lines = arrayOfNulls<ByteArray>(600)

    /** "HTTP/1.1 200 OK\r\n" for [status]; any status in 100..599 works, unnamed ones with an empty reason. */
    fun statusLine(status: Int): ByteArray {
        require(status in 100..599) { "A status is 100..599, not $status" }
        return lines[status] ?: "HTTP/1.1 $status ${reasons[status] ?: ""}\r\n".toByteArray().also { lines[status] = it }
    }

    init {
        for (status in reasons.keys) statusLine(status)
    }

    val CONTENT_TYPE = "Content-Type: ".toByteArray()
    val CONTENT_LENGTH = "Content-Length: ".toByteArray()
    val CONNECTION_CLOSE = "Connection: close\r\n".toByteArray()
    val CRLF = "\r\n".toByteArray()
    val CONTINUE = "HTTP/1.1 100 Continue\r\n\r\n".toByteArray()

    val TEXT_PLAIN = "text/plain".toByteArray()
    val APPLICATION_JSON = "application/json".toByteArray()
    val OCTET_STREAM = "application/octet-stream".toByteArray()
    val HTML = "text/html; charset=utf-8".toByteArray()

    /** The bare response a refused request gets, closing the connection. */
    fun refusal(status: Int): ByteArray = ("HTTP/1.1 $status ${reasons[status] ?: ""}\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").toByteArray()
}
