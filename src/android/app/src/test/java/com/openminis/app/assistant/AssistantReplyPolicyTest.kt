package com.openminis.app.assistant

import com.openminis.app.ui.chat.ChatMessage
import org.junit.Assert.*
import org.junit.Test

class AssistantReplyPolicyTest {
    private fun answer(id: String, text: String, error: String? = null, streaming: Boolean = false) =
        ChatMessage(id = id, role = "assistant", content = text, error = error, isStreaming = streaming)

    @Test fun onlyCompletedLastAnswerIsEligible() {
        assertEquals("final", completedAssistantReply(listOf(answer("intermediate", "正在处理"), answer("final", "好了")))?.id)
    }
    @Test fun partialFailureDoesNotFallBackToEarlierNarration() {
        assertNull(completedAssistantReply(listOf(answer("narration", "正在处理"), answer("failed", "只输出了一半", "连接中断"))))
    }
    @Test fun inFlightAnswerIsNotCompletion() { assertNull(completedAssistantReply(listOf(answer("a", "处理中", streaming = true)))) }
    @Test fun blankReplyIsNotVoiced() { assertNull(completedAssistantReply(listOf(answer("a", " ")))) }
    @Test fun noAssistantAnswerHasNoSpeech() { assertNull(completedAssistantReply(emptyList())) }
}
