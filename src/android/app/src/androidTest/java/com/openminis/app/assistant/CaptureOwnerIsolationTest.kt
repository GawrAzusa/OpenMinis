package com.openminis.app.assistant

import android.Manifest
import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.openminis.app.speech.SpeechRecognitionManager
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** A departing panel must not cancel the real microphone owned by its successor. */
@RunWith(AndroidJUnit4::class)
class CaptureOwnerIsolationTest {
    @get:Rule val microphone = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    @Test fun oldPanelCleanupCannotCancelNewCapture() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val audio = instrumentation.targetContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val oldOwner = Any()
        val newOwner = Any()
        fun onMain(action: () -> Unit) = instrumentation.runOnMainSync(action)
        fun awaitMicrophone() {
            val deadline = SystemClock.elapsedRealtime() + 8_000
            while (audio.activeRecordingConfigurations.isEmpty() && SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(25)
            }
            assertTrue("Real microphone was acquired", audio.activeRecordingConfigurations.isNotEmpty())
        }
        try {
            onMain {
                SpeechRecognitionManager.cancelRecording()
                SpeechRecognitionManager.selectEngine("system")
                SpeechRecognitionManager.startRecording({ _, _ -> }, { _, _ -> }, oldOwner)
            }
            awaitMicrophone()
            onMain {
                assertTrue(SpeechRecognitionManager.isCaptureOwner(oldOwner))
                SpeechRecognitionManager.cancelRecording()
                SpeechRecognitionManager.startRecording({ _, _ -> }, { _, _ -> }, newOwner)
            }
            awaitMicrophone()
            onMain {
                // This is the same ownership check used by panel disposal.
                if (SpeechRecognitionManager.isCaptureOwner(oldOwner)) {
                    SpeechRecognitionManager.cancelRecording()
                }
                assertFalse(SpeechRecognitionManager.isCaptureOwner(oldOwner))
                assertTrue(SpeechRecognitionManager.isCaptureOwner(newOwner))
            }
            assertTrue("Stale disposal did not release the successor's microphone", audio.activeRecordingConfigurations.isNotEmpty())
        } finally {
            onMain { SpeechRecognitionManager.cancelRecording() }
        }
    }
}
