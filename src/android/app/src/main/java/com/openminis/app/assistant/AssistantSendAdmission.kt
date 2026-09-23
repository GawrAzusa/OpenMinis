package com.openminis.app.assistant

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Main-thread, one-shot acknowledgement; starting setup is not durable admission. */
class AssistantSendAdmission(private val onSettled: (Boolean) -> Unit) {
    var committed: Boolean = false
        private set
    private var settled = false

    /** Only local DB/history commit is shielded, never model/tool execution. */
    suspend fun commit(block: suspend () -> Unit) {
        currentCoroutineContext().ensureActive()
        withContext(NonCancellable) {
            block()
            committed = true
            settle(true)
        }
        currentCoroutineContext().ensureActive()
    }

    fun reject() = settle(false)

    private fun settle(accepted: Boolean) {
        if (settled) return
        settled = true
        onSettled(accepted)
    }
}
