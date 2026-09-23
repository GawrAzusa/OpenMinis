package com.openminis.app.assistant

import java.util.UUID

/** One-shot, process-local handoff. A session URL alone cannot start recording. */
object AssistantLaunch {
    private var pendingSessionId: String? = null

    fun isAssistAction(action: String?): Boolean =
        action == "android.intent.action.ASSIST" || action == "android.intent.action.VOICE_ASSIST"

    @Synchronized
    fun newSessionId(): String = "__new__${UUID.randomUUID()}".also { pendingSessionId = it }

    @Synchronized
    fun cancelPending() { pendingSessionId = null }

    @Synchronized
    fun consume(sessionId: String): Boolean {
        if (pendingSessionId != sessionId) return false
        pendingSessionId = null
        return true
    }
}
