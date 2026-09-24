package com.openminis.app.assistant

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Structural guard; idle/active behavior is also exercised on the actual test phone. */
class AssistantPauseSourceGuardTest {
    @Test fun idleHideDoesNotEnterCompletedTurnCancellationCleanup() {
        val source = File("src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt").readText()
        val body = source.substringAfter("fun pauseAssistantTask() {")
            .substringBefore("fun assistantAudioUnavailableReason()")
        assertTrue(body.contains("val hadActiveStream = _isStreaming.value"))
        assertTrue(body.indexOf("val hadActiveStream") < body.indexOf("assistantSendSetupJob?.cancel()"))
        assertTrue(body.contains("assistantTaskPaused = true"))
        assertTrue(body.contains("assistantSendSetupJob?.cancel()"))
        assertTrue(body.contains("assistantResumeSetupJob?.cancel()"))
        assertTrue(body.contains("if (hadActiveStream) cancelStream()"))
    }
}
