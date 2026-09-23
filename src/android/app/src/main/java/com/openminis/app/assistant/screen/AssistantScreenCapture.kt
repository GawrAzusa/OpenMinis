package com.openminis.app.assistant.screen

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.os.Build
import android.os.PowerManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/** No network or model upload. Periodic latest-frame context is NOT Gemini Live video streaming.
 * Call request only in response to an explicit user gesture while the app is foreground/visible.
 * Collect state: requesting=false signals consent failure/completion; frameVersion changes signal
 * a new local preview. A File returned by snapshot is borrowed, may change or disappear on stop,
 * and must never be persisted or automatically uploaded by a collector. stop() also dismisses
 * retained preview data. The OS can kill a process without onDestroy: leftover private cache
 * files are purged on the next request/snapshot, not claimed to be securely erased on SIGKILL.
 *
 * Integration: non-exported AssistantScreenCaptureConsentActivity (excludeFromRecents=true)
 * and AssistantScreenCaptureService (foregroundServiceType="mediaProjection", exported=false).
 * Declare FOREGROUND_SERVICE, FOREGROUND_SERVICE_MEDIA_PROJECTION and POST_NOTIFICATIONS;
 * the parent must obtain notification permission from a visible UI before calling request().
 * No boot receiver/background restart is supported. Do not set noHistory on the consent Activity:
 * it must survive while the system permission Activity is on top.
 */
object AssistantScreenCapture {
    data class State(
        val active: Boolean = false,
        val error: String? = null,
        val frameVersion: Long = 0,
        val requesting: Boolean = false,
        val frameAvailable: Boolean = false,
        val selectionVersion: Long = 0,
    ) {
        fun startingRequest() = copy(active = false, error = null, requesting = true, frameAvailable = false)
        fun readyForPreview() = !requesting && (frameAvailable || error != null)
    }

    private val mutableState = MutableStateFlow(State())
    val state: StateFlow<State> = mutableState.asStateFlow()
    private val gate = CaptureGeneration()
    private val lock = Any()
    private var currentId = 0L
    private var frame: File? = null
    private var cache: File? = null
    private var receiverInstalled = false

    @Synchronized
    fun request(context: Context, continuous: Boolean) {
        val app = context.applicationContext
        val id = synchronized(lock) {
            prepare(app)
            // Invalidate old completion BEFORE clearFrame publishes any state. Otherwise
            // an old error/frame can reopen our overlay over the new OS consent dialog.
            mutableState.value = mutableState.value.startingRequest()
            clearFrame()
            gate.begin().also {
                currentId = it
                mutableState.value = mutableState.value.copy(active = false, error = null, requesting = true)
            }
        }
        app.stopService(Intent(app, AssistantScreenCaptureService::class.java))
        if (!unlocked(app)) {
            end(id, "请先解锁屏幕，再截图或共享。")
            return
        }
        try {
            context.startActivity(Intent(context, AssistantScreenCaptureConsentActivity::class.java)
                .putExtra(EXTRA_GENERATION, id).putExtra(EXTRA_CONTINUOUS, continuous)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK))
        } catch (_: Exception) {
            end(id, "无法打开系统截屏授权，请重试。")
        }
    }

    @Synchronized
    fun stop(context: Context) {
        synchronized(lock) {
            gate.end(currentId)
            clearFrame()
            mutableState.value = mutableState.value.copy(active = false, requesting = false, error = null)
        }
        context.applicationContext.stopService(Intent(context, AssistantScreenCaptureService::class.java))
    }

    suspend fun snapshot(context: Context): File? = withContext(Dispatchers.IO) {
        if (!unlocked(context)) {
            stop(context)
            null
        } else synchronized(lock) {
            prepare(context.applicationContext)
            frame?.takeIf { it.isFile }?.let { latest ->
                // Exactly one explicitly selected frame, still owned/cleared by this capture.
                // Periodic updates must not swap the bytes underneath a user's pending send.
                val selected = File(checkNotNull(cache), "selected.png")
                latest.copyTo(selected, overwrite = true)
                mutableState.value = mutableState.value.copy(selectionVersion = mutableState.value.selectionVersion + 1)
                selected
            }
        }
    }

    internal fun current(id: Long): Boolean = synchronized(lock) { gate.current(id) }
    internal fun consume(id: Long): Boolean = synchronized(lock) { gate.consume(id) }
    internal fun started(id: Long) = synchronized(lock) {
        if (gate.current(id)) mutableState.value = mutableState.value.copy(active = true, requesting = false)
    }

    /** Serializes deletion/publication so an old worker can never resurrect a revoked frame. */
    internal fun publish(id: Long, bitmap: Bitmap): Boolean = synchronized(lock) {
        if (!gate.current(id)) return false
        val directory = cache ?: return false
        val temporary = File(directory, "pending.png")
        val latest = File(directory, "latest.png")
        try {
            temporary.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            check(temporary.renameTo(latest))
            frame = latest
            mutableState.value = mutableState.value.copy(error = null, frameAvailable = true, frameVersion = mutableState.value.frameVersion + 1)
            true
        } finally {
            temporary.delete()
        }
    }

    internal fun unavailable(id: Long) = synchronized(lock) {
        if (gate.current(id)) {
            clearFrame()
            mutableState.value = mutableState.value.copy(error = "当前画面不可读取：可能是受保护页面或空白屏幕。")
        }
    }

    internal fun end(id: Long, error: String? = null, retainPreview: Boolean = false) = synchronized(lock) {
        if (gate.end(id)) {
            if (!retainPreview) clearFrame()
            mutableState.value = mutableState.value.copy(active = false, requesting = false, error = error)
        }
    }

    internal fun unlocked(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java).isInteractive &&
            !context.getSystemService(KeyguardManager::class.java).isKeyguardLocked

    private fun clearFrame() {
        frame = null
        mutableState.value = mutableState.value.copy(frameAvailable = false, selectionVersion = mutableState.value.selectionVersion + 1)
        cache?.listFiles()?.forEach { it.delete() }
    }

    private fun prepare(app: Context) {
        if (cache == null) {
            cache = File(app.cacheDir, "assistant-screen").apply { mkdirs() }
            clearFrame() // Never restore pixels from an earlier process lifetime.
        }
        if (!receiverInstalled) {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) { stop(context) }
            }
            val filter = IntentFilter(Intent.ACTION_SCREEN_OFF).apply { addAction(Intent.ACTION_SHUTDOWN) }
            if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else app.registerReceiver(receiver, filter)
            receiverInstalled = true // Application-lifetime receiver also protects a retained one-shot preview.
        }
    }

    internal const val EXTRA_GENERATION = "screen.generation"
    internal const val EXTRA_CONTINUOUS = "screen.continuous"
    internal const val EXTRA_RESULT = "screen.result"
}
