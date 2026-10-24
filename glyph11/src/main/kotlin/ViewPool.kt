package ktron.glyph11

import java.nio.ByteBuffer

/**
 * Recycles the read-only views a parser hands out for the fields of a request, so that parsing further requests from
 * the same buffer (such as the buffer of a connection) does not allocate. GenHTTP hands out `ReadOnlyMemory<byte>`
 * structs, which are free; on the JVM, every view is an object.
 *
 * A view is bound to the array it has been created on (only its position and limit can change), so the pool serves
 * views on a single array and starts over if another array is parsed. Views handed out stay untouched until [recycle]
 * is called, the next views are then served by re-pointing the existing objects.
 */
internal class ViewPool {

    private var array: ByteArray? = null

    private var base: ByteBuffer = EmptyBuffer

    private val views = arrayOfNulls<ByteBuffer>(Capacity)

    private var created = 0

    private var used = 0

    /**
     * Returns a read-only view on `[offset, offset + length)` of [array] (absolute indexes, the position of the view
     * is [offset]), or null if all views of the pool are in use.
     */
    fun view(array: ByteArray, offset: Int, length: Int): ByteBuffer? {
        if (array !== this.array) {
            // views handed out on the previous array are dropped, not re-pointed, so they stay valid
            this.array = array
            base = ByteBuffer.wrap(array).asReadOnlyBuffer()

            views.fill(null, 0, created)
            created = 0
            used = 0
        }

        if (used == Capacity) {
            return null
        }

        val view = views[used] ?: base.duplicate().also {
            views[used] = it
            created++
        }

        used++

        // the limit first, so that the position is always within the limit
        view.limit(offset + length)
        view.position(offset)

        return view
    }

    /**
     * Allows the views handed out so far to be re-pointed by the next calls to [view].
     */
    fun recycle() {
        used = 0
    }

    /**
     * Drops all views and the reference to the array.
     */
    fun release() {
        array = null
        base = EmptyBuffer

        views.fill(null, 0, created)
        created = 0
        used = 0
    }

    private companion object {

        // enough for the fields of about 30 headers, bounds the memory kept per connection (56 bytes per view)
        const val Capacity = 64

    }

}
