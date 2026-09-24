package com.openminis.app.assistant

import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import com.openminis.app.R

/** Ask Android to select the assistant. Never write secure settings or grant permissions. */
object AssistantSettings {
    fun isSelected(context: Context): Boolean = android.service.voice.VoiceInteractionService.isActiveService(
        context, android.content.ComponentName(context, MinisVoiceInteractionService::class.java),
    )

    private fun isPackageSelected(context: Context): Boolean = if (Build.VERSION.SDK_INT >= 29) {
        context.getSystemService(RoleManager::class.java)?.let {
            it.isRoleAvailable(RoleManager.ROLE_ASSISTANT) && it.isRoleHeld(RoleManager.ROLE_ASSISTANT)
        } ?: false
    } else {
        android.content.ComponentName.unflattenFromString(
            Settings.Secure.getString(context.contentResolver, "assistant").orEmpty(),
        )?.packageName == context.packageName
    }

    fun open(context: Context) {
        // ASSISTANT can be available but explicitly non-requestable (including
        // Android 15). createRequestRoleIntent then silently returns canceled.
        // The user-controlled system settings picker works for activity assistants.
        val candidates = listOf(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
            Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
        for (intent in candidates) {
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: ActivityNotFoundException) {
                // OEMs may omit an individual settings panel; try the next official one.
            } catch (_: SecurityException) {
                // Restricted profiles/device policy can deny a panel.
            }
        }
        Toast.makeText(context, R.string.assistant_settings_unavailable, Toast.LENGTH_LONG).show()
    }
}
