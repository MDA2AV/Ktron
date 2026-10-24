package ktron.glyph11.tests

import ktron.glyph11.parser.flexibleparser.FlexibleParser
import ktron.glyph11.protocol.BinaryRequest
import ktron.glyph11.protocol.KeyValueList
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class FlexibleParserTryExtractFullHeader_ROM {

    private val expectedPath = "/route"

    @Test
    fun parseSingleSegmentRequest() {
        val request =
            "GET /route?p1=1&p2=2&p3=3&p4=4 HTTP/1.1\r\n" +
            "Content-Length: 100\r\n" +
            "Server: Glyph11\r\n" +
            "\r\n"

        val rom = ByteBuffer.wrap(TestBuffers.b(request))

        val data = BinaryRequest()

        val position = FlexibleParser.tryExtractFullHeader(rom, data)

        assertTrue(position >= 0)
        assertRequestParsedCorrectly(data)

        // Verify consumed exactly the header bytes
        assertEquals(rom.remaining() - 1, position)
    }

    @Test
    fun parseMultiSegmentRequest() {
        // the port only supports contiguous input: the bytes of the three upstream segments are
        // parsed as one buffer placed in the middle of a larger direct buffer
        val segmented = createMultiSegment()

        val data = BinaryRequest()

        val position = FlexibleParser.tryExtractFullHeader(segmented, data)

        assertTrue(position >= 0)
        assertRequestParsedCorrectly(data)

        assertEquals(segmented.remaining() - 1, position)
    }

    private fun assertRequestParsedCorrectly(data: BinaryRequest) {
        // Method + path (path only, query stripped)
        AssertAscii.equal("GET", data.method)
        AssertAscii.equal(expectedPath, data.path)

        // Query params
        val qp = data.queryParameters
        assertEquals(4, qp.count)

        assertKeyValue(qp, "p1", "1")
        assertKeyValue(qp, "p2", "2")
        assertKeyValue(qp, "p3", "3")
        assertKeyValue(qp, "p4", "4")

        // Headers
        val headers = data.headers
        assertEquals(2, headers.count)

        assertKeyValue(headers, "Content-Length", "100")
        assertKeyValue(headers, "Server", "Glyph11")
    }

    private fun assertKeyValue(list: KeyValueList, expectedKey: String, expectedValue: String) {
        for (i in 0 until list.count) {
            val kv = list[i]

            val key = kv.first

            if (asciiEquals(key, expectedKey)) {
                AssertAscii.equal(expectedValue, kv.second)
                return
            }
        }

        fail("Missing key '$expectedKey'")
    }

    private fun asciiEquals(bytes: ByteBuffer, ascii: String): Boolean {
        // avoid string allocation for key compares
        if (bytes.remaining() != ascii.length) return false

        for (i in 0 until bytes.remaining()) {
            if (bytes.get(bytes.position() + i) != ascii[i].code.toByte()) {
                return false
            }
        }

        return true
    }

    private fun createMultiSegment(): ByteBuffer {
        val seg1 = TestBuffers.b("GET /route?p1=1&p2=2&p3=3&p4=4 HT")
        val seg2 = TestBuffers.b("TP/1.1\r\nContent-Length: 100\r\nServer: ")
        val seg3 = TestBuffers.b("Glyph11\r\n\r\n")

        return TestBuffers.input(seg1 + seg2 + seg3, true)
    }

}
