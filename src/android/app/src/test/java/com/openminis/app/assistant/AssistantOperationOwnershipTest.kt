package com.openminis.app.assistant

import org.junit.Assert.*
import org.junit.Test

/** Pure policy tests; parent exercises the real gate / window callback chain on device. */
class AssistantOperationOwnershipTest {
    @Test fun ownedRequestCannotBecomeUnrelatedWhenConversationChangesDuringWait() {
        val ownership = AssistantOperationOwnership()
        val admission = ownership.admit("chat-a", "draft-a", "chat-a")
        assertTrue(admission.assistantOwned)
        assertTrue(ownership.current(admission, "draft-a", "chat-a"))
        assertFalse(ownership.current(admission, "draft-b", "chat-b"))
        assertFalse(ownership.current(admission, null, null))
    }

    @Test fun ownedRequestCannotPassThroughAfterMainHandoffDuringPermissionWait() {
        val ownership = AssistantOperationOwnership()
        val admission = ownership.admit("chat-a", "draft-a", "chat-a")
        ownership.handoff()
        assertTrue(admission.assistantOwned) // captured classification is never recomputed
        assertFalse(ownership.current(admission, "draft-a", "chat-a"))
        ownership.reclaim()
        assertFalse(ownership.current(admission, "draft-a", "chat-a"))
    }

    @Test fun pauseThenImmediateResumeCannotReviveDispatchOrRestore() {
        val ownership = AssistantOperationOwnership()
        val admission = ownership.admit("chat-a", "draft-a", "chat-a")
        val lease = AssistantToolLeasePolicy()
        assertTrue(lease.acknowledgeHide(true, ownership.current(admission, "draft-a", "chat-a")))
        ownership.invalidate() // Workspace.pauseOwnedTask; resume does not reset the epoch.
        val resumedAdmission = ownership.admit("chat-a", "draft-a", "chat-a")
        assertTrue(ownership.current(resumedAdmission, "draft-a", "chat-a"))
        assertFalse(lease.canDispatch(ownership.current(admission, "draft-a", "chat-a")))
        assertFalse(lease.beginRestore(ownership.current(admission, "draft-a", "chat-a")))
    }

    @Test fun freshMainRequestBypassesOverlayButReclaimInvalidatesIt() {
        val ownership = AssistantOperationOwnership()
        ownership.handoff()
        val main = ownership.admit("chat-a", "draft-a", "chat-a")
        assertFalse(main.assistantOwned)
        assertEquals("draft-a", main.workspaceKey) // tracked, unlike another chat
        assertTrue(ownership.current(main, "draft-a", "chat-a"))
        ownership.reclaim()
        assertFalse(ownership.current(main, "draft-a", "chat-a"))
        assertTrue(ownership.admit("chat-a", "draft-a", "chat-a").assistantOwned)
    }

    @Test fun unrelatedChatAndTerminalStayUnaffectedByPauseHandoffAndReclaim() {
        val ownership = AssistantOperationOwnership()
        val unrelated = listOf("other-chat", null, "").map { ownership.admit(it, "draft-a", "chat-a") }
        ownership.invalidate()
        ownership.handoff()
        ownership.reclaim()
        unrelated.forEach {
            assertFalse(it.assistantOwned)
            assertNull(it.workspaceKey)
            assertTrue(ownership.current(it, "draft-b", "chat-b"))
        }
    }

    @Test fun admissionIsReadOnlyAndDoesNotInvalidateAnExistingOperation() {
        val ownership = AssistantOperationOwnership()
        val first = ownership.admit("chat-a", "draft-a", "chat-a")
        val second = ownership.admit("chat-a", "draft-a", "chat-a")
        assertEquals(first.epoch, second.epoch)
        assertFalse(ownership.handedOff)
        assertTrue(ownership.current(first, "draft-a", "chat-a"))
    }
}
