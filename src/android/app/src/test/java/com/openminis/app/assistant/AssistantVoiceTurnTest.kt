package com.openminis.app.assistant

import org.junit.Assert.*
import org.junit.Test

class AssistantVoiceTurnTest {
    @Test fun `ordinary dictation does not submit automatically`() {
        assertNull(AssistantVoiceTurn().takeFinal("hello", true, true))
    }
    @Test fun `cancelling a pending permission request prevents later capture`() {
        val turn = AssistantVoiceTurn().apply { request() }
        assertTrue(turn.pending)
        assertTrue(turn.cancel())
        assertFalse(turn.arm())
        assertNull(turn.takeFinal("late", true, true))
    }
    @Test fun `a recreated screen has no pending authorization`() {
        val recreated = AssistantVoiceTurn()
        assertFalse(recreated.pending)
        assertFalse(recreated.arm())
    }
    @Test fun `blank and partial results are ignored and first final is submitted once`() {
        val turn = AssistantVoiceTurn().apply { request(); assertTrue(arm()) }
        assertNull(turn.takeFinal("partial", false, true))
        assertNull(turn.takeFinal("  ", true, true))
        assertEquals("hello", turn.takeFinal(" hello ", true, true))
        assertFalse(turn.armed)
        assertNull(turn.takeFinal("duplicate", true, true))
    }
    @Test fun `cancellation rejects late transcripts`() {
        val turn = AssistantVoiceTurn().apply { request(); assertTrue(arm()) }
        assertTrue(turn.cancel())
        assertFalse(turn.cancel())
        assertNull(turn.takeFinal("late result", true, true))
    }
    @Test fun `background or locked callback disarms the turn`() {
        val turn = AssistantVoiceTurn().apply { request(); assertTrue(arm()) }
        assertNull(turn.takeFinal("private", true, false))
        assertFalse(turn.armed)
        assertNull(turn.takeFinal("later", true, true))
    }
}
