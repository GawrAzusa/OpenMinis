package com.openminis.app.assistant

/** Process-local user-action lease. No deferred send/reopen survives an external dismissal. */
object AssistantReturnGate {
    private var generation = 0L
    private var expectedHide = false
    @Synchronized fun beginTemporary(): Long { expectedHide = true; return ++generation }
    @Synchronized fun invalidate() { generation++; expectedHide = false }
    @Synchronized fun hasExpectedHide(): Boolean = expectedHide
    @Synchronized fun current(ticket: Long): Boolean = ticket == generation
    @Synchronized fun consumeExpectedHide(): Boolean = expectedHide.also { expectedHide = false }
}
