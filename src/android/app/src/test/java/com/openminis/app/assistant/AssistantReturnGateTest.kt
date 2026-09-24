package com.openminis.app.assistant

import org.junit.Assert.*
import org.junit.Test

class AssistantReturnGateTest {
    @Test fun onlyOneIntentionalHideCanKeepActionAlive() {
        val ticket = AssistantReturnGate.beginTemporary()
        assertTrue(AssistantReturnGate.consumeExpectedHide())
        assertFalse(AssistantReturnGate.consumeExpectedHide())
        assertTrue(AssistantReturnGate.current(ticket))
        AssistantReturnGate.invalidate()
        assertFalse(AssistantReturnGate.current(ticket))
    }
    @Test fun newActionSupersedesPendingCaptureAndSend() {
        val old = AssistantReturnGate.beginTemporary()
        val replacement = AssistantReturnGate.beginTemporary()
        assertFalse(AssistantReturnGate.current(old))
        assertTrue(AssistantReturnGate.current(replacement))
    }
    @Test fun dismissalCancelsPermissionReturn() {
        val permission = AssistantReturnGate.beginTemporary()
        AssistantReturnGate.invalidate()
        assertFalse(AssistantReturnGate.current(permission))
        assertFalse(AssistantReturnGate.consumeExpectedHide())
    }
}
