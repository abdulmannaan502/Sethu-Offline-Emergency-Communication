package com.sethu.emergency.sim

import com.sethu.emergency.comm.bluetooth.BoundedLineReader
import com.sethu.emergency.comm.bluetooth.BoundedLineReader.Frame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class BoundedLineReaderTest {

    /** Stream that returns at most [chunk] bytes per read() to emulate fragmented Bluetooth delivery. */
    private class Trickle(private val data: ByteArray, private val chunk: Int) : InputStream() {
        private var pos = 0
        override fun read(): Int = if (pos >= data.size) -1 else data[pos++].toInt() and 0xFF
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos >= data.size) return -1
            val n = minOf(len, chunk, data.size - pos)
            System.arraycopy(data, pos, b, off, n)
            pos += n
            return n
        }
    }

    private fun readAll(reader: BoundedLineReader): List<Frame> {
        val out = mutableListOf<Frame>()
        while (true) {
            val f = reader.readFrame()
            out.add(f)
            if (f is Frame.EndOfStream) return out
        }
    }

    @Test
    fun framesSplitAcrossManySmallReadsAreReassembled() {
        val data = "{\"a\":1}\n{\"b\":\"two\"}\n".toByteArray()
        val frames = readAll(BoundedLineReader(Trickle(data, 1)))
        assertEquals(listOf(Frame.Line("{\"a\":1}"), Frame.Line("{\"b\":\"two\"}"), Frame.EndOfStream), frames)
    }

    @Test
    fun multiByteUtf8SplitAcrossReadsIsDecodedCorrectly() {
        val data = "{\"n\":\"ünï ✓ 日本\"}\n".toByteArray(Charsets.UTF_8)
        val frames = readAll(BoundedLineReader(Trickle(data, 2)))
        assertEquals(Frame.Line("{\"n\":\"ünï ✓ 日本\"}"), frames[0])
    }

    @Test
    fun truncatedTrailingFrameIsNeverDelivered() {
        val frames = readAll(BoundedLineReader(ByteArrayInputStream("{\"ok\":1}\n{\"cut\":".toByteArray())))
        assertEquals(listOf(Frame.Line("{\"ok\":1}"), Frame.EndOfStream), frames)
    }

    @Test
    fun oversizedFrameIsDroppedAndStreamStaysAligned() {
        val big = "x".repeat(5000)
        val data = ("good1\n" + big + "\ngood2\n").toByteArray()
        val frames = readAll(BoundedLineReader(Trickle(data, 64), maxFrameBytes = 1024))
        assertEquals(listOf(Frame.Line("good1"), Frame.Oversized, Frame.Line("good2"), Frame.EndOfStream), frames)
    }

    @Test
    fun frameExactlyAtLimitIsAcceptedOneOverIsNot() {
        val ok = "y".repeat(100)
        val over = "y".repeat(101)
        val frames = readAll(BoundedLineReader(ByteArrayInputStream("$ok\n$over\n".toByteArray()), maxFrameBytes = 100))
        assertEquals(Frame.Line(ok), frames[0])
        assertEquals(Frame.Oversized, frames[1])
    }

    @Test
    fun emptyStreamAndBlankLines() {
        assertTrue(readAll(BoundedLineReader(ByteArrayInputStream(ByteArray(0)))) == listOf<Frame>(Frame.EndOfStream))
        val frames = readAll(BoundedLineReader(ByteArrayInputStream("\n\nx\n".toByteArray())))
        assertEquals(listOf(Frame.Line(""), Frame.Line(""), Frame.Line("x"), Frame.EndOfStream), frames)
    }
}
