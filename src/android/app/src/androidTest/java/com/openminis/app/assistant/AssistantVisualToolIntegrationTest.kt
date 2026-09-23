package com.openminis.app.assistant

import android.app.UiAutomation
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.accessibility.MinisAccessibilityService
import com.openminis.app.offload.OffloadPermissionManager
import com.openminis.app.sandbox.NativeOffloadRequest
import com.openminis.app.sandbox.offload.AccessibilityOffloadHandler
import com.openminis.app.ui.chat.ChatViewModelStore
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit emulator fixture: real service/UI/tools, no synthetic model or successful API response. */
@RunWith(AndroidJUnit4::class)
class AssistantVisualToolIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun eventually(message: String, test: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (!test() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
        assertTrue(message, test())
    }
    private fun hasComposer(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        if (node.contentDescription?.toString() == "助理文字输入") return true
        return (0 until node.childCount).any { hasComposer(node.getChild(it)) }
    }
    private fun composerVisible() = MinisAccessibilityService.getInstance()?.rootNodes()?.any(::hasComposer) == true

    @Test fun nativeVisualToolsYieldTheAssistantSurfaceAndRespectPause() {
        assumeTrue("Requires explicitly prepared isolated visual QA fixture", InstrumentationRegistry.getArguments().getString("nativeVisualQA") == "true")
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        eventually("Real Minis accessibility service not enabled/bound") { MinisAccessibilityService.getInstance() != null }
        assertTrue("Select the default assistant through Android UI before this test", AssistantSettings.isSelected(context))
        instrumentation.runOnMainSync { context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        automation.executeShellCommand("input keyevent KEYCODE_ASSIST").close()
        eventually("Actual system assistant composer did not appear") { composerVisible() }
        instrumentation.runOnMainSync { AssistantWorkspace.newConversation(context) }
        val id = AssistantWorkspace.sessionKey?.let(ChatViewModelStore::resolvePersistedId)
        assertNotNull("Workspace/session identity missing", id)
        val manager = OffloadPermissionManager
        val previous = manager.getLevel("a11y_cli")
        manager.setLevel("a11y_cli", OffloadPermissionManager.PermissionLevel.BYPASS)
        val handler = AccessibilityOffloadHandler(context)
        fun call(vararg args: String) = handler.handle(NativeOffloadRequest(
            pid = android.os.Process.myPid(), argv = listOf("android-a11y-cli") + args,
            env = emptyMap(), cwd = context.cacheDir.path, sessionId = id,
        ))
        try {
            val first = call("ui", "dump")
            assertEquals(first.output.take(500), 0, first.exitCode)
            assertTrue("Expected real underlying Android settings", first.output.contains("com.android.settings"))
            assertFalse("Our own assistant must not obstruct the visual tool", first.output.contains("助理文字输入"))
            eventually("Assistant did not return after its own tool") { composerVisible() }
            assertFalse("Own temporary tool hide must not pause task", AssistantWorkspace.state.value.paused)
            val metrics = context.resources.displayMetrics
            val swipe = call("gesture", "swipe", (metrics.widthPixels / 2).toString(), (metrics.heightPixels * 3 / 4).toString(),
                (metrics.widthPixels / 2).toString(), (metrics.heightPixels / 3).toString(), "--duration", "300")
            assertEquals(swipe.output.take(500), 0, swipe.exitCode)
            eventually("Assistant did not return after actual gesture") { composerVisible() }
            instrumentation.runOnMainSync { AssistantWorkspace.pause() }
            val paused = call("ui", "dump")
            assertNotEquals("User pause must refuse a new owned visual action", 0, paused.exitCode)

            var replacementTicket = 0L
            instrumentation.runOnMainSync {
                AssistantWorkspace.newConversation(context)
                AssistantWorkspace.setDraft("replacement draft")
                replacementTicket = AssistantReturnGate.beginTemporary()
                val field = AssistantToolSurface::class.java.getDeclaredField("session").apply { isAccessible = true }
                val session = field.get(AssistantToolSurface) as MinisVoiceSession
                // Execute the real adapter, not a copy of the ticket predicate.
                session.onShow(android.os.Bundle().apply {
                    putBoolean("resume", true)
                    putLong(AssistantToolSurface.RESTORE_TICKET, -777L)
                }, 0)
                session.onShow(android.os.Bundle().apply {
                    putBoolean("resume", true)
                    putLong(MinisVoiceInteractionService.RETURN_TICKET, -778L)
                }, 0)
            }
            SystemClock.sleep(400)
            instrumentation.waitForIdleSync()
            assertFalse("Late callback paused replacement", AssistantWorkspace.state.value.paused)
            assertTrue(AssistantReturnGate.current(replacementTicket))
            assertTrue("Late callback consumed new hide ticket", AssistantReturnGate.hasExpectedHide())
            assertEquals("replacement draft", AssistantWorkspace.state.value.draft)
            assertTrue("Late callback hid replacement UI", composerVisible())
            instrumentation.runOnMainSync {
                AssistantReturnGate.invalidate()
                val field = AssistantToolSurface::class.java.getDeclaredField("session").apply { isAccessible = true }
                (field.get(AssistantToolSurface) as MinisVoiceSession).hide()
            }
            eventually("Ordinary hide must still pause") { AssistantWorkspace.state.value.paused }
        } finally {
            manager.setLevel("a11y_cli", previous)
            instrumentation.runOnMainSync { AssistantWorkspace.close(context) }
        }
    }
}
