package ktron

/** One registered endpoint: its handler, sync or suspending, and the names of its path parameters in order. */
internal class Route(
    val method: Int,
    val pattern: String,
    val handler: Handler?,
    val asyncHandler: AsyncHandler?,
    val paramNames: Array<String>,
)

/** The path parameters of the matched route, as offsets into the request bytes: nothing is copied per match. */
public class RouteParams internal constructor() {
    public var count: Int = 0
        internal set
    internal val starts = IntArray(MAX)
    internal val lengths = IntArray(MAX)
    internal var names: Array<String> = NO_NAMES

    /** The index of the parameter called [name], or -1. */
    public fun indexOf(name: String): Int {
        val n = names
        for (i in 0 until count) if (n[i] == name) return i
        return -1
    }

    public fun start(index: Int): Int = starts[index]
    public fun length(index: Int): Int = lengths[index]

    internal companion object {
        const val MAX = 16
        val NO_NAMES = emptyArray<String>()
    }
}

/**
 * A trie over path segments, as Apex and Crest have it: literal children first, then one parameter child, then a
 * catch-all, with backtracking when a branch leads nowhere; each node holds its handlers by method. Matching walks
 * the request's bytes in place and allocates nothing.
 *
 * Patterns: `/users/{id}` or `/users/:id` for a parameter, `/files/{*path}` for the rest of the path (last segment
 * only). A trailing slash is the same route as none.
 */
internal class Router {
    private class Node {
        var keys: Array<ByteArray> = NO_KEYS
        var children: Array<Node?> = NO_NODES
        var param: Node? = null
        var paramName: String? = null
        var catchAll: Node? = null
        var catchAllName: String? = null
        val routes = arrayOfNulls<Route>(Method.COUNT)
        var hasRoutes = false
        var allow: ByteArray = NO_ALLOW
    }

    private val root = Node()

    fun add(method: Int, pattern: String, route: Route) {
        require(pattern.startsWith("/")) { "A route pattern starts with '/': $pattern" }
        val segments = pattern.substring(1).trimEnd('/').split('/').filter { it.isNotEmpty() }
        var node = root
        for ((i, segment) in segments.withIndex()) {
            node = when {
                segment.startsWith("{*") && segment.endsWith("}") -> {
                    require(i == segments.lastIndex) { "A catch-all is the last segment: $pattern" }
                    val name = segment.substring(2, segment.length - 1)
                    claimName(node.catchAllName, name, pattern)
                    node.catchAllName = name
                    node.catchAll ?: Node().also { node.catchAll = it }
                }
                segment.startsWith("{") && segment.endsWith("}") || segment.startsWith(":") -> {
                    val name = if (segment.startsWith(":")) segment.substring(1) else segment.substring(1, segment.length - 1)
                    claimName(node.paramName, name, pattern)
                    node.paramName = name
                    node.param ?: Node().also { node.param = it }
                }
                else -> literalChild(node, segment.toByteArray())
            }
        }
        require(node.routes[method] == null) { "${Method.name(method)} $pattern is registered twice" }
        node.routes[method] = route
        node.hasRoutes = true
        node.allow = allowLine(node.routes)
    }

    // Two patterns naming one parameter slot differently would leave one handler reading the other's name.
    private fun claimName(existing: String?, name: String, pattern: String) {
        require(name.isNotEmpty()) { "An empty parameter name in $pattern" }
        require(existing == null || existing == name) { "$pattern names a parameter '$name' where another route names it '$existing'" }
    }

    private fun literalChild(node: Node, key: ByteArray): Node {
        for (i in node.keys.indices) if (node.keys[i].contentEquals(key)) return node.children[i]!!
        val child = Node()
        node.keys = node.keys + key
        node.children = node.children + child
        return child
    }

    private fun allowLine(routes: Array<Route?>): ByteArray {
        val methods = (0 until Method.COUNT).filter { routes[it] != null || (it == Method.HEAD && routes[Method.GET] != null) }
        return ("Allow: " + methods.joinToString(", ") { Method.name(it) } + "\r\n").toByteArray()
    }

    /** Why the last [match] found nothing: 404, or 405 with [allowed] as its Allow line. */
    var missStatus = 404
        private set
    var allowed: ByteArray = NO_ALLOW
        private set

    /** The route for [method] at the path `[start, start + length)` of [input], its parameters in [params]; null on a miss. */
    fun match(input: ByteArray, start: Int, length: Int, method: Int, params: RouteParams): Route? {
        params.count = 0
        var end = start + length
        if (length > 1 && input[end - 1] == SLASH) end--   // a trailing slash is the same route
        val node = walk(root, input, start + 1, end, params)
        if (node == null) {
            missStatus = 404
            return null
        }
        val route = node.routes[method] ?: if (method == Method.HEAD) node.routes[Method.GET] else null
        if (route == null) {
            missStatus = 405
            allowed = node.allow
            return null
        }
        params.names = route.paramNames
        return route
    }

    // The node the path ends at, trying literals, then the parameter, then the catch-all at each segment.
    private fun walk(node: Node, input: ByteArray, at: Int, end: Int, params: RouteParams): Node? {
        if (at >= end) return if (node.hasRoutes) node else null

        var segmentEnd = at
        while (segmentEnd < end && input[segmentEnd] != SLASH) segmentEnd++
        val segmentLength = segmentEnd - at
        val next = segmentEnd + 1

        val keys = node.keys
        for (i in keys.indices) {
            val key = keys[i]
            if (key.size == segmentLength && regionEquals(input, at, key)) {
                walk(node.children[i]!!, input, next, end, params)?.let { return it }
            }
        }

        val param = node.param
        if (param != null && segmentLength > 0 && params.count < RouteParams.MAX) {
            val slot = params.count
            params.starts[slot] = at
            params.lengths[slot] = segmentLength
            params.count = slot + 1
            walk(param, input, next, end, params)?.let { return it }
            params.count = slot   // backtrack
        }

        val rest = node.catchAll
        if (rest != null && rest.hasRoutes && params.count < RouteParams.MAX) {
            val slot = params.count
            params.starts[slot] = at
            params.lengths[slot] = end - at
            params.count = slot + 1
            return rest
        }
        return null
    }

    private companion object {
        const val SLASH = '/'.code.toByte()
        val NO_KEYS = emptyArray<ByteArray>()
        val NO_NODES = emptyArray<Node?>()
        val NO_ALLOW = ByteArray(0)

        fun regionEquals(input: ByteArray, at: Int, key: ByteArray): Boolean {
            for (i in key.indices) if (input[at + i] != key[i]) return false
            return true
        }
    }
}
