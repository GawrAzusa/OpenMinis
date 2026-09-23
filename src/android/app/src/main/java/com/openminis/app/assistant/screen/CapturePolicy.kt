package com.openminis.app.assistant.screen

import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.roundToInt

/** Pure policy; callers serialize access. A consent result is consumed at most once. */
internal class CaptureGeneration {
    private var generation = 0L
    private var open = false
    private var consumed = false

    fun begin(): Long {
        generation++
        open = true
        consumed = false
        return generation
    }

    fun current(id: Long): Boolean = open && id == generation
    fun consume(id: Long): Boolean {
        if (!current(id) || consumed) return false
        consumed = true
        return true
    }

    fun end(id: Long): Boolean {
        if (!current(id)) return false
        open = false
        return true
    }
}

internal object CapturePolicy {
    const val MAX_EDGE = 1280
    const val FRAME_INTERVAL_MS = 1500L
    const val FIRST_FRAME_TIMEOUT_MS = 15000L

    fun dimensions(width: Int, height: Int): Pair<Int, Int> {
        require(width > 0 && height > 0)
        val scale = minOf(1.0, MAX_EDGE.toDouble() / max(width, height))
        return max(1, (width * scale).roundToInt()) to max(1, (height * scale).roundToInt())
    }

    /** Never read row padding, including padding absent from the final row. */
    fun rgbaPixels(buffer: ByteBuffer, rowStride: Int, pixelStride: Int,
                   left: Int, top: Int, width: Int, height: Int): IntArray {
        require(pixelStride >= 4 && rowStride > 0 && left >= 0 && top >= 0)
        require(width in 1..MAX_EDGE && height in 1..MAX_EDGE)
        val base = buffer.position().toLong()
        val last = base + (top.toLong() + height - 1) * rowStride +
            (left.toLong() + width - 1) * pixelStride + 2
        require(last < buffer.limit())
        require((left.toLong() + width - 1) * pixelStride + 3 < rowStride)
        return IntArray(width * height) { index ->
            val offset = (base + (top.toLong() + index / width) * rowStride +
                (left.toLong() + index % width) * pixelStride).toInt()
            val r = buffer.get(offset).toInt() and 255
            val g = buffer.get(offset + 1).toInt() and 255
            val b = buffer.get(offset + 2).toInt() and 255
            (255 shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    // Conservative: reject fully black/near-black images; this cannot identify every secure region.
    fun hasVisiblePixels(argb: IntArray): Boolean = argb.any {
        ((it ushr 16) and 255) > 8 || ((it ushr 8) and 255) > 8 || (it and 255) > 8
    }
}
