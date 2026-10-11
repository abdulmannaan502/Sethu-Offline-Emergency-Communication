package com.sethu.emergency.comm.bluetooth

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets

/**
 * Newline-delimited frame reader with a hard per-frame size bound.
 *
 * Sethu frames are single-line UTF-8 JSON objects terminated by '\n' (JSON string escaping guarantees
 * the payload never contains a raw newline). An unbounded readLine() would let a misbehaving peer exhaust
 * memory; here an oversized frame is discarded up to its terminator and reported as [Frame.Oversized] so the
 * stream stays aligned for the next frame. A frame truncated by EOF is never delivered.
 */
class BoundedLineReader(
    private val input: InputStream,
    private val maxFrameBytes: Int = DEFAULT_MAX_FRAME_BYTES
) {
    companion object {
        const val DEFAULT_MAX_FRAME_BYTES = 64 * 1024
    }

    sealed class Frame {
        data class Line(val text: String) : Frame()
        object Oversized : Frame()
        object EndOfStream : Frame()
    }

    private val buffer = ByteArray(1024)
    private var bufPos = 0
    private var bufLen = 0

    private fun nextByte(): Int {
        if (bufPos >= bufLen) {
            val n = input.read(buffer, 0, buffer.size)
            if (n < 0) return -1
            bufLen = n
            bufPos = 0
            if (n == 0) return nextByte()
        }
        return buffer[bufPos++].toInt() and 0xFF
    }

    /** Blocks for the next frame. Throws [IOException] if the underlying stream fails. */
    @Throws(IOException::class)
    fun readFrame(): Frame {
        val out = ByteArrayOutputStream()
        var oversized = false
        while (true) {
            val b = nextByte()
            if (b < 0) return Frame.EndOfStream // truncated trailing data is dropped, never delivered
            if (b == '\n'.code) {
                return if (oversized) Frame.Oversized else Frame.Line(String(out.toByteArray(), StandardCharsets.UTF_8))
            }
            if (!oversized) {
                if (out.size() >= maxFrameBytes) {
                    oversized = true
                    out.reset()
                } else {
                    out.write(b)
                }
            }
        }
    }
}
