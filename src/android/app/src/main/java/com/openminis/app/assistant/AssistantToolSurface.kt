package com.openminis.app.assistant

import android.os.Looper
import com.openminis.app.ui.chat.ChatViewModelStore
import kotlinx.coroutines.*

internal fun assistantToolOwns(requestSessionId: String?, persistedWorkspaceId: String?): Boolean =
    !requestSessionId.isNullOrBlank() && requestSessionId == persistedWorkspaceId

/** Main-thread, process-local identity captured before any permission wait. */
internal class AssistantOperationOwnership {
    var epoch = 0L
        private set
    var handedOff = false
        private set

    fun invalidate() { epoch++ }
    fun handoff() { invalidate(); handedOff = true }
    fun reclaim() { invalidate(); handedOff = false }

    fun admit(sessionId: String?, workspaceKey: String?, persistedId: String?): AssistantToolSurface.Admission {
        val matches = assistantToolOwns(sessionId, persistedId)
        return AssistantToolSurface.Admission(sessionId, if (matches) workspaceKey else null,
            epoch, matches && !handedOff)
    }

    fun current(admission: AssistantToolSurface.Admission, workspaceKey: String?, persistedId: String?): Boolean =
        admission.workspaceKey == null || (admission.workspaceKey == workspaceKey &&
            admission.epoch == epoch && assistantToolOwns(admission.sessionId, persistedId) &&
            admission.assistantOwned == !handedOff)
}

/** Main-thread policy, separate from Android so hide/dispatch races can be unit tested. */
internal class AssistantToolLeasePolicy {
    var hidden = false
        private set
    var restoring = false
        private set

    fun acknowledgeHide(expected: Boolean, authorized: Boolean): Boolean {
        if (!expected || !authorized || hidden || restoring) return false
        hidden = true
        return true
    }

    fun canDispatch(authorized: Boolean): Boolean = hidden && !restoring && authorized
    fun beginRestore(authorized: Boolean): Boolean {
        if (!canDispatch(authorized)) return false
        restoring = true
        return true
    }
}

/**
 * A lease on the *existing* voice window, not a background task runner. Only the matching
 * workspace chat participates. All lifecycle/identity checks run on Main; native blocking
 * accessibility calls remain on their sandbox worker. Never call this bridge from Main.
 */
object AssistantToolSurface {
    internal const val RESTORE_TICKET = "assistant_tool_restore_ticket"
    private const val BRIDGE_TIMEOUT_MS = 2500L
    private var session: MinisVoiceSession? = null
    private var active: Lease? = null
    private val workerLease = ThreadLocal<Lease?>()
    private val workerAdmission = ThreadLocal<Admission?>()
    private val repairWaiters = mutableMapOf<Any, String?>()

    class Admission internal constructor(
        internal val sessionId: String?, internal val workspaceKey: String?,
        internal val epoch: Long, internal val assistantOwned: Boolean,
    )

    /** Snapshot only: no window hide, pause or permission prompt. Call BEFORE either gate. */
    fun admit(sessionId: String?): Admission {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Visual offload must run on a sandbox worker" }
        return runBlocking {
            withTimeoutOrNull(BRIDGE_TIMEOUT_MS) {
                withContext(Dispatchers.Main.immediate) {
                    AssistantWorkspace.toolOwnership.admit(sessionId, AssistantWorkspace.sessionKey, persistedId())
                }
            } ?: throw Unavailable("Assistant ownership could not be checked; no action dispatched.")
        }
    }

    private fun persistedId(): String? = AssistantWorkspace.sessionKey?.let { ChatViewModelStore.resolvePersistedId(it) }
    private fun current(admission: Admission): Boolean =
        AssistantWorkspace.toolOwnership.current(admission, AssistantWorkspace.sessionKey, persistedId())

    /** Attribution only: never responds to the process-global recovery prompt. */
    suspend fun <T> withRepairGuidance(sessionId: String?, operation: suspend () -> T): T {
        val token = Any()
        try {
            withTimeout(BRIDGE_TIMEOUT_MS) {
                withContext(Dispatchers.Main.immediate) {
                    repairWaiters[token] = sessionId
                    session?.refreshRepairGuidance()
                }
            }
            return operation()
        } finally {
            // No blocking handoff in cancellation cleanup; mutate only when Main runs it.
            android.os.Handler(Looper.getMainLooper()).post {
                repairWaiters.remove(token)
                session?.refreshRepairGuidance()
            }
        }
    }

    internal fun ownRepairPending(): Boolean = repairWaiters.values.any { owns(it) }

    class Unavailable(message: String) : IllegalStateException(message)

    private class Lease(val surface: MinisVoiceSession, val admission: Admission, val ticket: Long) {
        val sessionId: String get() = admission.sessionId!!
        val policy = AssistantToolLeasePolicy()
        val hidden = CompletableDeferred<Unit>()
        val restored = CompletableDeferred<Unit>()
    }

    internal fun attach(surface: MinisVoiceSession) { session = surface }
    internal fun interruptForShow(surface: MinisVoiceSession) {
        if (active?.surface === surface) {
            AssistantReturnGate.invalidate()
            AssistantWorkspace.pause()
        }
    }
    internal fun detach(surface: MinisVoiceSession) {
        if (session === surface) session = null
    }

    private fun owns(sessionId: String?): Boolean = !AssistantWorkspace.toolOwnership.handedOff &&
        assistantToolOwns(sessionId, persistedId())

    private fun authorized(lease: Lease): Boolean = active === lease && session === lease.surface &&
        current(lease.admission) && owns(lease.sessionId) && AssistantReturnGate.current(lease.ticket) &&
        !AssistantWorkspace.state.value.paused && !lease.surface.toolSurfaceLocked()

    internal fun onHide(surface: MinisVoiceSession, expected: Boolean): Boolean {
        val lease = active ?: return false
        if (lease.surface !== surface || !lease.policy.acknowledgeHide(expected, authorized(lease))) return false
        lease.hidden.complete(Unit)
        return true
    }

    /** A delayed system show callback must not resurrect an invalidated return ticket. */
    internal fun acceptRestore(surface: MinisVoiceSession, ticket: Long): Boolean {
        val lease = active ?: return false
        return lease.surface === surface && lease.ticket == ticket && lease.policy.restoring && authorized(lease)
    }

    internal fun onRestored(surface: MinisVoiceSession) {
        active?.takeIf { it.surface === surface && it.policy.restoring }?.restored?.complete(Unit)
    }

    /** Exact implemented visual verbs only: help, diagnostics and event watchers don't hide. */
    fun isVisual(group: String, verb: String?): Boolean = when (group) {
        "ui" -> verb in setOf("dump", "find", "info", "node", "screenshot")
        "tap" -> verb in setOf("node", "xy", "text", "id")
        "input" -> verb in setOf("text", "clear", "key")
        "scroll" -> verb in setOf("node", "xy", "to-text")
        "gesture" -> verb in setOf("swipe", "pinch", "path")
        "wait" -> verb in setOf("appear", "disappear", "stable", "activity")
        "dialog" -> verb in setOf("detect", "dismiss")
        "extract" -> verb in setOf("text", "list", "form")
        else -> false
    }

    /** Convenience for callers with no asynchronous permission gates. */
    fun <T> withUnderlyingApp(sessionId: String?, operation: () -> T): T = withUnderlyingApp(admit(sessionId), operation)

    /** Hide AFTER both gates, but never reclassify a formerly owned admission as unrelated. */
    fun <T> withUnderlyingApp(admission: Admission, operation: () -> T): T {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Visual offload must run on a sandbox worker" }
        var lease: Lease? = null
        return runBlocking {
            try {
                withTimeout(BRIDGE_TIMEOUT_MS) {
                    withContext(Dispatchers.Main.immediate) {
                        if (!current(admission)) throw Unavailable("Assistant ownership changed while waiting; retry explicitly.")
                        if (!admission.assistantOwned) return@withContext
                        val surface = session
                        if (surface == null || !surface.toolSurfaceShown() || surface.toolSurfaceLocked() ||
                            AssistantWorkspace.state.value.paused || active != null || AssistantReturnGate.hasExpectedHide()) {
                            throw Unavailable("Assistant is paused, hidden, or changing surfaces; continue explicitly before using phone controls.")
                        }
                        // Assign inside Main before requesting hide, including a synchronous onHide.
                        val acquired = Lease(surface, admission, AssistantReturnGate.beginTemporary())
                        lease = acquired
                        active = acquired
                        surface.hide()
                        acquired.hidden.await()
                        // Let the framework finish its hide transaction/IME transition, then recheck.
                        delay(100)
                        if (!acquired.policy.canDispatch(authorized(acquired))) throw Unavailable("Assistant tool hide was interrupted.")
                    }
                }
                workerLease.set(lease)
                workerAdmission.set(admission)
                checkDispatch()
                operation()
            } catch (e: TimeoutCancellationException) {
                throw Unavailable("Assistant window did not acknowledge the transition in time; no new action dispatched.")
            } finally {
                workerLease.remove()
                workerAdmission.remove()
                // Cancellation/interruption must still release the lease. Bound the Main handoff
                // and actual restore callback as well; a late callback fails acceptRestore.
                withContext(NonCancellable) {
                    try {
                        withTimeout(BRIDGE_TIMEOUT_MS) {
                            withContext(Dispatchers.Main.immediate) {
                                val acquired = lease ?: return@withContext
                                try {
                                    if (acquired.policy.beginRestore(authorized(acquired))) {
                                        acquired.surface.restoreToolSurface(acquired.ticket)
                                        acquired.restored.await()
                                    }
                                } finally {
                                    if (active === acquired) {
                                        active = null
                                        if (!acquired.restored.isCompleted && current(acquired.admission) && owns(acquired.sessionId) &&
                                            session === acquired.surface && AssistantReturnGate.current(acquired.ticket)) {
                                            AssistantReturnGate.invalidate()
                                            AssistantWorkspace.pause()
                                        }
                                    }
                                }
                            }
                        }
                    } catch (_: TimeoutCancellationException) {
                        // Main may have been unavailable even before entry. A main-queued cleanup
                        // is safe to run late, but must never reopen a window or perform an action.
                        lease?.let { acquired ->
                            android.os.Handler(Looper.getMainLooper()).post {
                                if (active === acquired) {
                                    active = null
                                    if (current(acquired.admission) && owns(acquired.sessionId) && session === acquired.surface && AssistantReturnGate.current(acquired.ticket)) {
                                        AssistantReturnGate.invalidate()
                                        AssistantWorkspace.pause()
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /** Repeat immediately before every native action and after polling sleeps, not just entry. */
    fun checkDispatch() {
        val admission = workerAdmission.get() ?: return
        if (admission.workspaceKey == null) return // Unrelated chat / interactive terminal.
        val lease = workerLease.get()
        val allowed = runBlocking {
            withTimeoutOrNull(BRIDGE_TIMEOUT_MS) {
                withContext(Dispatchers.Main.immediate) {
                    current(admission) && (lease == null || lease.policy.canDispatch(authorized(lease)))
                }
            } == true
        }
        if (!allowed) throw Unavailable("Assistant was paused or dismissed; phone controls stopped.")
    }
}
