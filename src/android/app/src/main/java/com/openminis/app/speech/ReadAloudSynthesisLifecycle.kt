package com.openminis.app.speech

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Main-thread confined queue. A reset revokes old work without joining a slow provider. */
internal class ReadAloudSynthesisLifecycle(
    private val scope: CoroutineScope,
    private val onPendingChanged: (Int) -> Unit,
    private val onFailure: (Exception) -> Unit,
    private val speak: suspend (String, Lease) -> Unit,
) {
    private val queue = Channel<String>(Channel.UNLIMITED)
    private var worker: Job? = null
    private var generation = 0L
    private var pending = 0

    inner class Lease internal constructor(private val generation: Long) {
        val isCurrent: Boolean get() = generation == this@ReadAloudSynthesisLifecycle.generation
        fun ensureCurrent() {
            if (!isCurrent) throw CancellationException("Speech superseded")
        }
    }

    fun enqueue(text: String) {
        onPendingChanged(++pending)
        queue.trySend(text).getOrThrow()
        if (worker != null) return
        val lease = Lease(generation)
        worker = scope.launch(start = CoroutineStart.LAZY) {
            for (text in queue) {
                lease.ensureCurrent()
                try {
                    speak(text, lease)
                } catch (cancelled: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    lease.ensureCurrent()
                } catch (failure: Exception) {
                    if (lease.isCurrent) onFailure(failure)
                } finally {
                    // Old synthesis may finish after new speech has already started.
                    if (lease.isCurrent) onPendingChanged(--pending)
                }
                lease.ensureCurrent()
            }
        }
        worker?.start()
    }

    /** Abandon the active utterance; return only those not yet started, in FIFO order. */
    fun reset(): List<String> {
        generation++ // Revoke before cancellation can run old finally blocks inline.
        worker?.cancel()
        worker = null // Deliberately no join: even an uncooperative provider cannot hold the queue.
        val held = mutableListOf<String>()
        while (true) held.add(queue.tryReceive().getOrNull() ?: break)
        pending = 0
        onPendingChanged(0)
        return held
    }
}
