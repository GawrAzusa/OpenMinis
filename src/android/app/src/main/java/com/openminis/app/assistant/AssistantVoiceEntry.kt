package com.openminis.app.assistant

/** Snapshot before model readiness suspends; pause and replacement must invalidate it. */
internal data class AssistantVoiceRequest(val sessionKey: String?, val epoch: Int) {
    fun isCurrent(currentKey: String?, currentEpoch: Int, visible: Boolean, handedOff: Boolean) =
        visible && !handedOff && sessionKey != null && sessionKey == currentKey && epoch == currentEpoch
}

/** One process-local microphone permission continuation, never restored from saved state. */
internal class AssistantVoiceEntry {
    private var microphoneTicket: Long? = null
    private var pendingOnlyEmptyComposer = true
    var onlyEmptyComposer = true
        private set

    fun awaitingMicrophone(ticket: Long, onlyEmptyComposer: Boolean = true) {
        microphoneTicket = ticket
        pendingOnlyEmptyComposer = onlyEmptyComposer
    }
    fun cancel() { microphoneTicket = null }

    fun shouldListen(
        explicitInvocation: Boolean,
        restoring: Boolean,
        returnTicket: Long?,
        microphoneGranted: Boolean,
    ): Boolean {
        if (explicitInvocation && !restoring && returnTicket == null) {
            cancel()
            onlyEmptyComposer = true
            return true
        }
        val matches = returnTicket != null && returnTicket == microphoneTicket
        if (matches && microphoneGranted) onlyEmptyComposer = pendingOnlyEmptyComposer
        // A return (including denial) consumes the continuation exactly once.
        cancel()
        return matches && microphoneGranted
    }
}
