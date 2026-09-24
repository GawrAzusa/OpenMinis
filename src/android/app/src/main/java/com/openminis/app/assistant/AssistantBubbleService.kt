package com.openminis.app.assistant

import android.app.*
import android.content.*
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.TextView
import com.openminis.app.R
import com.openminis.app.assistant.screen.AssistantScreenCapture
import kotlin.math.abs

/** User-minimized assistant only. Never automatically resumed after process death. */
class AssistantBubbleService : Service() {
    private var bubble: TextView? = null
    private lateinit var wm: WindowManager
    private var params: WindowManager.LayoutParams? = null
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            AssistantWorkspace.pause(); AssistantScreenCapture.stop(context); stopSelf()
        }
    }
    override fun onBind(intent: Intent?) = null
    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WindowManager::class.java)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), RECEIVER_NOT_EXPORTED)
        else registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF))
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop" || !Settings.canDrawOverlays(this) || getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
            stopSelf(); return START_NOT_STICKY
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Minis 悬浮助理", NotificationManager.IMPORTANCE_LOW))
        val restore = PendingIntent.getActivity(this, 880,
            Intent(this, AssistantPermissionActivity::class.java).putExtra("purpose", "resume"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val close = PendingIntent.getService(this, 881,
            Intent(this, AssistantBubbleService::class.java).setAction("stop"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_notification_completed)
            .setContentTitle("Minis 已挂起").setContentText("任务已暂停 · 点小球继续 · 屏幕共享可单独停止")
            .setContentIntent(restore).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "收起小球", close).build()).build()
        try { startForeground(882, notification) } catch (e: RuntimeException) {
            AssistantWorkspace.error("无法保持悬浮球，请检查后台权限。"); stopSelf(); return START_NOT_STICKY
        }
        if (bubble == null) attach()
        return START_NOT_STICKY
    }
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun bounds(): Rect = if (Build.VERSION.SDK_INT >= 30) wm.currentWindowMetrics.bounds
        else Rect(0, 0, resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
    private fun attach() {
        val size = dp(58)
        val metrics = bounds()
        val prefs = getSharedPreferences("assistant_bubble", MODE_PRIVATE)
        val left = prefs.getBoolean("left", false)
        val y = (metrics.height() * prefs.getFloat("y", 0.38f)).toInt().coerceIn(dp(40), (metrics.height() - size - dp(32)).coerceAtLeast(dp(40)))
        val lp = WindowManager.LayoutParams(size, size, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_SECURE,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = if (left) dp(6) else metrics.width() - size - dp(6)
            this.y = y
        }
        params = lp
        val view = TextView(this).apply {
            text = "✦"; textSize = 29f; gravity = Gravity.CENTER
            setTextColor(Color.rgb(206, 226, 255))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(43, 62, 103), Color.rgb(36, 29, 66))).apply {
                cornerRadius = size.toFloat(); setStroke(dp(2), Color.rgb(156, 185, 247))
            }
            elevation = dp(9).toFloat()
            contentDescription = "Minis 悬浮球，任务已暂停，点击恢复小窗，长按关闭"
            isClickable = true; isFocusable = true
            setOnClickListener {
                if (MinisVoiceInteractionService.open(this@AssistantBubbleService)) stopSelf()
                else AssistantWorkspace.error("请先在默认数字助理中选择 Minis。")
            }
            setOnLongClickListener { AssistantWorkspace.close(this@AssistantBubbleService); stopSelf(); true }
        }
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        var downTime = 0L
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY; startX = lp.x; startY = lp.y
                    moved = false; downTime = SystemClock.uptimeMillis(); true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX; val dy = event.rawY - downY
                    if (abs(dx) + abs(dy) > dp(8)) moved = true
                    if (moved) {
                        val b = bounds()
                        lp.x = (startX + dx.toInt()).coerceIn(0, (b.width() - size).coerceAtLeast(0))
                        lp.y = (startY + dy.toInt()).coerceIn(dp(32), (b.height() - size - dp(24)).coerceAtLeast(dp(32)))
                        runCatching { wm.updateViewLayout(view, lp) }
                    }; true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        if (SystemClock.uptimeMillis() - downTime > 600) v.performLongClick() else v.performClick()
                    } else {
                        val b = bounds(); val dockLeft = lp.x + size / 2 < b.width() / 2
                        lp.x = if (dockLeft) dp(6) else b.width() - size - dp(6)
                        runCatching { wm.updateViewLayout(view, lp) }
                        prefs.edit().putBoolean("left", dockLeft).putFloat("y", lp.y.toFloat() / b.height()).apply()
                    }; true
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
        try { wm.addView(view, lp); bubble = view } catch (e: RuntimeException) {
            AssistantWorkspace.error("悬浮球权限不可用，请重新授权。"); stopSelf()
        }
    }
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        bubble?.let { runCatching { wm.removeView(it) } }; bubble = null
        if (Settings.canDrawOverlays(this)) attach() else stopSelf()
    }
    override fun onDestroy() {
        runCatching { unregisterReceiver(screenOff) }
        bubble?.let { runCatching { wm.removeView(it) } }; bubble = null
        super.onDestroy()
    }
    companion object {
        private const val CHANNEL = "minis_assistant_bubble"
        fun start(context: Context): Boolean = try {
            context.startForegroundService(Intent(context, AssistantBubbleService::class.java)); true
        } catch (e: RuntimeException) {
            AssistantWorkspace.error("系统不允许显示悬浮球，请检查权限。"); false
        }
        fun stop(context: Context) { context.stopService(Intent(context, AssistantBubbleService::class.java)) }
    }
}
