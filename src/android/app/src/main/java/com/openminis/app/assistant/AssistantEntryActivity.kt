package com.openminis.app.assistant

import android.app.Activity
import android.app.AlertDialog
import android.app.KeyguardManager
import android.os.Bundle

/** Compatibility entry for OEMs dispatching ASSIST activities. Never opens the full chat page. */
class AssistantEntryActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if (getSystemService(KeyguardManager::class.java).isKeyguardLocked) { finish(); return }
        if (MinisVoiceInteractionService.open(this, resume = false)) { finish(); return }
        AlertDialog.Builder(this)
            .setTitle("启用 Minis 小窗助理")
            .setMessage("这个版本使用系统助理小窗。请在默认数字助理中选择 Minis；若系统已经显示 Minis，请先改成“无”，再选回 Minis。设置后长按电源键即可呼出。")
            .setPositiveButton("打开系统设置") { _, _ -> AssistantSettings.open(this); finish() }
            .setNegativeButton("稍后") { _, _ -> finish() }
            .setOnCancelListener { finish() }.show()
    }
}
