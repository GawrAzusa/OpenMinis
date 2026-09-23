package com.openminis.app.assistant.screen

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.view.WindowManager

/** Visible, non-sticky mediaProjection FGS. One consent -> one getMediaProjection -> one display.
 * FLAG_SECURE is enforced by the platform; no accessibility, privileged APIs or bypasses.
 */
class AssistantScreenCaptureService : Service() {
    private lateinit var thread: HandlerThread
    private lateinit var worker: Handler
    private var generation = 0L
    private var continuous = false
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var width = 0
    private var height = 0
    private var lastFrameAt = 0L
    private var finished = false
    private var receivedFrame = false
    private var visible = true

    private val callback = object : MediaProjection.Callback() {
        override fun onStop() { finishCapture("屏幕共享已结束或授权已撤销。") }
        override fun onCapturedContentResize(width: Int, height: Int) {
            if (!finished) guarded { resize(width, height) }
        }
        override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
            visible = isVisible
            if (!isVisible) AssistantScreenCapture.unavailable(generation)
        }
    }

    override fun onCreate() {
        super.onCreate()
        thread = HandlerThread("assistant-screen-capture").apply { start() }
        worker = Handler(thread.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // A stale notification must not stop a later consent generation.
            if (intent.getLongExtra(AssistantScreenCapture.EXTRA_GENERATION, -1) == generation) {
                AssistantScreenCapture.stop(this)
            }
            if (generation == 0L) stopSelf(startId)
            return START_NOT_STICKY
        }
        val id = intent?.getLongExtra(AssistantScreenCapture.EXTRA_GENERATION, 0) ?: 0
        if (!AssistantScreenCapture.consume(id)) {
            if (generation == 0L) stopSelf(startId)
            return START_NOT_STICKY
        }
        if (generation != 0L) {
            AssistantScreenCapture.end(id, "上一次共享正在停止，请稍后重试。")
            return START_NOT_STICKY
        }
        generation = id
        continuous = intent?.getBooleanExtra(AssistantScreenCapture.EXTRA_CONTINUOUS, false) ?: false
        @Suppress("DEPRECATION")
        val result = intent?.getParcelableExtra<Intent>(AssistantScreenCapture.EXTRA_RESULT)
        try {
            showNotification()
        } catch (_: Exception) {
            AssistantScreenCapture.end(id, "请开启通知并保持助理可见，以便随时停止屏幕共享。")
            stopSelf()
            return START_NOT_STICKY
        }
        // Consent's result callback can run before its dismiss animation has left the compositor.
        // Create the display only after that transition, otherwise one-shot captures the permission sheet.
        val animationScale = maxOf(
            android.provider.Settings.Global.getFloat(contentResolver, android.provider.Settings.Global.WINDOW_ANIMATION_SCALE, 1f),
            android.provider.Settings.Global.getFloat(contentResolver, android.provider.Settings.Global.TRANSITION_ANIMATION_SCALE, 1f),
        ).coerceIn(0f, 10f)
        val settleMillis = (250f + animationScale * 350f).toLong()
        worker.postDelayed({
            guarded {
                check(!finished && result != null && AssistantScreenCapture.current(id) && AssistantScreenCapture.unlocked(this))
                projection = getSystemService(MediaProjectionManager::class.java)
                    .getMediaProjection(Activity.RESULT_OK, result)
                val capture = checkNotNull(projection)
                capture.registerCallback(callback, worker) // Required BEFORE createVirtualDisplay on API 34+.
                val (w, h) = initialDimensions()
                val bounded = CapturePolicy.dimensions(w, h)
                width = bounded.first
                height = bounded.second
                reader = createReader(width, height)
                display = capture.createVirtualDisplay("Assistant screen preview", width, height,
                    resources.configuration.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader!!.surface, null, worker)
                checkNotNull(display)
                AssistantScreenCapture.started(id)
                worker.postDelayed({
                    if (!receivedFrame && !finished) finishCapture("未收到可读取画面，无法读取受保护或空白页面。")
                }, CapturePolicy.FIRST_FRAME_TIMEOUT_MS)
            }
        }, settleMillis)
        return START_NOT_STICKY
    }

    private fun showNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Minis 屏幕捕获", NotificationManager.IMPORTANCE_LOW))
        check(manager.areNotificationsEnabled() && manager.getNotificationChannel(CHANNEL).importance != NotificationManager.IMPORTANCE_NONE)
        val stop = PendingIntent.getService(this, 0,
            Intent(this, AssistantScreenCaptureService::class.java).setAction(ACTION_STOP)
                .putExtra(AssistantScreenCapture.EXTRA_GENERATION, generation),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle(if (continuous) "Minis 正在共享屏幕" else "Minis 截图")
            .setContentText("本地捕获；点击发送才交给模型 · 随时可停止")
            .setOngoing(true).setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .addAction(Notification.Action.Builder(null, "停止共享", stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(NOTIFICATION_ID, notification)
    }

    @Suppress("DEPRECATION")
    private fun initialDimensions(): Pair<Int, Int> {
        val windows = getSystemService(WindowManager::class.java)
        if (Build.VERSION.SDK_INT >= 30) return windows.maximumWindowMetrics.bounds.let { it.width() to it.height() }
        val metrics = android.util.DisplayMetrics()
        windows.defaultDisplay.getRealMetrics(metrics)
        return metrics.widthPixels to metrics.heightPixels
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // API 34+ callback gives the selected app's dimensions, not the whole screen's dimensions.
        if (Build.VERSION.SDK_INT < 34) worker.post {
            if (!finished && display != null) guarded { val (w, h) = initialDimensions(); resize(w, h) }
        }
    }

    private fun resize(sourceWidth: Int, sourceHeight: Int) {
        if (!AssistantScreenCapture.current(generation)) return
        val (w, h) = CapturePolicy.dimensions(sourceWidth, sourceHeight)
        if (w == width && h == height) return
        val target = display ?: return
        val replacement = createReader(w, h)
        try {
            target.surface = null
            target.resize(w, h, resources.configuration.densityDpi)
            target.surface = replacement.surface
        } catch (failure: Exception) {
            replacement.close()
            throw failure
        }
        reader?.close()
        reader = replacement
        width = w
        height = h
        lastFrameAt = 0
        AssistantScreenCapture.unavailable(generation) // Never expose a stale pre-rotation frame.
    }

    private fun createReader(w: Int, h: Int): ImageReader =
        ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2).apply {
            var queued = false
            setOnImageAvailableListener({ source ->
                if (!queued) {
                    queued = true
                    // Keep the latest queued buffer until the rate limit permits reading. Dropping
                    // the only post-hide frame would make a static screen impossible to share.
                    val wait = (CapturePolicy.FRAME_INTERVAL_MS - (SystemClock.elapsedRealtime() - lastFrameAt)).coerceAtLeast(0)
                    worker.postDelayed({
                        queued = false
                        if (source === reader && !finished) readFrame(source)
                    }, wait)
                }
            }, worker)
        }

    private fun readFrame(source: ImageReader) {
        guarded {
            val image = source.acquireLatestImage() ?: return@guarded
            image.use {
                if (finished || !visible || !AssistantScreenCapture.current(generation)) return@use
                if (!AssistantScreenCapture.unlocked(this@AssistantScreenCaptureService)) {
                    finishCapture("屏幕已锁定，捕获画面已清除。")
                    return@use
                }
                lastFrameAt = SystemClock.elapsedRealtime()
                val bitmap = decode(image)
                try {
                    if (bitmap == null) {
                        AssistantScreenCapture.unavailable(generation)
                    } else if (AssistantScreenCapture.publish(generation, bitmap)) {
                        receivedFrame = true
                        if (!continuous) finishCapture(retainPreview = true)
                    }
                } finally { bitmap?.recycle() }
            }
        }
    }

    /** Read only real pixels: last rows need not contain row padding. Output is already bounded. */
    private fun decode(image: Image): Bitmap? {
        val plane = image.planes[0]
        val crop = image.cropRect
        val pixels = CapturePolicy.rgbaPixels(plane.buffer, plane.rowStride, plane.pixelStride,
            crop.left, crop.top, crop.width(), crop.height())
        if (!CapturePolicy.hasVisiblePixels(pixels)) return null
        return Bitmap.createBitmap(pixels, crop.width(), crop.height(), Bitmap.Config.ARGB_8888)
    }

    private inline fun guarded(block: () -> Unit) {
        try { block() } catch (_: Exception) { finishCapture("屏幕捕获失败，请重新授权。") }
    }

    private fun finishCapture(error: String? = null, retainPreview: Boolean = false) {
        if (finished) return
        finished = true
        AssistantScreenCapture.end(generation, error, retainPreview)
        releaseCapture()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun releaseCapture() {
        // Every resource is attempted even when the platform has already revoked projection.
        runCatching { display?.release() }; display = null
        runCatching { reader?.close() }; reader = null
        runCatching { projection?.unregisterCallback(callback) }
        runCatching { projection?.stop() }; projection = null
    }

    override fun onDestroy() {
        // Immediately invalidate workers. Successful one-shot already ended its generation and
        // intentionally retained its preview; an unexpected destroy always clears live capture.
        AssistantScreenCapture.end(generation)
        worker.post { finished = true; releaseCapture(); thread.quitSafely() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private companion object {
        const val CHANNEL = "assistant_screen_capture"
        const val NOTIFICATION_ID = 7143
        const val ACTION_STOP = "com.openminis.app.assistant.screen.STOP"
    }
}
