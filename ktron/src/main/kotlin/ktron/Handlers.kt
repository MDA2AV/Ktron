package ktron

/** A handler that answers without waiting. It is the zero-allocation path: no coroutine is involved at all. */
public fun interface Handler {
    public fun handle(ctx: Context)
}

/**
 * A handler that may suspend: for [Context.delay], another service, anything that completes on the reactor's ring.
 * It resumes inline on the reactor thread that took the request.
 */
public fun interface AsyncHandler {
    public suspend fun handle(ctx: Context)
}

/**
 * Runs around every request, in the order the middleware were added. [before] runs on the way in: returning false
 * short-circuits, sending the response it left and skipping the later middleware and the handler. [after] runs on
 * the way out, in reverse order, for every middleware whose [before] ran - so it sees the finished response and may
 * change it, as compression does.
 *
 * Both are synchronous, so a request through any number of middleware allocates nothing; the handler between them
 * may still suspend.
 */
public interface Middleware {
    public fun before(ctx: Context): Boolean = true

    public fun after(ctx: Context) {}
}
