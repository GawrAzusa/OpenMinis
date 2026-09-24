package com.openminis.app.assistant

import org.junit.Assert.*
import org.junit.Test

class AssistantLaunchTest {
    @Test fun `only platform assistant actions trigger recording`() {
        assertTrue(AssistantLaunch.isAssistAction("android.intent.action.ASSIST"))
        assertTrue(AssistantLaunch.isAssistAction("android.intent.action.VOICE_ASSIST"))
        assertFalse(AssistantLaunch.isAssistAction(null))
        assertFalse(AssistantLaunch.isAssistAction("android.intent.action.VIEW"))
        assertFalse(AssistantLaunch.isAssistAction("android.intent.action.MAIN"))
    }
    @Test fun `new invocation gets a distinct draft and is consumed only once`() {
        val id = AssistantLaunch.newSessionId()
        assertTrue(id.startsWith("__new__"))
        assertFalse(AssistantLaunch.consume("different-chat"))
        assertTrue(AssistantLaunch.consume(id))
        assertFalse(AssistantLaunch.consume(id))
        assertNotEquals(id, AssistantLaunch.newSessionId())
    }
    @Test fun `latest invocation supersedes a pending earlier one`() {
        val first = AssistantLaunch.newSessionId()
        val second = AssistantLaunch.newSessionId()
        assertFalse(AssistantLaunch.consume(first))
        assertTrue(AssistantLaunch.consume(second))
    }
    @Test fun `ordinary restored or externally linked sessions cannot start recording`() {
        assertFalse(AssistantLaunch.consume("__new__restored"))
        assertFalse(AssistantLaunch.consume("existing-session"))
    }
    @Test fun `leaving before navigation cancels the pending token`() {
        val id = AssistantLaunch.newSessionId()
        AssistantLaunch.cancelPending()
        assertFalse(AssistantLaunch.consume(id))
    }
}
