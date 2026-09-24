package com.openminis.app.assistant

import android.Manifest
import android.media.AudioManager
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.openminis.app.speech.ProviderSpeechRecognitionEngine
import com.openminis.app.speech.SegmentEndReason
import com.openminis.app.speech.VoiceActivityDetector
import com.openminis.app.speech.VoiceActivityListener
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises a real local AudioRecord/VAD, without a cloud provider or uploaded audio. */
@RunWith(AndroidJUnit4::class)
class ProviderCaptureCancellationTest {
    @get:Rule val microphonePermission: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    @Test fun cancellingProviderStopsItsOwnedMicrophone() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val audio = context.getSystemService(AudioManager::class.java)
        val engine = ProviderSpeechRecognitionEngine(context)
        val detector = VoiceActivityDetector(context, object : VoiceActivityListener {
            override fun onVoiceEnd(wav: ByteArray, reason: SegmentEndReason, spokenSeconds: Float) {}
        })
        // Isolate capture ownership from provider configuration/network access.
        // The real provider cancellation method must tear down the real detector.
        val field = ProviderSpeechRecognitionEngine::class.java.getDeclaredField("detector")
            .apply { isAccessible = true }
        field.set(engine, detector)
        try {
            assertNull("VAD must start successfully", detector.start())
            await("AudioRecord did not become active") { audio.activeRecordingConfigurations.isNotEmpty() }
            assertTrue(detector.isRunning)
            engine.cancel()
            assertFalse("Detector must be stopped, not just the manager state", detector.isRunning)
            assertNull("Provider must release its detector reference", field.get(engine))
            await("Microphone remained active after cancel") { audio.activeRecordingConfigurations.isEmpty() }
        } finally {
            engine.cancel()
            detector.cancel()
        }
    }

    private fun await(message: String, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 8_000
        while (!predicate() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
        assertTrue(message, predicate())
    }
}
