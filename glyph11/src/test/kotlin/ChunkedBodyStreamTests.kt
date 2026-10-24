package ktron.glyph11.tests

import ktron.glyph11.HttpParseException
import ktron.glyph11.parser.ChunkResult
import ktron.glyph11.parser.ChunkedBodyStream
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ChunkedBodyStreamTests {

    private fun b(s: String): ByteArray = TestBuffers.b(s)

    private fun span(bytes: ByteArray): ByteBuffer = ByteBuffer.wrap(bytes)

    private fun slice(buffer: ByteBuffer, start: Int): ByteBuffer = TestBuffers.skip(buffer, start)

    private fun text(buffer: ByteBuffer, offset: Int, length: Int): String = TestBuffers.asciiAt(buffer, offset, length)

    // ==== Single-call (parity with TryValidate) ====

    @Test
    fun singleCall_ValidSingleChunk() {
        val input = span(b("5\r\nHello\r\n0\r\n\r\n"))
        val stream = ChunkedBodyStream()

        val r1 = stream.tryReadChunk(input)
        val c1 = stream.bytesConsumed
        val off1 = stream.dataOffset
        val len1 = stream.dataLength
        assertEquals(ChunkResult.Chunk, r1)
        assertEquals(5, len1)
        assertEquals("Hello", text(input, off1, len1))

        val remaining = slice(input, c1)
        val r2 = stream.tryReadChunk(remaining)
        val c2 = stream.bytesConsumed
        assertEquals(ChunkResult.Completed, r2)
        assertEquals(remaining.remaining(), c2)
    }

    @Test
    fun singleCall_ValidMultiChunk() {
        val input = span(b("5\r\nHello\r\n6\r\n World\r\n0\r\n\r\n"))
        val stream = ChunkedBodyStream()

        val r1 = stream.tryReadChunk(input)
        val c1 = stream.bytesConsumed
        val off1 = stream.dataOffset
        val len1 = stream.dataLength
        assertEquals(ChunkResult.Chunk, r1)
        assertEquals("Hello", text(input, off1, len1))

        val span2 = slice(input, c1)
        val r2 = stream.tryReadChunk(span2)
        val c2 = stream.bytesConsumed
        val off2 = stream.dataOffset
        val len2 = stream.dataLength
        assertEquals(ChunkResult.Chunk, r2)
        assertEquals(" World", text(span2, off2, len2))

        val span3 = slice(span2, c2)
        val r3 = stream.tryReadChunk(span3)
        val c3 = stream.bytesConsumed
        assertEquals(ChunkResult.Completed, r3)
        assertEquals(span3.remaining(), c3)
    }

    @Test
    fun singleCall_EmptyBody() {
        val bytes = b("0\r\n\r\n")
        val input = span(bytes)
        val stream = ChunkedBodyStream()

        val r = stream.tryReadChunk(input)
        val consumed = stream.bytesConsumed
        assertEquals(ChunkResult.Completed, r)
        assertEquals(bytes.size, consumed)
    }

    @Test
    fun singleCall_WithExtension() {
        val input = span(b("5;name=value\r\nHello\r\n0\r\n\r\n"))
        val stream = ChunkedBodyStream()

        val r1 = stream.tryReadChunk(input)
        val c1 = stream.bytesConsumed
        val off1 = stream.dataOffset
        val len1 = stream.dataLength
        assertEquals(ChunkResult.Chunk, r1)
        assertEquals("Hello", text(input, off1, len1))

        val remaining = slice(input, c1)
        val r2 = stream.tryReadChunk(remaining)
        assertEquals(ChunkResult.Completed, r2)
    }

    @Test
    fun singleCall_WithTrailers() {
        val bytes = b("0\r\nTrailer: value\r\n\r\n")
        val input = span(bytes)
        val stream = ChunkedBodyStream()

        val r = stream.tryReadChunk(input)
        val consumed = stream.bytesConsumed
        assertEquals(ChunkResult.Completed, r)
        assertEquals(bytes.size, consumed)
    }

    @Test
    fun singleCall_Malformed_Throws() {
        val input = span(b("-5\r\nHello\r\n0\r\n\r\n"))
        val stream = ChunkedBodyStream()

        assertFailsWith<HttpParseException> {
            stream.tryReadChunk(input)
        }
    }

    // ==== Data slice correctness ====

    @Test
    fun dataSlice_CorrectOffsetAndLength() {
        val bytes = b("5\r\nHello\r\n0\r\n\r\n")
        val input = span(bytes)
        val stream = ChunkedBodyStream()

        stream.tryReadChunk(input)
        val off = stream.dataOffset
        val len = stream.dataLength

        // Verify the offset points exactly past "5\r\n"
        assertEquals(3, off)
        assertEquals(5, len)

        // Verify actual bytes
        val payload = bytes.copyOfRange(off, off + len)
        assertContentEquals(b("Hello"), payload)
    }

    @Test
    fun dataSlice_MultiChunk_EachSliceCorrect() {
        val input = span(b("3\r\nFoo\r\n4\r\nBar!\r\n0\r\n\r\n"))
        val stream = ChunkedBodyStream()

        // First chunk
        val r1 = stream.tryReadChunk(input)
        val c1 = stream.bytesConsumed
        val off1 = stream.dataOffset
        val len1 = stream.dataLength
        assertEquals(ChunkResult.Chunk, r1)
        assertEquals("Foo", text(input, off1, len1))

        // Second chunk (on the remaining span)
        val span2 = slice(input, c1)
        val r2 = stream.tryReadChunk(span2)
        val c2 = stream.bytesConsumed
        val off2 = stream.dataOffset
        val len2 = stream.dataLength
        assertEquals(ChunkResult.Chunk, r2)
        assertEquals("Bar!", text(span2, off2, len2))

        // Terminal
        val span3 = slice(span2, c2)
        val r3 = stream.tryReadChunk(span3)
        assertEquals(ChunkResult.Completed, r3)
    }

    // ==== Incremental ====

    @Test
    fun incremental_ChunkSizeAcrossCalls() {
        // "1A\r\n" + 26 bytes + "\r\n" + "0\r\n\r\n"
        val data = "X".repeat(26)
        val full = span(b("1A\r\n$data\r\n0\r\n\r\n"))

        val stream = ChunkedBodyStream()

        // First call: just "1" - not enough
        val part1 = span(b("1"))
        val r1 = stream.tryReadChunk(part1)
        val c1 = stream.bytesConsumed
        assertEquals(ChunkResult.NeedMoreData, r1)
        assertEquals(0, c1)

        // Second call: full input
        val r2 = stream.tryReadChunk(full)
        val off2 = stream.dataOffset
        val len2 = stream.dataLength
        assertEquals(ChunkResult.Chunk, r2)
        assertEquals(26, len2)
        assertEquals(data, text(full, off2, len2))
    }

    @Test
    fun incremental_ChunkDataAcrossCalls() {
        val stream = ChunkedBodyStream()

        // Send size line but only partial data
        val part1 = span(b("5\r\nHe"))
        val r1 = stream.tryReadChunk(part1)
        val c1 = stream.bytesConsumed
        assertEquals(ChunkResult.NeedMoreData, r1)
        assertEquals(0, c1)

        // Now send the full chunk
        val part2 = span(b("5\r\nHello\r\n0\r\n\r\n"))
        val r2 = stream.tryReadChunk(part2)
        val off2 = stream.dataOffset
        val len2 = stream.dataLength
        assertEquals(ChunkResult.Chunk, r2)
        assertEquals("Hello", text(part2, off2, len2))
    }

    @Test
    fun incremental_MultiChunkByteByByte() {
        val full = b("3\r\nFoo\r\n2\r\nHi\r\n0\r\n\r\n")
        var stream = ChunkedBodyStream()

        val chunks = ArrayList<String>()
        val globalPos = 0

        while (globalPos < full.size) {
            // Feed one byte at a time, but present the full remaining buffer
            // since NeedMoreData means "don't advance, get more data"
            // We simulate by growing the window by 1 byte each iteration.
            @Suppress("UNUSED_VARIABLE")
            val window = ByteBuffer.wrap(full, 0, globalPos + 1)

            // We need to re-present from where the parser last returned NeedMoreData.
            // Since bytesConsumed is 0 on NeedMoreData, we just grow window.
            // But after Chunk, we advance. Track total consumed.
            break // See byte-at-a-time strategy below.
        }

        // Better approach: accumulate bytes and always present full unconsumed buffer
        val buffer = ArrayList<Byte>()
        stream = ChunkedBodyStream()
        chunks.clear()
        var completed = false

        outer@ for (byte in full) {
            buffer.add(byte)
            var span = ByteBuffer.wrap(buffer.toByteArray())

            while (true) {
                val r = stream.tryReadChunk(span)
                val consumed = stream.bytesConsumed
                val off = stream.dataOffset
                val len = stream.dataLength

                if (r == ChunkResult.Chunk) {
                    chunks.add(text(span, off, len))
                    buffer.subList(0, consumed).clear()
                    span = ByteBuffer.wrap(buffer.toByteArray())
                    if (span.remaining() > 0) continue // retry
                } else if (r == ChunkResult.Completed) {
                    completed = true
                    break@outer
                }
                // NeedMoreData -> continue adding bytes
                break
            }
        }

        assertTrue(completed)
        assertEquals(2, chunks.size)
        assertEquals("Foo", chunks[0])
        assertEquals("Hi", chunks[1])
    }

    @Test
    fun incremental_TerminalChunkSplitFromTrailers() {
        val stream = ChunkedBodyStream()

        // First: "0\r\n" - terminal chunk size, but no trailing CRLF yet
        val part1 = span(b("0\r\n"))
        val r1 = stream.tryReadChunk(part1)
        val c1 = stream.bytesConsumed
        assertEquals(ChunkResult.NeedMoreData, r1)
        assertEquals(0, c1)

        // Second: full "0\r\n\r\n"
        val part2 = span(b("0\r\n\r\n"))
        val r2 = stream.tryReadChunk(part2)
        val c2 = stream.bytesConsumed
        assertEquals(ChunkResult.Completed, r2)
        assertEquals(part2.remaining(), c2)
    }

    // ==== Error mid-stream ====

    @Test
    fun incremental_MalformedAfterValidChunks() {
        val stream = ChunkedBodyStream()

        // First chunk is valid
        val part1 = span(b("5\r\nHello\r\n"))
        val r1 = stream.tryReadChunk(part1)
        assertEquals(ChunkResult.Chunk, r1)

        // Second chunk is malformed (negative size)
        val part2 = span(b("-3\r\nFoo\r\n0\r\n\r\n"))
        assertFailsWith<HttpParseException> {
            stream.tryReadChunk(part2)
        }
    }

}
