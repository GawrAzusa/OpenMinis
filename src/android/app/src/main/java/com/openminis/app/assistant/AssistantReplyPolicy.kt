package com.openminis.app.assistant

import com.openminis.app.ui.chat.ChatMessage

/** Idle can mean failure/cancellation; a failed last row must not fall back to earlier narration. */
internal fun completedAssistantReply(messages: List<ChatMessage>): ChatMessage? =
    messages.lastOrNull { it.role == "assistant" }?.takeIf {
        !it.isStreaming && it.error == null && it.content.isNotBlank()
    }
