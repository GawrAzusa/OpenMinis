package com.openminis.app.assistant.screen

import org.junit.Assert.*
import org.junit.Test

class CapturePolicyTest {
    @Test fun consentIsSingleUse() {
        val gate = CaptureGeneration()
        val id = gate.begin()
        assertTrue(gate.consume(id))
        assertFalse(gate.consume(id))
    }

    @Test fun stoppedConsentCannotRestart() {
        val gate = CaptureGeneration()
        val id = gate.begin()
        assertTrue(gate.end(id))
        assertFalse(gate.consume(id))
        assertFalse(gate.current(id))
    }

    @Test fun staleResultsAndCleanupCannotChangeNewGeneration() {
        val gate = CaptureGeneration()
        val first = gate.begin()
        val second = gate.begin()
        assertFalse(gate.consume(first))
        assertFalse(gate.end(first))
        assertTrue(gate.current(second))
        assertTrue(gate.consume(second))
    }

    @Test fun successfulOneShotCannotPublishAgain() {
        val gate = CaptureGeneration()
        val id = gate.begin()
        assertTrue(gate.consume(id))
        assertTrue(gate.end(id))
        assertFalse(gate.end(id))
        assertFalse(gate.current(id))
    }

    @Test fun dimensionsAreBoundedAcrossRotationAndExtremeAspectRatios() {
        assertEquals(720 to 1280, CapturePolicy.dimensions(2160, 3840))
        assertEquals(1280 to 720, CapturePolicy.dimensions(3840, 2160))
        assertEquals(64 to 32, CapturePolicy.dimensions(64, 32))
        assertEquals(1 to 1280, CapturePolicy.dimensions(1, Int.MAX_VALUE))
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidDimensionsAreRejected() { CapturePolicy.dimensions(0, 20) }

    @Test fun rgbaSkipsPaddingAndHandlesShortFinalRow() {
        val buffer = java.nio.ByteBuffer.wrap(byteArrayOf(1, 2, 3, -1, 99, 99, 99, 99, 4, 5, 6, -1))
        assertArrayEquals(intArrayOf(0xff010203.toInt(), 0xff040506.toInt()),
            CapturePolicy.rgbaPixels(buffer, 8, 4, 0, 0, 1, 2))
    }

    @Test fun rgbaHonorsCropPixelStrideAndBufferPosition() {
        val buffer = java.nio.ByteBuffer.allocate(40)
        buffer.position(2)
        buffer.put(26, 10); buffer.put(27, 20); buffer.put(28, 30)
        assertArrayEquals(intArrayOf(0xff0a141e.toInt()),
            CapturePolicy.rgbaPixels(buffer, 16, 8, 1, 1, 1, 1))
        assertEquals(2, buffer.position())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rgbaRejectsTruncatedBuffer() {
        CapturePolicy.rgbaPixels(java.nio.ByteBuffer.allocate(2), 4, 4, 0, 0, 1, 1)
    }

    @Test fun blackFramesAreNotReadableContent() {
        assertFalse(CapturePolicy.hasVisiblePixels(intArrayOf(0xff000000.toInt(), 0xff080808.toInt())))
        assertFalse(CapturePolicy.hasVisiblePixels(intArrayOf()))
        assertTrue(CapturePolicy.hasVisiblePixels(intArrayOf(0xff000010.toInt())))
    }
}
