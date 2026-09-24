package com.openminis.app.assistant

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings

/** Permission-only trampoline. The live session alone owns a one-shot microphone continuation. */
class AssistantPermissionActivity : Activity() {
    private var launched = false
    private var purpose = ""
    private var ticket = -1L
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        purpose = intent.getStringExtra("purpose").orEmpty()
        ticket = if (purpose == "resume") AssistantReturnGate.beginTemporary() else intent.getLongExtra("ticket", -1)
        if (!AssistantReturnGate.current(ticket)) { finish(); return }
        if (state != null) { finish(); return }
        when (purpose) {
            "microphone" -> {
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) complete()
                else requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 41)
            }
            "notifications" -> {
                if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 43)
                else complete()
            }
            "overlay" -> {
                if (Settings.canDrawOverlays(this)) complete()
                else try {
                    launched = true
                    startActivityForResult(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")), 42)
                } catch (e: RuntimeException) { AssistantWorkspace.error("请在系统设置中允许 Minis 显示在其他应用上层。"); complete() }
            }
            else -> complete()
        }
    }
    @Deprecated("Activity compatibility callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 42) {
            AssistantWorkspace.error(if (Settings.canDrawOverlays(this)) "悬浮权限已开启，点击 − 即可收成小球。" else "未开启悬浮权限；小窗仍可正常使用。")
            complete()
        }
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 43) {
            AssistantWorkspace.error(if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) "通知已开启，请再次点击截图或共享。" else "共享需要可见的停止通知；你仍可打字或语音。")
            complete()
        }
        if (requestCode == 41) {
            AssistantWorkspace.error(if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) null else "麦克风未授权，可以继续打字。")
            complete()
        }
    }
    private fun complete() {
        finish()
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (!AssistantReturnGate.current(ticket)) return@postDelayed
            if (!MinisVoiceInteractionService.open(applicationContext, ticket = ticket)) {
                android.widget.Toast.makeText(applicationContext, "请在默认数字助理中选择 Minis", android.widget.Toast.LENGTH_LONG).show()
            }
        }, 180)
    }
    companion object {
        fun open(context: Context, purpose: String): Long {
            val ticket = AssistantReturnGate.beginTemporary()
            context.startActivity(Intent(context, AssistantPermissionActivity::class.java)
                .putExtra("purpose", purpose).putExtra("ticket", ticket).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK))
            return ticket
        }
    }
}
