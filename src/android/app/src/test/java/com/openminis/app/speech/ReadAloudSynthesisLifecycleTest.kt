package com.openminis.app.speech

import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Exercises the production queue with a deliberately cancellation-uncooperative synthesis fake. */
@OptIn(ExperimentalCoroutinesApi::class)
class ReadAloudSynthesisLifecycleTest {
    @Test fun blockedSynthesisStopAndNewSpeakRejectsLateAudioAndOldCounters() = runTest {
        lateinit var old: Continuation<Unit>
        val replacement = CompletableDeferred<Unit>()
        val started = mutableListOf<String>()
        val played = mutableListOf<String>()
        val counts = mutableListOf<Int>()
        var synthesizing: String? = null
        val lifecycle = ReadAloudSynthesisLifecycle(backgroundScope, counts::add, { throw it }) { text, lease ->
            started.add(text)
            synthesizing = text
            try {
                if (text == "old") suspendCoroutine<Unit> { old = it }
                else replacement.await()
            } finally {
                if (lease.isCurrent) synthesizing = null
            }
            lease.ensureCurrent()
            played.add(text)
        }
        lifecycle.enqueue("old")
        lifecycle.enqueue("discarded")
        runCurrent()
        assertEquals(listOf("discarded"), lifecycle.reset())
        lifecycle.enqueue("new")
        runCurrent()
        assertEquals(listOf("old", "new"), started) // New starts before old responds.
        assertEquals("new", synthesizing)
        val beforeLateCompletion = counts.toList()
        old.resume(Unit)
        runCurrent()
        assertTrue(played.isEmpty())
        assertEquals("new", synthesizing)
        assertEquals(beforeLateCompletion, counts)
        replacement.complete(Unit)
        runCurrent()
        assertEquals(listOf("new"), played)
        assertEquals(0, counts.last())
    }

    @Test fun lateProviderFailureCannotFallBackOrReportUnavailableForReplacement() = runTest {
        lateinit var old: Continuation<Unit>
        val replacement = CompletableDeferred<Unit>()
        val failures = mutableListOf<Exception>()
        val fallback = mutableListOf<String>()
        val counts = mutableListOf<Int>()
        val lifecycle = ReadAloudSynthesisLifecycle(backgroundScope, counts::add, failures::add) { text, lease ->
            try {
                if (text == "old") suspendCoroutine<Unit> { old = it }
                else replacement.await()
            } catch (failure: Exception) {
                // Same pre-fallback guard used by ReadAloudPlayer.
                lease.ensureCurrent()
                fallback.add(text)
            }
        }
        lifecycle.enqueue("old")
        runCurrent()
        lifecycle.reset()
        lifecycle.enqueue("new")
        runCurrent()
        old.resumeWithException(IllegalStateException("late provider failure"))
        runCurrent()
        assertTrue(fallback.isEmpty())
        assertTrue(failures.isEmpty())
        assertEquals(1, counts.last())
        replacement.complete(Unit)
        runCurrent()
        assertEquals(0, counts.last())
    }

    @Test fun capturePauseAbandonsActiveSynthesisButPreservesQueuedOrderOnResume() = runTest {
        lateinit var active: Continuation<Unit>
        val played = mutableListOf<String>()
        val counts = mutableListOf<Int>()
        val lifecycle = ReadAloudSynthesisLifecycle(backgroundScope, counts::add, { throw it }) { text, lease ->
            if (text == "active") suspendCoroutine<Unit> { active = it }
            lease.ensureCurrent()
            played.add(text)
        }
        lifecycle.enqueue("active")
        lifecycle.enqueue("second")
        lifecycle.enqueue("third")
        runCurrent()
        val held = lifecycle.reset() // suspendForCapture uses this exact operation.
        assertEquals(listOf("second", "third"), held)
        assertEquals(0, counts.last())
        active.resume(Unit)
        runCurrent()
        assertTrue(played.isEmpty()) // Nothing leaks into the open microphone.
        assertEquals(0, counts.last())
        held.forEach(lifecycle::enqueue) // resumeAfterCapture re-applies enqueue gates.
        runCurrent()
        assertEquals(listOf("second", "third"), played)
        assertEquals(0, counts.last())
    }

    @Test fun ordinaryQueuedSpeechRemainsSequentialAndActiveFailuresDoNotWedgeIt() = runTest {
        val first = CompletableDeferred<Unit>()
        val started = mutableListOf<String>()
        val failures = mutableListOf<Exception>()
        val counts = mutableListOf<Int>()
        val lifecycle = ReadAloudSynthesisLifecycle(backgroundScope, counts::add, failures::add) { text, _ ->
            started.add(text)
            if (text == "first") {
                first.await()
                throw IllegalStateException("current utterance failed")
            }
        }
        lifecycle.enqueue("first")
        lifecycle.enqueue("second")
        runCurrent()
        assertEquals(listOf("first"), started)
        assertEquals(2, counts.last())
        first.complete(Unit)
        runCurrent()
        assertEquals(listOf("first", "second"), started)
        assertEquals(1, failures.size)
        assertEquals(0, counts.last())
        lifecycle.enqueue("third")
        runCurrent()
        assertEquals(listOf("first", "second", "third"), started)
        assertEquals(0, counts.last())
        assertTrue(counts.all { it >= 0 })
    }
}
