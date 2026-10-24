package ktron.glyph11.tests

import ktron.glyph11.parser.ParserLimits
import ktron.glyph11.parser.ultrahardened.UltraHardenedParser
import ktron.glyph11.protocol.BinaryRequest
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BinaryRequestTests {

    @Test
    fun clear_ResetsAllFields() {
        BinaryRequest().use { request ->
            val limits = ParserLimits.Default

            // Populate via parser
            val rom = ByteBuffer.wrap(TestBuffers.b("GET /path?a=1 HTTP/1.1\r\nHost: x\r\n\r\n"))
            UltraHardenedParser.tryExtractFullHeader(rom, request, limits)

            // Verify fields are populated
            assertFalse(request.method.remaining() == 0)
            assertFalse(request.path.remaining() == 0)
            assertFalse(request.version.remaining() == 0)
            assertTrue(request.headers.count > 0)
            assertTrue(request.queryParameters.count > 0)

            request.clear()

            assertTrue(request.method.remaining() == 0)
            assertTrue(request.path.remaining() == 0)
            assertTrue(request.version.remaining() == 0)
            assertTrue(request.body.remaining() == 0)
            assertEquals(0, request.headers.count)
            assertEquals(0, request.queryParameters.count)
        }
    }

}
