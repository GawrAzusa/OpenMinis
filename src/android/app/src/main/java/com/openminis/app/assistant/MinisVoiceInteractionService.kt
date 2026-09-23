package com.openminis.app.assistant

import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

/** System-bound assistant, not a hotword service. No screen or microphone capture in onReady. */
class MinisVoiceInteractionService : VoiceInteractionService() {
    private val dismissReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: android.content.Intent) {
            val reason = intent.getStringExtra("reason")
            if (intent.action == android.content.Intent.ACTION_SCREEN_OFF || reason in setOf("homekey", "recentapps", "lock")) {
                AssistantReturnGate.invalidate()
                AssistantWorkspace.pause()
            }
        }
    }
    override fun onReady() { super.onReady(); active = this
        val filter = android.content.IntentFilter(android.content.Intent.ACTION_CLOSE_SYSTEM_DIALOGS).apply {
            addAction(android.content.Intent.ACTION_SCREEN_OFF)
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(dismissReceiver, filter, RECEIVER_NOT_EXPORTED)
        else registerReceiver(dismissReceiver, filter)
        setDisabledShowContext(VoiceInteractionSession.SHOW_WITH_ASSIST or VoiceInteractionSession.SHOW_WITH_SCREENSHOT)
    }
    override fun onShutdown() {
        runCatching { unregisterReceiver(dismissReceiver) }
        AssistantReturnGate.invalidate(); active = null; AssistantWorkspace.close(this); super.onShutdown()
    }
    override fun onLaunchVoiceAssistFromKeyguard() { /* Unlock through Android first. */ }
    companion object {
        internal const val RETURN_TICKET = "assistant_return_ticket"
        private var active: MinisVoiceInteractionService? = null
        fun open(context: Context, resume: Boolean = true, ticket: Long? = null): Boolean {
            if (ticket != null && !AssistantReturnGate.current(ticket)) return false
            if (context.getSystemService(KeyguardManager::class.java).isKeyguardLocked) return false
            if (!isActiveService(context, ComponentName(context, MinisVoiceInteractionService::class.java))) return false
            return runCatching { active?.showSession(Bundle().apply {
                putBoolean("resume", resume)
                if (ticket != null) putLong(RETURN_TICKET, ticket)
            }, 0) != null }.getOrDefault(false)
        }
    }
}

class MinisVoiceSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = MinisVoiceSession(this)
}
