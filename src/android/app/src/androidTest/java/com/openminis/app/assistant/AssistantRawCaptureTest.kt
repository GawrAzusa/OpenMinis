package com.openminis.app.assistant

import android.Manifest
import android.media.AudioManager
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real microphone/WAV/cancel checks, deliberately no speech model or network request. */
@RunWith(AndroidJUnit4::class)
class AssistantRawCaptureTest {
    @get:Rule val permission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun await(expectActive: Boolean) {
        val manager = context.getSystemService(AudioManager::class.java)
        val deadline = SystemClock.elapsedRealtime() + 5000
        while (manager.activeRecordingConfigurations.isNotEmpty() != expectActive && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
        assertEquals(expectActive, manager.activeRecordingConfigurations.isNotEmpty())
    }
    @Test fun recordsActualPcmWavAndStopsMicrophone() = runBlocking {
        val recorder = AssistantAudioRecorder(context)
        try {
            instrumentation.runOnMainSync { assertTrue(recorder.start({}, { fail(it) })) }
            await(true); SystemClock.sleep(350)
            val wav = recorder.stop()
            assertNotNull(wav)
            assertNull(AssistantAudioSupport.validateWav(wav!!))
            assertTrue(wav.size > 44)
            await(false)
        } finally { recorder.cancel() }
    }
    @Test fun cancelReleasesAudioAndCannotReturnStaleTake() = runBlocking {
        val recorder = AssistantAudioRecorder(context)
        try {
            instrumentation.runOnMainSync { assertTrue(recorder.start({}, { fail(it) })) }
            await(true)
            instrumentation.runOnMainSync { recorder.cancel() }
            await(false)
            assertNull(recorder.stop())
        } finally { recorder.cancel() }
    }
}
