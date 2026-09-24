package com.openminis.app.assistant

import com.openminis.app.assistant.AssistantVoiceEndpoint.Decision.*
import org.junit.Assert.*
import org.junit.Test

class AssistantVoiceEndpointTest {
    @Test fun emptyAndZeroDurationDoNotCreateSpeechOrAdvanceTime() {
        val endpoint = AssistantVoiceEndpoint()
        assertFalse(endpoint.hasSpeech)
        repeat(100) { assertEquals(CONTINUE, endpoint.accept(1f, 0)) }
        assertFalse(endpoint.hasSpeech)
        assertEquals(CONTINUE, endpoint.accept(0f, 9_999))
        assertEquals(SILENT, endpoint.accept(0f, 1))
    }

    @Test fun silenceTimesOutWithoutSendingAndCannotBeRevived() {
        val endpoint = AssistantVoiceEndpoint()
        repeat(156) { assertEquals(CONTINUE, endpoint.accept(0f, 64)) }
        assertEquals(SILENT, endpoint.accept(0f, 16))
        repeat(20) { assertEquals(SILENT, endpoint.accept(1f, 64)) }
        assertFalse(endpoint.hasSpeech)
    }

    @Test fun singleTransientNeverAuthorizesSend() {
        val endpoint = AssistantVoiceEndpoint()
        assertEquals(CONTINUE, endpoint.accept(1f, 64))
        assertFalse(endpoint.hasSpeech)
        assertEquals(CONTINUE, endpoint.accept(0f, 1_500))
        assertEquals(SILENT, endpoint.accept(0f, 8_436))
        assertFalse(endpoint.hasSpeech)
    }

    @Test fun isolatedSpikesDoNotAccumulateIntoSpeech() {
        val endpoint = AssistantVoiceEndpoint()
        repeat(50) {
            assertEquals(CONTINUE, endpoint.accept(1f, 64))
            assertEquals(CONTINUE, endpoint.accept(0f, 64))
        }
        assertFalse(endpoint.hasSpeech)
        assertEquals(SILENT, endpoint.accept(0f, 3_600))
    }

    @Test fun oneLongLoudBlockIsStillOnlyOneObservation() {
        val endpoint = AssistantVoiceEndpoint()
        assertEquals(CONTINUE, endpoint.accept(1f, 1_000))
        assertFalse(endpoint.hasSpeech)
        assertEquals(SILENT, endpoint.accept(0f, 9_000))
    }

    @Test fun sustainedEvidenceNeedsDurationAndMultipleBlocks() {
        val endpoint = AssistantVoiceEndpoint()
        repeat(3) { assertEquals(CONTINUE, endpoint.accept(0.1f, 64)) }
        assertFalse(endpoint.hasSpeech)
        assertEquals(CONTINUE, endpoint.accept(0.1f, 47))
        assertFalse(endpoint.hasSpeech)
        assertEquals(CONTINUE, endpoint.accept(0.1f, 1))
        assertTrue(endpoint.hasSpeech)
    }

    @Test fun shortPauseThenSpeechRestartsTrailingPause() {
        val endpoint = speakingEndpoint()
        assertEquals(CONTINUE, endpoint.accept(0f, 1_499))
        assertEquals(CONTINUE, endpoint.accept(0.1f, 64))
        assertEquals(CONTINUE, endpoint.accept(0f, 1_499))
        assertEquals(SEND, endpoint.accept(0f, 1))
    }

    @Test fun trailingPauseTransitionsToSendOnceAndLatches() {
        val endpoint = speakingEndpoint()
        assertEquals(CONTINUE, endpoint.accept(0f, 1_499))
        assertEquals(SEND, endpoint.accept(0f, 1))
        repeat(20) { assertEquals(SEND, endpoint.accept(1f, 64)) }
        assertEquals(SEND, endpoint.accept(Float.NaN, -1))
        assertTrue(endpoint.hasSpeech)
    }

    @Test fun continuousAudioSendsExactlyAtMaximumDuration() {
        val endpoint = AssistantVoiceEndpoint()
        repeat(937) { assertEquals(CONTINUE, endpoint.accept(0.1f, 64)) }
        assertTrue(endpoint.hasSpeech)
        assertEquals(CONTINUE, endpoint.accept(0.1f, 31))
        assertEquals(SEND, endpoint.accept(0.1f, 1))
    }

    @Test fun thresholdIsInclusiveAndBelowThresholdCannotAuthorizeSend() {
        val below = AssistantVoiceEndpoint()
        repeat(99) { assertEquals(CONTINUE, below.accept(0.019999f, 100)) }
        assertEquals(SILENT, below.accept(0.019999f, 100))
        assertFalse(below.hasSpeech)
        val at = AssistantVoiceEndpoint()
        repeat(3) { assertEquals(CONTINUE, at.accept(0.02f, 80)) }
        assertTrue(at.hasSpeech)
        assertEquals(SEND, at.accept(0.019999f, 1_500))
    }

    @Test fun invalidLevelsAndDurationsFailBeforeChangingState() {
        val endpoint = AssistantVoiceEndpoint()
        for (level in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -0.1f, 1.1f)) {
            expectInvalid { endpoint.accept(level, 10_000) }
        }
        expectInvalid { endpoint.accept(1f, -1) }
        expectInvalid { endpoint.accept(1f, Long.MIN_VALUE) }
        assertFalse(endpoint.hasSpeech)
        repeat(3) { assertEquals(CONTINUE, endpoint.accept(0.1f, 80)) }
        assertTrue(endpoint.hasSpeech)
        assertEquals(SEND, endpoint.accept(0f, 1_500))
    }

    @Test fun extremeDurationDoesNotOverflowOrTurnSilenceIntoSend() {
        for (level in listOf(0f, 1f)) {
            val endpoint = AssistantVoiceEndpoint()
            assertEquals(SILENT, endpoint.accept(level, Long.MAX_VALUE))
            assertFalse(endpoint.hasSpeech)
        }
        assertEquals(SEND, speakingEndpoint().accept(0f, Long.MAX_VALUE))
        assertEquals(SEND, speakingEndpoint().accept(1f, Long.MAX_VALUE))
    }

    @Test fun qualificationBlockCrossingMaximumStillHonorsTheCap() {
        val endpoint = AssistantVoiceEndpoint()
        repeat(2) { assertEquals(CONTINUE, endpoint.accept(0.1f, 80)) }
        assertEquals(SEND, endpoint.accept(0.1f, Long.MAX_VALUE))
        assertTrue(endpoint.hasSpeech)
    }

    @Test fun evidenceAfterInitialDeadlineCannotRescueSilentTurn() {
        val endpoint = AssistantVoiceEndpoint()
        assertEquals(CONTINUE, endpoint.accept(0f, 9_800))
        repeat(2) { assertEquals(CONTINUE, endpoint.accept(0.1f, 64)) }
        assertEquals(SILENT, endpoint.accept(0.1f, 10_000))
        assertFalse(endpoint.hasSpeech)
    }

    private fun speakingEndpoint() = AssistantVoiceEndpoint().also { endpoint ->
        repeat(4) { assertEquals(CONTINUE, endpoint.accept(0.1f, 64)) }
        assertTrue(endpoint.hasSpeech)
    }

    private fun expectInvalid(action: () -> Unit) {
        try {
            action()
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // Expected input contract failure.
        }
    }
}
