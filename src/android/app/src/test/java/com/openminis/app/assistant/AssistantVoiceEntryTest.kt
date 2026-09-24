package com.openminis.app.assistant

import org.junit.Assert.*
import org.junit.Test

class AssistantVoiceEntryTest {
    @Test fun pendingAcquisitionCannotSurvivePauseReplacementOrHandoff() {
        val request = AssistantVoiceRequest("old", 2)
        assertTrue(request.isCurrent("old", 2, true, false))
        assertFalse(request.isCurrent("old", 3, true, false))
        assertFalse(request.isCurrent("new", 2, true, false))
        assertFalse(request.isCurrent("old", 2, false, false))
        assertFalse(request.isCurrent("old", 2, true, true))
        assertFalse(AssistantVoiceRequest(null, 2).isCurrent(null, 2, true, false))
        assertTrue(AssistantVoiceRequest("old", 3).isCurrent("old", 3, true, false))
    }
    @Test fun explicitInvocationListensBeforePermissionCheck() {
        assertTrue(AssistantVoiceEntry().shouldListen(true, false, null, false))
    }
    @Test fun ordinaryRestoresAndProcessRecreationDoNotListen() {
        for (ticket in listOf(null, 14L)) {
            assertFalse(AssistantVoiceEntry().shouldListen(false, true, ticket, true))
        }
        assertFalse(AssistantVoiceEntry().shouldListen(false, false, null, true))
        assertFalse(AssistantVoiceEntry().shouldListen(true, true, null, true))
    }
    @Test fun onlyMatchingMicrophoneGrantContinuesOnce() {
        val entry = AssistantVoiceEntry()
        entry.awaitingMicrophone(4)
        assertTrue(entry.shouldListen(false, true, 4, true))
        assertFalse(entry.shouldListen(false, true, 4, true))
    }
    @Test fun deniedOrDifferentPermissionCannotArmRecorder() {
        val entry = AssistantVoiceEntry()
        entry.awaitingMicrophone(4)
        assertFalse(entry.shouldListen(false, true, 4, false))
        assertFalse(entry.shouldListen(false, true, 4, true))
        entry.awaitingMicrophone(5)
        assertFalse(entry.shouldListen(false, true, 6, true))
    }
    @Test fun explicitMicrophoneGrantPreservesTypedDraftPolicy() {
        val entry = AssistantVoiceEntry()
        entry.awaitingMicrophone(4, onlyEmptyComposer = false)
        assertTrue(entry.shouldListen(false, true, 4, true))
        assertFalse(entry.onlyEmptyComposer)
        assertFalse(entry.shouldListen(false, true, 4, true))
        assertTrue(entry.shouldListen(true, false, null, true))
        assertTrue(entry.onlyEmptyComposer)
    }
    @Test fun dismissalOrTypingRevokesPendingGrant() {
        val entry = AssistantVoiceEntry()
        entry.awaitingMicrophone(4)
        entry.cancel()
        assertFalse(entry.shouldListen(false, true, 4, true))
    }
    @Test fun newInvocationDiscardsOlderContinuation() {
        val entry = AssistantVoiceEntry()
        entry.awaitingMicrophone(4)
        assertTrue(entry.shouldListen(true, false, null, true))
        assertFalse(entry.shouldListen(false, true, 4, true))
    }
}
