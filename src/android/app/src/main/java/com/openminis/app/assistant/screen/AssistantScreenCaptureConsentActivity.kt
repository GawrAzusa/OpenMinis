package com.openminis.app.assistant.screen

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.view.WindowManager

/** Must be declared exported=false; never launch this component directly instead of request(). */
class AssistantScreenCaptureConsentActivity : Activity() {
    private var generation = 0L
    private var handedOff = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        generation = intent.getLongExtra(AssistantScreenCapture.EXTRA_GENERATION, 0)
        handedOff = savedInstanceState?.getBoolean("handedOff") ?: false
        if (!AssistantScreenCapture.current(generation) || !AssistantScreenCapture.unlocked(this)) {
            AssistantScreenCapture.end(generation, "这次截屏请求已失效，请重新点击。")
            finish()
            return
        }
        // Framework restores the outstanding result after configuration recreation; never reuse
        // a consent Intent or request a second token automatically.
        if (savedInstanceState == null) {
            try {
                startActivityForResult(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent(), CONSENT)
            } catch (_: Exception) {
                AssistantScreenCapture.end(generation, "系统暂时无法提供截屏授权。")
                finish()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("handedOff", handedOff)
        super.onSaveInstanceState(outState)
    }

    @Deprecated("Framework permission result used without an AndroidX activity dependency")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != CONSENT) return
        if (resultCode != RESULT_OK || data == null) {
            AssistantScreenCapture.end(generation, "未授权屏幕捕获，没有读取或发送画面。")
        } else if (AssistantScreenCapture.current(generation) && AssistantScreenCapture.unlocked(this)) {
            try {
                startForegroundService(Intent(this, AssistantScreenCaptureService::class.java)
                    .putExtra(AssistantScreenCapture.EXTRA_GENERATION, generation)
                    .putExtra(AssistantScreenCapture.EXTRA_CONTINUOUS,
                        intent.getBooleanExtra(AssistantScreenCapture.EXTRA_CONTINUOUS, false))
                    .putExtra(AssistantScreenCapture.EXTRA_RESULT, data))
                handedOff = true
            } catch (_: Exception) {
                AssistantScreenCapture.end(generation, "屏幕捕获未能启动，请保持小窗可见后重试。")
            }
        } else {
            AssistantScreenCapture.end(generation, "这次截屏请求已失效，请重新点击。")
        }
        finish()
    }

    override fun onDestroy() {
        if (isFinishing && !handedOff) AssistantScreenCapture.end(generation, "已取消屏幕捕获。")
        super.onDestroy()
    }

    private companion object { const val CONSENT = 7143 }
}
