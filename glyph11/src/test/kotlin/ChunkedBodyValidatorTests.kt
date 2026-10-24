package ktron.glyph11.tests

import ktron.glyph11.HttpParseException
import ktron.glyph11.validation.ChunkedBodyValidator
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChunkedBodyValidatorTests {

    private fun b(s: String): ByteArray = TestBuffers.b(s)

    /** Maps the Int result of the validator to the (success, consumed) pair of the upstream API. */
    private fun tryValidate(body: ByteArray): Pair<Boolean, Int> {
        val consumed = ChunkedBodyValidator.tryValidate(ByteBuffer.wrap(body))
        return Pair(consumed >= 0, consumed)
    }

    // ---- Valid cases ----

    @Test
    fun valid_SingleChunk() {
        val body = b("5\r\nHello\r\n0\r\n\r\n")
        val (ok, consumed) = tryValidate(body)
        assertTrue(ok)
        assertEquals(body.size, consumed)
    }

    @Test
    fun valid_MultiChunk() {
        val body = b("5\r\nHello\r\n6\r\n World\r\n0\r\n\r\n")
        val (ok, consumed) = tryValidate(body)
        assertTrue(ok)
        assertEquals(body.size, consumed)
    }

    @Test
    fun valid_EmptyBody() {
        val body = b("0\r\n\r\n")
        val (ok, consumed) = tryValidate(body)
        assertTrue(ok)
        assertEquals(body.size, consumed)
    }

    @Test
    fun valid_ChunkWithExtension() {
        val body = b("5;name=value\r\nHello\r\n0\r\n\r\n")
        val (ok, consumed) = tryValidate(body)
        assertTrue(ok)
        assertEquals(body.size, consumed)
    }

    @Test
    fun valid_HexUppercase() {
        val body = b("A\r\n0123456789\r\n0\r\n\r\n")
        val (ok, consumed) = tryValidate(body)
        assertTrue(ok)
        assertEquals(body.size, consumed)
    }

    @Test
    fun valid_HexLowercase() {
        val body = b("a\r\n0123456789\r\n0\r\n\r\n")
        val (ok, consumed) = tryValidate(body)
        assertTrue(ok)
        assertEquals(body.size, consumed)
    }

    @Test
    fun valid_WithTrailers() {
        val body = b("0\r\nTrailer: value\r\n\r\n")
        val (ok, consumed) = tryValidate(body)
        assertTrue(ok)
        assertEquals(body.size, consumed)
    }

    // ---- Invalid cases ----

    @Test
    fun invalid_MissingCrlfAfterChunkData() {
        val body = b("5\r\nHello0\r\n\r\n")
        assertFailsWith<HttpParseException> { tryValidate(body) }
    }

    @Test
    fun invalid_BareLfTerminator() {
        val body = b("5\nHello\r\n0\r\n\r\n")
        assertFailsWith<HttpParseException> { tryValidate(body) }
    }

    @Test
    fun invalid_HexPrefix() {
        val body = b("0x5\r\nHello\r\n0\r\n\r\n")
        assertFailsWith<HttpParseException> { tryValidate(body) }
    }

    @Test
    fun invalid_UnderscoreInSize() {
        val body = b("5_0\r\n" + "A".repeat(80) + "\r\n0\r\n\r\n")
        assertFailsWith<HttpParseException> { tryValidate(body) }
    }

    @Test
    fun invalid_LeadingSpace() {
        val body = b(" 5\r\nHello\r\n0\r\n\r\n")
        assertFailsWith<HttpParseException> { tryValidate(body) }
    }

    @Test
    fun invalid_NegativeSize() {
        val body = b("-5\r\nHello\r\n0\r\n\r\n")
        assertFailsWith<HttpParseException> { tryValidate(body) }
    }

    @Test
    fun invalid_NulInExtension() {
        val raw = byteArrayOf(
            '5'.code.toByte(), ';'.code.toByte(), 0x00, '\r'.code.toByte(), '\n'.code.toByte(),
            'H'.code.toByte(), 'e'.code.toByte(), 'l'.code.toByte(), 'l'.code.toByte(), 'o'.code.toByte(),
            '\r'.code.toByte(), '\n'.code.toByte(),
            '0'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte()
        )
        assertFailsWith<HttpParseException> { tryValidate(raw) }
    }

    @Test
    fun invalid_IntegerOverflow() {
        // 17 hex digits -> overflow
        val body = b("FFFFFFFFFFFFFFFFF\r\n")
        assertFailsWith<HttpParseException> { tryValidate(body) }
    }

    @Test
    fun invalid_BareLfInTrailer() {
        val body = b("0\r\nTrailer: value\n\r\n")
        assertFailsWith<HttpParseException> { tryValidate(body) }
    }

    // ---- Incomplete cases ----

    @Test
    fun incomplete_EmptyBuffer() {
        val (ok, _) = tryValidate(ByteArray(0))
        assertFalse(ok)
    }

    @Test
    fun incomplete_PartialSize() {
        val body = b("5")
        val (ok, _) = tryValidate(body)
        assertFalse(ok)
    }

    @Test
    fun incomplete_SizeWithoutData() {
        val body = b("5\r\n")
        val (ok, _) = tryValidate(body)
        assertFalse(ok)
    }

    @Test
    fun incomplete_MissingFinalCrlf() {
        val body = b("0\r\n")
        val (ok, _) = tryValidate(body)
        assertFalse(ok)
    }

}
