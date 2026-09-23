package com.openminis.app.assistant

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.MainActivity
import com.openminis.app.deeplink.DeepLinkAction
import com.openminis.app.deeplink.DeepLinkHandler
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Android framework/packaged-manifest checks; not a substitute for button + microphone QA. */
@RunWith(AndroidJUnit4::class)
class AssistantIntegrationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun assistResolvesToCompactEntryWithoutUri() {
        val resolved = context.packageManager.resolveActivity(
            Intent(Intent.ACTION_ASSIST).setPackage(context.packageName),
            PackageManager.MATCH_DEFAULT_ONLY,
        )
        assertNotNull(resolved)
        assertEquals(AssistantEntryActivity::class.java.name, resolved!!.activityInfo.name)
        assertTrue(resolved.activityInfo.exported)
    }

    @Test fun voiceAssistResolvesWithoutUri() {
        assertNotNull(context.packageManager.resolveActivity(
            Intent("android.intent.action.VOICE_ASSIST").setPackage(context.packageName),
            PackageManager.MATCH_DEFAULT_ONLY,
        ))
    }

    @Test fun assistantActionDoesNotNeedOrExecuteCallerData() {
        assertEquals(DeepLinkAction.NewAssistantChat, DeepLinkHandler.parseLaunch(Intent.ACTION_ASSIST, null))
        assertEquals(DeepLinkAction.NewAssistantChat, DeepLinkHandler.parseLaunch(
            "android.intent.action.VOICE_ASSIST", Uri.parse("minis://open_terminal?init_command=untrusted"),
        ))
    }

    @Test fun ordinaryLauncherDoesNotInvokeAssistant() {
        assertEquals(DeepLinkAction.Unknown, DeepLinkHandler.parseLaunch(Intent.ACTION_MAIN, null))
    }

    @Test fun existingShortcutsKeepTheirMeaning() {
        assertEquals(DeepLinkAction.NewVoiceChat, DeepLinkHandler.parseLaunch(
            Intent.ACTION_VIEW, Uri.parse("minis://action/voice_chat"),
        ))
        assertEquals(DeepLinkAction.NewCameraChat, DeepLinkHandler.parseLaunch(
            Intent.ACTION_VIEW, Uri.parse("minis://action/camera_chat"),
        ))
        assertEquals(DeepLinkAction.OpenSession("existing"), DeepLinkHandler.parseLaunch(
            Intent.ACTION_VIEW, Uri.parse("minis://session/existing"),
        ))
    }
}
