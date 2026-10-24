package ktron.glyph11.tests

import ktron.glyph11.HttpParseException
import ktron.glyph11.parser.ChunkResult
import ktron.glyph11.parser.ChunkedBodyStream
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.ByteBuffer
import kotlin.test.assertFailsWith

/**
 * Only the malformed input test of the upstream sequence tests is ported: the port does not know
 * segmented input, so every input is presented as one contiguous buffer.
 */
class ChunkedBodyStreamSequenceTests {

    private fun b(s: String): ByteArray = TestBuffers.b(s)

    @ParameterizedTest
    @ValueSource(
        strings = [
            "G\r\n\r\n",                 // invalid hex digit
            "5\r\nHello\nX",             // bare LF after chunk data
            " 5\r\nHello\r\n0\r\n\r\n",  // leading whitespace in size
            "-5\r\n\r\n",                // negative size
            "0x5\r\n\r\n",               // hex prefix
        ]
    )
    fun sequence_RejectsMalformed(raw: String) {
        val input = b(raw)

        assertFailsWith<HttpParseException> {
            val s = ChunkedBodyStream()
            var offset = 0

            for (i in 0 until 32) {
                val r = s.tryReadChunk(TestBuffers.skip(ByteBuffer.wrap(input), offset))

                if (r == ChunkResult.Completed || r == ChunkResult.NeedMoreData) break

                offset += s.bytesConsumed
            }
        }
    }

}
