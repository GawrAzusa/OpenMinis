package com.openminis.app.assistant

import android.content.ComponentName
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.assistant.screen.AssistantScreenCapture
import com.openminis.app.assistant.screen.AssistantScreenCaptureConsentActivity
import com.openminis.app.assistant.screen.AssistantScreenCaptureService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AssistantOverlayContractTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun systemSessionRequiresAndroidBindingPermission() {
        for (type in listOf(MinisVoiceInteractionService::class.java, MinisVoiceSessionService::class.java)) {
            val service = context.packageManager.getServiceInfo(ComponentName(context, type), PackageManager.GET_META_DATA)
            assertTrue(service.exported)
            assertEquals("android.permission.BIND_VOICE_INTERACTION", service.permission)
        }
        val service = context.packageManager.getServiceInfo(ComponentName(context, MinisVoiceInteractionService::class.java), PackageManager.GET_META_DATA)
        val metadata = service.metaData.getInt("android.voice_interaction")
        assertNotEquals(0, metadata)
        val parser = context.resources.getXml(metadata)
        while (parser.eventType != org.xmlpull.v1.XmlPullParser.START_TAG) parser.next()
        assertEquals("voice-interaction-service", parser.name)
        assertEquals(MinisVoiceSessionService::class.java.name,
            parser.getAttributeValue("http://schemas.android.com/apk/res/android", "sessionService"))
        parser.close()
    }
    @Test fun privatePermissionAndProjectionComponentsAreNotExported() {
        for (type in listOf(AssistantPermissionActivity::class.java, AssistantScreenCaptureConsentActivity::class.java)) {
            assertFalse(context.packageManager.getActivityInfo(ComponentName(context, type), 0).exported)
        }
        for (type in listOf(AssistantBubbleService::class.java, AssistantScreenCaptureService::class.java)) {
            assertFalse(context.packageManager.getServiceInfo(ComponentName(context, type), 0).exported)
        }
    }
    @Test fun screenSharingNeverRestoresPermissionFromDisk() = runBlocking {
        AssistantScreenCapture.stop(context)
        assertFalse(AssistantScreenCapture.state.value.active)
        assertFalse(AssistantScreenCapture.state.value.requesting)
        assertNull(AssistantScreenCapture.snapshot(context))
    }
}
