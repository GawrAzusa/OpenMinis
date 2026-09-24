package com.openminis.app.assistant

/** At most one final utterance per explicit invocation; never send interim/late results. */
class AssistantVoiceTurn {
    var pending: Boolean = false
        private set
    var armed: Boolean = false
        private set

    fun request() { pending = true }

    fun arm(): Boolean {
        if (!pending) return false
        pending = false
        armed = true
        return true
    }

    fun cancel(): Boolean = (pending || armed).also {
        pending = false
        armed = false
    }

    fun takeFinal(text: String, isFinal: Boolean, foregroundAndUnlocked: Boolean): String? {
        if (!foregroundAndUnlocked) {
            cancel()
            return null
        }
        if (!armed || !isFinal || text.isBlank()) return null
        armed = false
        return text.trim()
    }
}
