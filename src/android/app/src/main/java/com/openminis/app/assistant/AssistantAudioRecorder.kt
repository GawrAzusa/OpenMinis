package com.openminis.app.assistant

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

/** Explicit-start, memory-only capture. The owner MUST cancel on hide/destroy. No ASR. */
class AssistantAudioRecorder(context: Context) {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var session: Capture? = null

    private class Capture(val recorder: AudioRecord) {
        val running = AtomicBoolean(true)
        val cancelled = AtomicBoolean(false)
        val released = AtomicBoolean(false)
        val done = CountDownLatch(1)
        val pcm = ByteArrayOutputStream()
        @Volatile var failed = false
        fun release() {
            running.set(false)
            if (released.compareAndSet(false, true)) {
                runCatching { recorder.stop() }
                runCatching { recorder.release() }
            }
        }
    }

    @Synchronized
    fun start(onLevel: (Float) -> Unit, onError: (String) -> Unit): Boolean {
        if (session != null) { onError("A recording is already active."); return false }
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            onError("Microphone permission is required."); return false
        }
        var recorder: AudioRecord? = null
        try {
            val minimum = AudioRecord.getMinBufferSize(AssistantAudioSupport.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(minimum > 0) { "PCM microphone capture is unavailable." }
            val native = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,
                AssistantAudioSupport.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, 4096))
            recorder = native
            check(native.state == AudioRecord.STATE_INITIALIZED) { "Microphone initialization failed." }
            native.startRecording()
            check(native.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Microphone did not start." }
            val capture = Capture(native)
            session = capture
            Thread({
                try {
                    val buffer = ByteArray(2048)
                    while (capture.running.get() && capture.pcm.size() < AssistantAudioSupport.MAX_PCM_BYTES) {
                        val count = native.read(buffer, 0, minOf(buffer.size,
                            AssistantAudioSupport.MAX_PCM_BYTES - capture.pcm.size()))
                        if (!capture.running.get()) break
                        check(count > 0 && count % 2 == 0) { "Microphone capture failed ($count)." }
                        capture.pcm.write(buffer, 0, count)
                        var power = 0.0
                        for (i in 0 until count step 2) {
                            val sample = ((buffer[i].toInt() and 255) or (buffer[i + 1].toInt() shl 8)).toShort().toDouble() / 32768
                            power += sample * sample
                        }
                        val level = sqrt(power / (count / 2)).toFloat().coerceIn(0f, 1f)
                        main.post { if (isCurrent(capture) && capture.running.get()) onLevel(level) }
                    }
                    // Bound capture even when the user never taps stop. Keep bytes for explicit stop/send.
                } catch (e: Exception) {
                    if (capture.running.get()) {
                        capture.failed = true
                        main.post { if (isCurrent(capture) && !capture.cancelled.get()) onError(e.message ?: "Recording failed.") }
                    }
                } finally {
                    capture.release()
                    capture.done.countDown()
                }
            }, "AssistantAudioCapture").start()
            return true
        } catch (e: Exception) {
            runCatching { recorder?.release() }
            session = null
            onError(e.message ?: "Unable to open microphone.")
            return false
        }
    }

    @Synchronized private fun isCurrent(capture: Capture) = session === capture

    suspend fun stop(): ByteArray? {
        val capture = synchronized(this) { session } ?: return null
        capture.release()
        return try {
            withContext(Dispatchers.IO) {
                capture.done.await()
                if (capture.cancelled.get() || capture.failed || capture.pcm.size() == 0) null
                else AssistantAudioSupport.wav(capture.pcm.toByteArray())
            }
        } finally {
            synchronized(this) { if (session === capture) session = null }
            capture.release()
        }
    }

    @Synchronized
    fun cancel() {
        val capture = session ?: return
        session = null
        capture.cancelled.set(true)
        capture.release()
    }
}
