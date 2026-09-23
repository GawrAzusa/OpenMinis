package com.openminis.app.assistant

import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Policy tests only. Real VoiceInteractionSession / a11y tests belong in androidTest. */
class AssistantToolSurfaceTest {
    @After fun resetTicket() { AssistantReturnGate.invalidate() }

    @Test fun onlyExactPersistedWorkspaceOwnerParticipates() {
        assertTrue(assistantToolOwns("persisted-chat", "persisted-chat"))
        assertFalse(assistantToolOwns("other-chat", "persisted-chat"))
        assertFalse(assistantToolOwns(null, "persisted-chat")) // interactive terminal
        assertFalse(assistantToolOwns("", ""))
        assertFalse(assistantToolOwns("persisted-chat", null))
        // Draft promotion is resolved on Main by the production adapter, not guessed here.
        assertFalse(assistantToolOwns("persisted-chat", "__new__draft"))
    }

    @Test fun permissionHelpAndNonVisualToolsNeverHide() {
        listOf("service" to "status", "service" to "ping", "--version" to null,
            "event" to "watch", "notify" to "once", "ui" to null,
            "dialog" to "watch", "extract" to "table", "tap" to "unknown").forEach {
            assertFalse("${it.first} ${it.second}", AssistantToolSurface.isVisual(it.first, it.second))
        }
    }

    @Test fun implementedVisualOperationsUseUnderlyingApp() {
        mapOf(
            "ui" to listOf("dump", "find", "info", "node", "screenshot"),
            "tap" to listOf("node", "xy", "text", "id"),
            "input" to listOf("text", "clear", "key"),
            "scroll" to listOf("node", "xy", "to-text"),
            "gesture" to listOf("swipe", "pinch", "path"),
            "wait" to listOf("appear", "disappear", "stable", "activity"),
            "dialog" to listOf("detect", "dismiss"),
            "extract" to listOf("text", "list", "form"),
        ).forEach { (group, verbs) -> verbs.forEach { assertTrue(AssistantToolSurface.isVisual(group, it)) } }
    }

    @Test fun dispatchRequiresActualAuthorizedHideAcknowledgement() {
        val lease = AssistantToolLeasePolicy()
        assertFalse(lease.canDispatch(authorized = true))
        assertFalse(lease.beginRestore(authorized = true)) // hide timed out / never arrived
        assertFalse(lease.acknowledgeHide(expected = false, authorized = true))
        assertFalse(lease.acknowledgeHide(expected = true, authorized = false))
        assertTrue(lease.acknowledgeHide(expected = true, authorized = true))
        assertTrue(lease.canDispatch(authorized = true))
        assertFalse(lease.acknowledgeHide(expected = true, authorized = true)) // second/external hide pauses
    }

    @Test fun homeCloseLockOrMinimizeInvalidatesDispatchAndRestore() {
        val lease = AssistantToolLeasePolicy()
        val ticket = AssistantReturnGate.beginTemporary()
        assertTrue(lease.acknowledgeHide(AssistantReturnGate.consumeExpectedHide(), AssistantReturnGate.current(ticket)))
        AssistantReturnGate.invalidate()
        assertFalse(lease.canDispatch(AssistantReturnGate.current(ticket)))
        assertFalse(lease.beginRestore(AssistantReturnGate.current(ticket)))
    }

    @Test fun changedReturnTicketCannotAcknowledgeOldHideOrRestore() {
        val old = AssistantReturnGate.beginTemporary()
        val lease = AssistantToolLeasePolicy()
        val newer = AssistantReturnGate.beginTemporary()
        assertTrue(AssistantReturnGate.current(newer))
        assertFalse(lease.acknowledgeHide(AssistantReturnGate.hasExpectedHide(), AssistantReturnGate.current(old)))
        assertFalse(lease.canDispatch(AssistantReturnGate.current(old)))
        assertFalse(lease.beginRestore(AssistantReturnGate.current(old)))
    }

    @Test fun pauseAfterHideBlocksNextActionAndRestoreEvenWithCurrentTicket() {
        val lease = AssistantToolLeasePolicy()
        assertTrue(lease.acknowledgeHide(expected = true, authorized = true))
        // Production authorization also checks paused, owner, surface identity and keyguard.
        assertFalse(lease.canDispatch(authorized = false))
        assertFalse(lease.beginRestore(authorized = false))
    }

    @Test fun restoreOnlyStartsOnceAndEndsDispatchPhase() {
        val lease = AssistantToolLeasePolicy()
        assertTrue(lease.acknowledgeHide(expected = true, authorized = true))
        assertTrue(lease.beginRestore(authorized = true))
        assertTrue(lease.restoring)
        assertFalse(lease.canDispatch(authorized = true))
        assertFalse(lease.beginRestore(authorized = true))
        assertFalse(lease.acknowledgeHide(expected = true, authorized = true))
    }
}
