package ktron.glyph11.protocol

import java.nio.ByteBuffer

/**
 * A growable list of key-value pairs where both key and value are read-only [ByteBuffer] slices.
 * Used to store parsed HTTP headers and query parameters without allocating per item.
 *
 * Backing storage is doubled on overflow. Call [clear] between requests to reset without
 * releasing the storage, or [close] to release it.
 *
 * @param initialCapacity Minimum number of entries before the first resize.
 */
public class KeyValueList(initialCapacity: Int = 16) : AutoCloseable {

    private var keys: Array<ByteBuffer?>

    private var values: Array<ByteBuffer?>

    /** Number of key-value pairs currently stored. */
    public var count: Int = 0
        private set

    init {
        val capacity = if (initialCapacity <= 0) 1 else initialCapacity

        keys = arrayOfNulls(capacity)
        values = arrayOfNulls(capacity)
    }

    /**
     * Gets the key-value pair at the specified index.
     *
     * @throws IndexOutOfBoundsException Thrown when [index] is out of range.
     */
    public operator fun get(index: Int): Pair<ByteBuffer, ByteBuffer> {
        checkIndex(index)

        return Pair(keys[index]!!, values[index]!!)
    }

    /** Returns the key at the given index without allocating a pair. */
    internal fun keyAt(index: Int): ByteBuffer {
        checkIndex(index)

        return keys[index]!!
    }

    /** Returns the value at the given index without allocating a pair. */
    internal fun valueAt(index: Int): ByteBuffer {
        checkIndex(index)

        return values[index]!!
    }

    private fun checkIndex(index: Int) {
        if (index < 0 || index >= count) {
            throw IndexOutOfBoundsException("Index $index out of bounds for count $count")
        }
    }

    /**
     * Appends a key-value pair. Grows the backing storage if needed.
     */
    internal fun add(key: ByteBuffer, value: ByteBuffer) {
        val i = count

        if (i < keys.size) {
            keys[i] = key
            values[i] = value
            count = i + 1
            return
        }

        growAndAdd(key, value)
    }

    /** Cold path: allocates larger storage, copies existing entries, then adds. */
    private fun growAndAdd(key: ByteBuffer, value: ByteBuffer) {
        var newSize = keys.size * 2
        if (newSize < 16) newSize = 16

        keys = keys.copyOf(newSize)
        values = values.copyOf(newSize)

        keys[count] = key
        values[count] = value

        count++
    }

    /**
     * Clears all entries without releasing the backing storage.
     * Use this between requests when reusing a [BinaryRequest].
     */
    internal fun clear() {
        keys.fill(null, 0, count)
        values.fill(null, 0, count)

        count = 0
    }

    /**
     * Returns a snapshot of the populated entries as a list of key-value pairs.
     */
    public fun asList(): List<Pair<ByteBuffer, ByteBuffer>> = List(count) { Pair(keys[it]!!, values[it]!!) }

    /**
     * Releases the backing storage. The list is empty afterwards.
     */
    override fun close() {
        keys = arrayOfNulls(0)
        values = arrayOfNulls(0)

        count = 0
    }

}
