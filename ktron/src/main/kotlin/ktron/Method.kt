package ktron

/** Request methods as small integers, which is how routes index their handlers. */
public object Method {
    public const val GET: Int = 0
    public const val HEAD: Int = 1
    public const val POST: Int = 2
    public const val PUT: Int = 3
    public const val DELETE: Int = 4
    public const val PATCH: Int = 5
    public const val OPTIONS: Int = 6
    internal const val COUNT: Int = 7

    /** A method Ktron does not route: answered with 501. */
    public const val UNKNOWN: Int = -1

    internal val names: Array<String> = arrayOf("GET", "HEAD", "POST", "PUT", "DELETE", "PATCH", "OPTIONS")

    public fun name(method: Int): String = if (method in 0 until COUNT) names[method] else "UNKNOWN"

    /** The method spelled by the bytes at [start] (case-sensitive, as RFC 9110 9.1 says), or [UNKNOWN]. */
    internal fun of(input: ByteArray, start: Int, length: Int): Int {
        val first = input[start].toInt()
        return when (length) {
            3 -> when {
                first == 'G'.code && input[start + 1] == 'E'.code.toByte() && input[start + 2] == 'T'.code.toByte() -> GET
                first == 'P'.code && input[start + 1] == 'U'.code.toByte() && input[start + 2] == 'T'.code.toByte() -> PUT
                else -> UNKNOWN
            }
            4 -> when {
                first == 'P'.code && matches(input, start, POST_BYTES) -> POST
                first == 'H'.code && matches(input, start, HEAD_BYTES) -> HEAD
                else -> UNKNOWN
            }
            5 -> if (first == 'P'.code && matches(input, start, PATCH_BYTES)) PATCH else UNKNOWN
            6 -> if (first == 'D'.code && matches(input, start, DELETE_BYTES)) DELETE else UNKNOWN
            7 -> if (first == 'O'.code && matches(input, start, OPTIONS_BYTES)) OPTIONS else UNKNOWN
            else -> UNKNOWN
        }
    }

    internal fun of(name: String): Int {
        val index = names.indexOf(name.uppercase())
        require(index >= 0) { "Ktron routes ${names.joinToString()}, not $name" }
        return index
    }

    private val POST_BYTES = "POST".toByteArray()
    private val HEAD_BYTES = "HEAD".toByteArray()
    private val PATCH_BYTES = "PATCH".toByteArray()
    private val DELETE_BYTES = "DELETE".toByteArray()
    private val OPTIONS_BYTES = "OPTIONS".toByteArray()

    private fun matches(input: ByteArray, start: Int, expected: ByteArray): Boolean {
        for (i in expected.indices) if (input[start + i] != expected[i]) return false
        return true
    }
}
