package ktron

import ioxide.IoxideServer
import ioxide.Reactor
import ioxide.ServerConfig
import ioxide.TcpConnection
import ioxide.TcpOptions
import ioxide.tls.TlsOptions
import ioxide.tls.TlsService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * TLS on a second port: the same routes, behind BoringSSL (bundled with ioxide) or kernel TLS.
 *
 * @property kernelTx the kernel encrypts outbound records (TLS 1.3 only, one suite, no tickets).
 * @property kernelRx the kernel decrypts inbound records too; experimental, and requires [kernelTx].
 */
public class KtronTls(
    public val certificatePath: String,
    public val keyPath: String,
    public val port: Int = 8443,
    public val kernelTx: Boolean = false,
    public val kernelRx: Boolean = false,
    public val alpn: List<String> = listOf("http/1.1"),
)

/**
 * @property reactors io_uring reactors, one thread and one ring each: by default one per CPU the process may use.
 * @property maxHeadBytes the largest request line and headers accepted; past it, 431.
 * @property maxBodyBytes the largest request body accepted, buffered whole; past it, 413.
 * @property dateHeader send a Date header, made once a second per reactor.
 */
public class KtronOptions(
    public val port: Int = 8080,
    public val reactors: Int = Runtime.getRuntime().availableProcessors(),
    public val tls: KtronTls? = null,
    public val maxHeadBytes: Int = 32 * 1024,
    public val maxBodyBytes: Int = 1 shl 20,
    public val dateHeader: Boolean = false,
)

/**
 * A Ktron application: routes, middleware, and the options it runs with.
 *
 * ```
 * Ktron()
 *     .get("/hello/{name}") { ctx -> ctx.response.text("hello " + ctx.request.param("name")) }
 *     .getAsync("/later") { ctx -> ctx.delay(10); ctx.response.text("later") }
 *     .run()
 * ```
 *
 * Handlers run on the reactor thread that took the request, and a suspending one resumes there: there is no
 * thread pool. Register everything before [start].
 */
public class Ktron(public val options: KtronOptions = KtronOptions()) {
    internal val router = Router()
    private val middleware = ArrayList<Middleware>()
    internal var middlewareArray: Array<Middleware> = emptyArray()
        private set
    private var started = false

    /** What a handler or middleware that throws is reported to; the request gets a 500 and the connection closes. */
    public var onFault: (Throwable) -> Unit = { e -> System.err.println("[ktron] request failed: $e") }

    public fun use(middleware: Middleware): Ktron {
        checkNotStarted()
        this.middleware.add(middleware)
        return this
    }

    public fun get(path: String, handler: Handler): Ktron = route(Method.GET, path, handler)
    public fun post(path: String, handler: Handler): Ktron = route(Method.POST, path, handler)
    public fun put(path: String, handler: Handler): Ktron = route(Method.PUT, path, handler)
    public fun delete(path: String, handler: Handler): Ktron = route(Method.DELETE, path, handler)
    public fun patch(path: String, handler: Handler): Ktron = route(Method.PATCH, path, handler)
    public fun options(path: String, handler: Handler): Ktron = route(Method.OPTIONS, path, handler)

    public fun getAsync(path: String, handler: AsyncHandler): Ktron = routeAsync(Method.GET, path, handler)
    public fun postAsync(path: String, handler: AsyncHandler): Ktron = routeAsync(Method.POST, path, handler)
    public fun putAsync(path: String, handler: AsyncHandler): Ktron = routeAsync(Method.PUT, path, handler)
    public fun deleteAsync(path: String, handler: AsyncHandler): Ktron = routeAsync(Method.DELETE, path, handler)
    public fun patchAsync(path: String, handler: AsyncHandler): Ktron = routeAsync(Method.PATCH, path, handler)

    /** [method] is a [Method] constant. */
    public fun route(method: Int, path: String, handler: Handler): Ktron = add(Route(method, path, handler, null, paramNames(path)))

    public fun routeAsync(method: Int, path: String, handler: AsyncHandler): Ktron = add(Route(method, path, null, handler, paramNames(path)))

    private fun add(route: Route): Ktron {
        checkNotStarted()
        router.add(route.method, route.pattern, route)
        return this
    }

    private fun checkNotStarted() = check(!started) { "Ktron is running: routes and middleware are fixed once it starts" }

    internal fun reportFault(e: Throwable) = onFault(e)

    /** Starts every reactor and returns once they listen; [KtronServer.join] waits for them to stop. */
    public fun start(): KtronServer {
        checkNotStarted()
        started = true
        middlewareArray = middleware.toTypedArray()

        val tls = options.tls
        val config = ServerConfig(
            reactorCount = options.reactors,
            tcp = TcpOptions(port = options.port, extraPorts = if (tls != null) intArrayOf(tls.port) else IntArray(0)),
        )
        val tlsOptions = tls?.let {
            TlsOptions(certificatePath = it.certificatePath, keyPath = it.keyPath, alpn = it.alpn, kernelTx = it.kernelTx, kernelRx = it.kernelRx)
        }
        val states = arrayOfNulls<ReactorState>(options.reactors)
        val listening = CountDownLatch(options.reactors)

        val server = IoxideServer(
            config,
            onStart = { reactor: Reactor ->
                states[reactor.id] = ReactorState(reactor, this, tlsOptions?.let { TlsService.start(reactor, it) })
                listening.countDown()
            },
            handler = { conn: TcpConnection -> states[conn.reactor.id]!!.acquire().serve(conn) },
        ).start()
        // Returns listening: every reactor has bound its ports and built its state. A reactor that failed to start
        // never counts down, and its fault is on stderr.
        check(listening.await(30, TimeUnit.SECONDS)) { "Ktron's reactors did not all start" }
        return KtronServer(server)
    }

    /** [start], then wait for the server to stop. */
    public fun run() {
        start().join()
    }

    private companion object {
        // The parameter names of a pattern, in order: {name}, :name and {*name}.
        fun paramNames(pattern: String): Array<String> = pattern.split('/').mapNotNull { segment ->
            when {
                segment.startsWith("{*") && segment.endsWith("}") -> segment.substring(2, segment.length - 1)
                segment.startsWith("{") && segment.endsWith("}") -> segment.substring(1, segment.length - 1)
                segment.startsWith(":") -> segment.substring(1)
                else -> null
            }
        }.toTypedArray()
    }
}

/** A running Ktron. */
public class KtronServer internal constructor(private val server: IoxideServer) : AutoCloseable {
    /** The port plaintext HTTP listens on: [KtronOptions.port], or the one the kernel picked for port 0. */
    public val port: Int get() = server.reactors[0].listenPorts[0]

    /** Waits until every reactor has stopped. */
    public fun join() {
        server.join()
    }

    /** Stops every reactor and waits for them. */
    override fun close() {
        server.close()
    }
}
