package com.openminis.app.assistant

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/** Main-thread ownership of a one-shot transcript. Cancellation also rejects late/non-cooperative replies. */
internal class AssistantSplitTranscription {
    data class Ticket(val session: String?, val capture: Int, val draft: String, val revision: Long)
    sealed interface Result {
        data class Text(val draft: String) : Result
        data object Blank : Result
        data object Stale : Result
        data object Failure : Result
    }

    private var active: Ticket? = null

    fun begin(session: String?, capture: Int, draft: String, revision: Long): Ticket? {
        if (active != null) return null
        return Ticket(session, capture, draft, revision).also { active = it }
    }

    fun cancel() { active = null }

    /** Provider suspension is injected so tests can exercise real late completion/cancellation. */
    suspend fun run(ticket: Ticket, transcribe: suspend () -> String, current: () -> Ticket?): Result {
        try {
            val text = try {
                transcribe().also { coroutineContext.ensureActive() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                coroutineContext.ensureActive()
                val now = current() ?: return Result.Stale
                return fail(ticket, now.session, now.capture, now.draft, now.revision, true)
            }
            val now = current() ?: return Result.Stale
            return complete(ticket, text, now.session, now.capture, now.draft, now.revision, true)
        } finally {
            if (active === ticket) active = null
        }
    }

    private fun claim(ticket: Ticket, session: String?, capture: Int, draft: String, revision: Long,
                      visible: Boolean): Boolean {
        if (active !== ticket) return false
        active = null // Every outcome is consumed exactly once, including blanks/failures.
        return visible && session != null && ticket.session == session && ticket.capture == capture &&
            ticket.draft == draft && ticket.revision == revision
    }

    fun complete(ticket: Ticket, text: String, session: String?, capture: Int, draft: String,
                 revision: Long, visible: Boolean): Result {
        if (!claim(ticket, session, capture, draft, revision, visible)) return Result.Stale
        val transcript = text.trim()
        if (transcript.isEmpty()) return Result.Blank
        return Result.Text(listOf(ticket.draft.trim(), transcript).filter { it.isNotEmpty() }.joinToString("\n"))
    }

    fun fail(ticket: Ticket, session: String?, capture: Int, draft: String, revision: Long,
             visible: Boolean): Result =
        if (claim(ticket, session, capture, draft, revision, visible)) Result.Failure else Result.Stale
}
