package ktron.glyph11.tests

import ktron.glyph11.parser.flexibleparser.FlexibleParser
import ktron.glyph11.protocol.BinaryRequest
import ktron.glyph11.protocol.KeyValueList
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class KeyValueListTests {

    @Test
    fun indexer_ThrowsOnOutOfRange() {
        KeyValueList().use { list ->
            assertFailsWith<IndexOutOfBoundsException> { list[0] }
            assertFailsWith<IndexOutOfBoundsException> { list[-1] }
        }
    }

    @Test
    fun growsWhenCapacityExceeded() {
        // Parse a request with many headers to force growth
        BinaryRequest().use { request ->
            var raw = "GET / HTTP/1.1\r\n"
            for (i in 0 until 20) {
                raw += "X-H$i: val$i\r\n"
            }
            raw += "\r\n"

            val rom = ByteBuffer.wrap(raw.toByteArray(Charsets.US_ASCII))
            FlexibleParser.tryExtractFullHeader(rom, request)

            assertEquals(20, request.headers.count)

            for (i in 0 until 20) {
                val kv = request.headers[i]
                assertFalse(kv.first.remaining() == 0)
                assertFalse(kv.second.remaining() == 0)
            }
        }
    }

    @Test
    fun asSpan_ReturnsPopulatedEntries() {
        BinaryRequest().use { request ->
            val rom = ByteBuffer.wrap(TestBuffers.b("GET / HTTP/1.1\r\nA: 1\r\nB: 2\r\n\r\n"))
            FlexibleParser.tryExtractFullHeader(rom, request)

            val span = request.headers.asList()
            assertEquals(2, span.size)
        }
    }

    @Test
    fun dispose_CanBeCalledTwice() {
        val request = BinaryRequest()

        val rom = ByteBuffer.wrap(TestBuffers.b("GET / HTTP/1.1\r\nA: 1\r\n\r\n"))
        FlexibleParser.tryExtractFullHeader(rom, request)

        request.close()

        var ex: Throwable? = null

        try {
            request.close()
        } catch (e: Throwable) {
            ex = e
        }

        assertNull(ex)
    }

    @Test
    fun constructor_ClampsNonPositiveCapacity() {
        KeyValueList(initialCapacity = 0).use { list ->
            // Should not throw - capacity is clamped to 1
            assertEquals(0, list.count)
        }
    }

}
