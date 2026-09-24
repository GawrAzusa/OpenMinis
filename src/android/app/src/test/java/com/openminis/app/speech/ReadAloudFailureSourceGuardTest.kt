package com.openminis.app.speech

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Structural guards only; MediaPlayer completion/error/cancellation still require device QA. */
class ReadAloudFailureSourceGuardTest {
    private val root = File("src/main/java/com/openminis/app")
    private fun source(path: String): String = File(root, path).readText()
    private val player get() = source("speech/ReadAloudPlayer.kt")

    @Test fun chatKeepsFallbackButAssistantOptsOut() {
        assertTrue(player.contains("private val allowSystemFallback: Boolean = true"))
        assertTrue(source("assistant/AssistantWorkspace.kt")
            .contains("ReadAloudPlayer(application, allowSystemFallback = false)"))
        val strict = player.substringAfter("if (ok) return")
            .substringAfter("if (!allowSystemFallback) {").substringBefore("\n            }")
        assertTrue(strict.contains("OutputRoute(\"unavailable\""))
        assertTrue(strict.contains("notifySpeechUnavailable(providerConfigured = true)"))
        assertTrue(strict.contains("return"))
        assertFalse(strict.contains("speakViaSystem"))
        // Null entry still uses the existing system selection, not another provider/service.
        assertTrue(player.contains("OutputRoute(\"system\", fallback = entry != null)"))
    }

    @Test fun onlyCompletionUsesSuccessfulFinish() {
        val playback = player.substringAfter("private suspend fun playBytes")
            .substringBefore("// -- audio focus")
        assertTrue(playback.contains("mp.setOnCompletionListener { finish() }"))
        val error = playback.substringAfter("mp.setOnErrorListener").substringBefore("mp.prepare()")
        assertTrue(error.contains("finish(IllegalStateException("))
        assertFalse(error.contains("finish()"))
        assertTrue(playback.substringAfter("MediaPlayer setup failed:").contains("finish(it)"))
        assertTrue(playback.contains("if (failure == null) cont.resume(Unit)"))
        assertTrue(playback.contains("else cont.resumeWithException(failure)"))
    }

    @Test fun stopDoesNotBecomeSuccessOrFallbackAndWorkerCancellationIsPreserved() {
        assertTrue(player.contains("playbackFinisher = { finish(CancellationException("))
        assertTrue(player.contains("if (it is kotlinx.coroutines.CancellationException) throw it"))
        assertTrue(player.contains("currentCoroutineContext().ensureActive()"))
        assertTrue(player.contains("finish(CancellationException(\"Speech playback cancelled\"))"))
        assertTrue(player.contains("if (player === mp)"))
    }

    @Test fun synthesisAndRoutingUseRevocableLease() {
        assertTrue(player.contains("val held = lifecycle.reset().toMutableList()"))
        assertTrue(player.substringAfter("fun stop()").substringBefore("fun shutdown()")
            .contains("lifecycle.reset()"))
        assertTrue(player.contains("if (lease.isCurrent && ownsCapsule()) VoiceOutputState.isSynthesizing.value = false"))
        assertTrue(player.contains("checkCurrent(lease)\n        if (data.isEmpty())"))
        assertTrue(player.contains("checkCurrent(lease)\n            playFile(file)"))
        assertTrue(player.contains("checkCurrent(lease)\n            if (ok) return"))
        assertTrue(player.contains("if (capturePaused) return\n        lifecycle.enqueue(clean)"))
    }

    @Test fun networkCancellationCancelsOkHttpAndClosesResponseBody() {
        val request = source("provider/voice/VoiceProvider.kt")
            .substringAfter("suspend fun executeRequest").substringBefore("// -- Chat-based ASR")
        assertTrue(request.contains("suspendCancellableCoroutine"))
        assertTrue(request.contains("cont.invokeOnCancellation { call.cancel() }"))
        assertTrue(request.contains("call.enqueue(object : Callback"))
        assertTrue(request.contains("response.use {"))
        assertFalse(request.contains(".execute()"))
    }

    @Test fun errorStateCannotSkipPlayerRelease() {
        assertTrue(player.contains("runCatching { if (mp.isPlaying) mp.stop() }"))
        assertTrue(player.contains("runCatching { mp.release() }"))
    }
}
