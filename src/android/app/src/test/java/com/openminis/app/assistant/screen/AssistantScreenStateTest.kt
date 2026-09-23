package com.openminis.app.assistant.screen

import org.junit.Assert.*
import org.junit.Test

class AssistantScreenStateTest {
    @Test fun retryCannotExposeOldErrorWhileClearingTheFrame() {
        val old = AssistantScreenCapture.State(error = "prior failure", frameVersion = 9)
        val starting = old.startingRequest()
        assertNull(starting.error)
        assertFalse(starting.readyForPreview())
        assertFalse(starting.copy(selectionVersion = 3, frameAvailable = false).readyForPreview())
    }
    @Test fun startedProjectionWithoutNewFrameDoesNotReopenOverlay() {
        val starting = AssistantScreenCapture.State(frameVersion = 9, frameAvailable = true).startingRequest()
        val started = starting.copy(active = true, requesting = false)
        assertFalse(started.readyForPreview())
        assertTrue(started.copy(frameAvailable = true, frameVersion = 10).readyForPreview())
    }
    @Test fun realFailureIsTerminalButStoppedEmptyCaptureIsNot() {
        assertTrue(AssistantScreenCapture.State(error = "denied").readyForPreview())
        assertFalse(AssistantScreenCapture.State(frameVersion = 10).readyForPreview())
    }
}
