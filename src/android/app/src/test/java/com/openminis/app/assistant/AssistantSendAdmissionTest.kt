package com.openminis.app.assistant

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class AssistantSendAdmissionTest {
    @Test fun cancelBeforeCommitRetainsInput() = runBlocking {
        val events = mutableListOf<Boolean>()
        val admission = AssistantSendAdmission { events.add(it) }
        var writes = 0
        val ready = CompletableDeferred<Unit>()
        val job = launch {
            try {
                ready.complete(Unit)
                awaitCancellation() // ensureSession/attachment setup
                @Suppress("UNREACHABLE_CODE")
                admission.commit { writes++ }
            } finally { admission.reject() }
        }
        ready.await(); job.cancelAndJoin()
        assertEquals(0, writes)
        assertEquals(listOf(false), events)
        assertFalse(admission.committed)
    }

    @Test fun cancelDuringCommitAcknowledgesOnceAndDoesNotRunModel() = runBlocking {
        val events = mutableListOf<Boolean>()
        val admission = AssistantSendAdmission { events.add(it) }
        val inserted = CompletableDeferred<Unit>()
        val finishDb = CompletableDeferred<Unit>()
        var writes = 0
        var history = 0
        var modelCalls = 0
        val job = launch {
            try {
                admission.commit {
                    writes++
                    inserted.complete(Unit)
                    finishDb.await() // pause while DB transaction is completing
                    history++
                }
                modelCalls++
            } finally { admission.reject() }
        }
        inserted.await(); job.cancel()
        assertTrue(events.isEmpty())
        finishDb.complete(Unit); job.join()
        admission.reject() // completion callback is also safe
        assertEquals(1, writes)
        assertEquals(1, history)
        assertEquals(0, modelCalls)
        assertEquals(listOf(true), events)
        assertTrue(admission.committed)
    }

    @Test fun failureBeforeCommitRetainsInput() = runBlocking {
        val events = mutableListOf<Boolean>()
        val admission = AssistantSendAdmission { events.add(it) }
        try {
            admission.commit { throw IllegalStateException("transaction rolled back") }
            fail("must fail")
        } catch (_: IllegalStateException) {
            admission.reject()
        }
        admission.reject()
        assertEquals(listOf(false), events)
        assertFalse(admission.committed)
    }

    @Test fun cancelAfterCommitCannotRestoreDuplicateDraft() = runBlocking {
        val events = mutableListOf<Boolean>()
        val admission = AssistantSendAdmission { events.add(it) }
        val ready = CompletableDeferred<Unit>()
        val job = launch {
            try {
                admission.commit { }
                ready.complete(Unit)
                awaitCancellation()
            } finally { admission.reject() }
        }
        ready.await(); job.cancelAndJoin()
        assertEquals(listOf(true), events)
    }

    @Test fun cancelledLazySetupStillRejects() = runBlocking {
        val events = mutableListOf<Boolean>()
        val admission = AssistantSendAdmission { events.add(it) }
        var writes = 0
        val job = launch(start = CoroutineStart.LAZY) { admission.commit { writes++ } }
        job.invokeOnCompletion { admission.reject() }
        job.cancelAndJoin()
        assertEquals(0, writes)
        assertEquals(listOf(false), events)
    }

    @Test fun resumeSetupCannotDispatchAfterPauseEvenWhenHelperSwallowsCancellation() = runBlocking {
        val ready = CompletableDeferred<Unit>()
        var modelCalls = 0
        val setup = launch(start = CoroutineStart.LAZY) {
            try {
                ready.complete(Unit)
                awaitCancellation()
            } catch (_: CancellationException) { /* legacy OAuth/prompt helper */ }
            currentCoroutineContext().ensureActive()
            launch { modelCalls++ }
        }
        setup.start(); ready.await(); setup.cancelAndJoin()
        assertEquals(0, modelCalls)
    }
}
