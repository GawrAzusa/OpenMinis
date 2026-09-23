package com.openminis.app.assistant

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.service.voice.VoiceInteractionSession
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.*
import com.openminis.app.MainActivity
import com.openminis.app.assistant.screen.AssistantScreenCapture
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

/** Native assistant window: the foreground application stays underneath, never a chat Activity. */
class MinisVoiceSession(context: Context) : VoiceInteractionSession(context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var root: FrameLayout
    private lateinit var card: LinearLayout
    private lateinit var status: TextView
    private lateinit var modelLabel: TextView
    private lateinit var transcript: TextView
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var warning: TextView
    private lateinit var repairGuidance: TextView
    private lateinit var approval: LinearLayout
    private lateinit var attachment: LinearLayout
    private lateinit var preview: ImageView
    private lateinit var attachmentText: TextView
    private lateinit var mic: TextView
    private lateinit var send: TextView
    private lateinit var speech: TextView
    private lateinit var share: TextView
    private lateinit var stop: TextView
    private lateinit var voiceLabel: TextView
    private var shown = false
    private var expanded = false
    private var awaitingCapture = false
    private var captureTicket = -1L
    private var shareActive = false
    private var generation = 0
    private var previousPreview: String? = null
    private var previousTranscript = ""
    private val ink = Color.rgb(238, 241, 255)
    private val muted = Color.rgb(157, 169, 199)
    private val blue = Color.rgb(159, 195, 255)
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    private fun rounded(color: Int, radius: Int = 22, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }
    private fun label(text: String, size: Float = 14f, color: Int = ink) = TextView(context).apply {
        this.text = text; textSize = size; setTextColor(color); includeFontPadding = false
    }
    private fun action(text: String, description: String = text, block: () -> Unit): TextView = label(text, 13f).apply {
        gravity = Gravity.CENTER; contentDescription = description; isClickable = true; isFocusable = true
        minHeight = dp(46); setPadding(dp(11), dp(8), dp(11), dp(8))
        background = rounded(Color.rgb(33, 41, 62), 18)
        setOnClickListener { block() }
    }
    private fun row() = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun LinearLayout.addWeighted(v: View) { addView(v, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6) }) }

    override fun onCreate() {
        setTheme(android.R.style.Theme_Material_NoActionBar)
        super.onCreate()
        setDisabledShowContext(SHOW_WITH_ASSIST or SHOW_WITH_SCREENSHOT)
        window?.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }

    override fun onCreateContentView(): View {
        root = FrameLayout(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { minimize() }
        }
        // Android 15 enforces edge-to-edge even for voice sessions: adjustResize alone
        // leaves the composer behind IME. Respect real insets instead of guessing keyboard height.
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                val ime = insets.getInsets(android.view.WindowInsets.Type.ime())
                view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
                insets
            }
        } else {
            root.viewTreeObserver.addOnGlobalLayoutListener {
                val visible = android.graphics.Rect()
                root.getWindowVisibleDisplayFrame(visible)
                val location = IntArray(2); root.getLocationOnScreen(location)
                root.setPadding(0, 0, 0, (location[1] + root.height - visible.bottom).coerceAtLeast(0))
            }
        }
        root.post { root.requestApplyInsets() }
        val border = FrameLayout(context).apply {
            setPadding(dp(1), dp(1), dp(1), dp(1))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(114, 180, 255), Color.rgb(173, 144, 234), Color.rgb(56, 72, 100))).apply {
                cornerRadius = dp(29).toFloat()
            }
            elevation = dp(18).toFloat()
        }
        card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(15))
            background = rounded(Color.rgb(17, 23, 38), 28)
            setOnClickListener { }
        }
        // The whole panel can scroll when IME/landscape/font scaling leave too little height.
        // Primary input/actions are never clipped outside an unscrollable wrap-content card.
        val panelScroll = ScrollView(context).apply { isFillViewport = false; addView(card) }
        border.addView(panelScroll, FrameLayout.LayoutParams(-1, -2))
        root.addView(border, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            leftMargin = dp(10); rightMargin = dp(10); bottomMargin = dp(24)
        })
        val handle = View(context).apply { background = rounded(Color.rgb(72, 84, 111), 3) }
        card.addView(handle, LinearLayout.LayoutParams(dp(32), dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(15) })
        val header = row()
        header.addView(label("✦", 31f, blue), LinearLayout.LayoutParams(dp(38), dp(44)))
        val title = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        title.addView(label("Minis", 21f).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) })
        modelLabel = label("你的随身助理", 11f, muted).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        title.addView(modelLabel, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3) })
        header.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(action("↗", "展开完整聊天") { openFullChat() }, LinearLayout.LayoutParams(dp(43), dp(44)).apply { marginEnd = dp(6) })
        header.addView(action("−", "收成悬浮球并暂停") { minimize() }, LinearLayout.LayoutParams(dp(43), dp(44)).apply { marginEnd = dp(6) })
        header.addView(action("×", "关闭助理并停止共享") { closeAll() }, LinearLayout.LayoutParams(dp(43), dp(44)))
        card.addView(header)
        status = label("有什么可以帮你？", 19f).apply { setPadding(0, dp(20), 0, dp(7)) }
        card.addView(status)
        voiceLabel = label("打字 · 原声提问 · 看懂你的屏幕", 11f, muted)
        card.addView(voiceLabel)
        transcript = label("", 14f).apply { setLineSpacing(dp(5).toFloat(), 1f); setPadding(dp(12), dp(10), dp(12), dp(10)); setTextIsSelectable(true) }
        scroll = ScrollView(context).apply {
            isFillViewport = false; background = rounded(Color.rgb(23, 31, 49), 17); addView(transcript)
            visibility = View.GONE
        }
        card.addView(scroll, LinearLayout.LayoutParams(-1, dp(145)).apply { topMargin = dp(12) })
        val expand = label("展开对话  ⌃", 11f, muted).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(7), 0, dp(7)); contentDescription = "展开或收起对话"
            setOnClickListener {
                expanded = !expanded
                scroll.layoutParams = (scroll.layoutParams as LinearLayout.LayoutParams).apply { height = dp(if (expanded) 285 else 145) }
                text = if (expanded) "收起对话  ⌄" else "展开对话  ⌃"
            }
        }
        card.addView(expand)
        attachment = row().apply { visibility = View.GONE; background = rounded(Color.rgb(29, 40, 57), 16); setPadding(dp(9), dp(8), dp(9), dp(8)) }
        preview = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; contentDescription = "待发送截图预览" }
        attachment.addView(preview, LinearLayout.LayoutParams(dp(48), dp(48)))
        attachmentText = label("截图 · 发送前可移除", 12f, blue)
        attachment.addView(attachmentText, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(10) })
        attachment.addView(action("移除") {
            AssistantWorkspace.discardPreview(); AssistantWorkspace.discardAudio()
            if (!shareActive) AssistantScreenCapture.stop(context)
        })
        card.addView(attachment, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        approval = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; visibility = View.GONE
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(Color.rgb(36, 41, 59), 16)
        }
        card.addView(approval, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        warning = label("", 12f, Color.rgb(255, 196, 140)).apply { visibility = View.GONE; setPadding(0, dp(5), 0, dp(9)) }
        card.addView(warning)
        repairGuidance = label("无障碍权限需要修复。请点击 ↗ 展开完整聊天完成授权，再继续任务。", 12f, Color.rgb(255, 196, 140)).apply {
            visibility = View.GONE; setPadding(0, dp(5), 0, dp(9))
        }
        card.addView(repairGuidance)
        val composer = row().apply { background = rounded(Color.rgb(31, 39, 59), 23); setPadding(dp(6), dp(3), dp(5), dp(3)) }
        input = EditText(context).apply {
            hint = "问点什么，或者交给我做…"; textSize = 15f; setTextColor(ink); setHintTextColor(muted)
            setBackgroundColor(Color.TRANSPARENT); maxLines = 3; minLines = 1
            setPadding(dp(10), dp(12), dp(6), dp(12)); contentDescription = "助理文字输入"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI or android.view.inputmethod.EditorInfo.IME_FLAG_NO_FULLSCREEN
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { AssistantWorkspace.setDraft(s?.toString().orEmpty()) }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        composer.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        send = action("↑", "发送给模型") { sendTurn() }.apply {
            textSize = 24f; setTextColor(Color.rgb(15, 25, 45)); background = rounded(blue, 19)
        }
        composer.addView(send, LinearLayout.LayoutParams(dp(42), dp(42)))
        card.addView(composer)
        val tools = row().apply { setPadding(0, dp(11), 0, 0) }
        tools.addWeighted(action("⌨ 打字") { input.requestFocus(); (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(input, InputMethodManager.SHOW_IMPLICIT) })
        mic = action("◉ 语音") {
            if (AssistantWorkspace.state.value.recording) AssistantWorkspace.endRecording()
            else {
                AssistantWorkspace.beginRecording(context)
                if (AssistantReturnGate.hasExpectedHide()) hide()
            }
        }; tools.addWeighted(mic)
        tools.addWeighted(action("▣ 截图") { capture(false) })
        share = action("▤ 共享") { if (shareActive) AssistantScreenCapture.stop(context) else capture(true) }
        tools.addWeighted(share)
        card.addView(tools)
        val footer = row().apply { setPadding(0, dp(10), 0, 0) }
        speech = label("声音：关闭", 11f, muted).apply { minHeight = dp(36); gravity = Gravity.CENTER_VERTICAL; setOnClickListener { AssistantWorkspace.toggleSpeech() } }
        footer.addWeighted(speech)
        footer.addView(action("新对话") { awaitingCapture = false; AssistantReturnGate.invalidate(); AssistantWorkspace.newConversation(context) })
        stop = action("停止任务") { if (AssistantWorkspace.state.value.canResume && !AssistantWorkspace.state.value.busy) AssistantWorkspace.resume() else AssistantWorkspace.pause() }
        footer.addView(stop, LinearLayout.LayoutParams(-2, dp(40)).apply { marginStart = dp(6) })
        footer.addView(action("设置") { openSettings() }, LinearLayout.LayoutParams(-2, dp(40)).apply { marginStart = dp(6) })
        card.addView(footer)
        AssistantWorkspace.initialize(context)
        scope.launch { AssistantWorkspace.state.collect { render(it) } }
        scope.launch { com.openminis.app.offload.OffloadPermissionManager.pendingRequest.collect { refreshApproval() } }
        scope.launch { com.openminis.app.accessibility.AccessibilityRecoveryManager.pendingPrompt.collect { refreshRepairGuidance() } }
        scope.launch { com.openminis.app.offload.OffloadPermissionManager.pendingAndroidPermission.collect { request ->
            if (request != null && shown && AssistantWorkspace.state.value.busy)
                AssistantWorkspace.error("这项操作需要 Android 系统权限，请点击 ↗ 展开聊天完成授权，再继续任务。")
        } }
        scope.launch {
            AssistantScreenCapture.state.collect { state ->
                shareActive = state.active
                if (!state.frameAvailable) {
                    AssistantWorkspace.setPreview(null)
                    preview.setImageDrawable(null)
                    previousPreview = null
                }
                if (awaitingCapture && !AssistantReturnGate.current(captureTicket)) awaitingCapture = false
                share.text = if (state.active) "■ 停共享" else "▤ 共享"
                if (state.error != null && !state.requesting) AssistantWorkspace.error(state.error)
                if (awaitingCapture && state.readyForPreview()) {
                    val ticket = captureTicket
                    val ownerGeneration = generation
                    val file = if (state.error == null) AssistantScreenCapture.snapshot(context) else null
                    // Snapshot suspends on IO: never adopt a newer mutable ticket when it returns.
                    if (awaitingCapture && captureTicket == ticket && generation == ownerGeneration &&
                        AssistantReturnGate.current(ticket) && (file != null || state.error != null)) {
                        awaitingCapture = false
                        previousPreview = null
                        AssistantWorkspace.setPreview(file)
                        render(AssistantWorkspace.state.value)
                        MinisVoiceInteractionService.open(context, ticket = ticket)
                    }
                }
                render(AssistantWorkspace.state.value)
                if (state.active) voiceLabel.text = "屏幕共享中 · 每次发送附带最新画面"
            }
        }
        return root
    }
    private fun refreshApproval() {
        if (!::approval.isInitialized) return
        approval.removeAllViews()
        val manager = com.openminis.app.offload.OffloadPermissionManager
        val request = manager.pendingRequest.value
        val key = AssistantWorkspace.sessionKey
        val own = key != null && request?.sessionId == com.openminis.app.ui.chat.ChatViewModelStore.resolvePersistedId(key)
        approval.visibility = if (request != null && own && shown) View.VISIBLE else View.GONE
        if (request == null || !own || !shown) return
        approval.addView(label("需要你确认 · ${request.toolTitle}", 14f, blue))
        approval.addView(label(request.description, 12f, muted).apply { setPadding(0, dp(7), 0, dp(8)) })
        val buttons = row()
        fun respond(response: com.openminis.app.offload.OffloadPermissionManager.Response) {
            if (shown && manager.pendingRequest.value === request) manager.respondToRequest(response)
        }
        buttons.addWeighted(action("仅允许这次") { respond(com.openminis.app.offload.OffloadPermissionManager.Response.ALLOW_ONCE) })
        buttons.addWeighted(action("本次对话允许") { respond(com.openminis.app.offload.OffloadPermissionManager.Response.ALLOW_SESSION) })
        buttons.addWeighted(action("拒绝") { respond(com.openminis.app.offload.OffloadPermissionManager.Response.DENY_SESSION) })
        approval.addView(buttons)
    }
    internal fun refreshRepairGuidance() {
        if (!::repairGuidance.isInitialized) return
        // The recovery manager's prompt is global and has no session id. This is guidance
        // for our own waiting tool only, never a button that answers another chat's prompt.
        repairGuidance.visibility = if (shown && AssistantToolSurface.ownRepairPending() &&
            com.openminis.app.accessibility.AccessibilityRecoveryManager.pendingPrompt.value != null)
            View.VISIBLE else View.GONE
    }
    private fun render(state: AssistantWorkspace.State) {
        if (!::card.isInitialized) return
        refreshRepairGuidance()
        modelLabel.text = state.model
        status.text = when {
            state.recording -> "正在听，点击结束录音…"
            state.busy -> "正在为你处理…"
            state.paused -> "已暂停，手机交还给你"
            state.audio != null -> "原声已准备好，点击发送"
            state.lines.isNotEmpty() -> "还需要我帮你做什么？"
            else -> "有什么可以帮你？"
        }
        if (!shareActive) voiceLabel.text = state.voiceLabel.ifBlank { "打字 · 原声提问 · 看懂你的屏幕" }
        val text = state.lines.filter { it.text.isNotBlank() }.joinToString("\n\n") { (if (it.role == "user") "你  ·  " else "Minis  ·  ") + it.text }
        scroll.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
        if (text != previousTranscript) { transcript.text = text; previousTranscript = text; scroll.post { scroll.fullScroll(View.FOCUS_DOWN) } }
        if (input.text.toString() != state.draft) { input.setText(state.draft); input.setSelection(input.length()) }
        mic.text = if (state.recording) "■ 结束" else "◉ 语音"
        mic.setTextColor(if (state.recording) Color.rgb(255, 170, 182) else ink)
        send.isEnabled = !state.busy && !state.recording
        send.alpha = if (send.isEnabled) 1f else 0.4f
        stop.text = if (state.canResume && !state.busy) "继续任务" else "停止任务"
        stop.isEnabled = state.busy || state.canResume
        speech.text = if (state.speech) "声音：开启" else "声音：关闭"
        warning.text = state.error
        warning.visibility = if (state.error.isNullOrBlank()) View.GONE else View.VISIBLE
        attachment.visibility = if (state.preview != null || state.audio != null) View.VISIBLE else View.GONE
        preview.visibility = if (state.preview != null) View.VISIBLE else View.GONE
        val previewIdentity = state.preview?.let { "${it.absolutePath}:${AssistantScreenCapture.state.value.selectionVersion}" }
        if (previewIdentity != previousPreview) {
            previousPreview = previewIdentity
            preview.setImageURI(null)
            preview.setImageURI(state.preview?.let { android.net.Uri.fromFile(it) })
        }
        attachmentText.text = when {
            state.preview != null && state.audio != null -> "截图 + 原始语音 · 待发送"
            state.preview != null -> "截图 · 待发送给模型"
            else -> "原始语音 · 不转写为文字"
        }
    }
    private fun capture(continuous: Boolean) {
        if (android.os.Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            AssistantPermissionActivity.open(context, "notifications"); hide(); return
        }
        if (AssistantWorkspace.imageUnavailableReason() != null) {
            // Capturing locally remains useful even with no configured vision model.
            AssistantWorkspace.error("截图仅本地预览；发送前需要支持看图的模型。")
        }
        captureTicket = AssistantReturnGate.beginTemporary()
        awaitingCapture = true
        AssistantWorkspace.discardPreview()
        AssistantWorkspace.cancelRecording()
        hide()
        AssistantScreenCapture.request(context, continuous)
    }
    private fun sendTurn() {
        if (!shareActive) { AssistantWorkspace.send(); return }
        val epoch = generation
        val ticket = AssistantReturnGate.beginTemporary()
        scope.launch {
            // Hide our own assistant surface before sampling the shared display.
            val baseline = AssistantScreenCapture.state.value.frameVersion
            hide()
            delay(350)
            val fresh = withTimeoutOrNull(3500) {
                AssistantScreenCapture.state.first { !it.active || it.frameVersion > baseline }
            }
            if (generation != epoch || !AssistantReturnGate.current(ticket)) return@launch
            val file = if (fresh?.active == true && fresh.frameVersion > baseline) AssistantScreenCapture.snapshot(context) else null
            if (generation != epoch || !AssistantReturnGate.current(ticket)) return@launch
            if (file == null) AssistantWorkspace.error("共享画面暂不可用，未发送请求。")
            else { AssistantWorkspace.setPreview(file); AssistantWorkspace.send() }
            MinisVoiceInteractionService.open(context, ticket = ticket)
        }
    }
    private fun minimize() {
        if (!Settings.canDrawOverlays(context)) { AssistantPermissionActivity.open(context, "overlay"); hide(); return }
        awaitingCapture = false
        generation++
        AssistantReturnGate.invalidate()
        AssistantWorkspace.pause()
        if (AssistantBubbleService.start(context)) hide()
    }
    private fun openSettings() {
        AssistantWorkspace.handoffToMain(settings = true)
        awaitingCapture = false; generation++; hide()
        context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("minis://settings/model-groups"), context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    private fun openFullChat() {
        if (!AssistantWorkspace.handoffToMain()) return
        awaitingCapture = false; generation++; hide()
        context.startActivity(Intent(Intent.ACTION_VIEW, AssistantWorkspace.conversationUri(), context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    private fun closeAll() { generation++; AssistantReturnGate.invalidate(); awaitingCapture = false; AssistantWorkspace.close(context); hide() }
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        if (args?.containsKey(MinisVoiceInteractionService.RETURN_TICKET) == true &&
            !AssistantReturnGate.current(args.getLong(MinisVoiceInteractionService.RETURN_TICKET))) {
            if (!shown) setUiEnabled(false)
            return
        }
        val restoringTool = args?.containsKey(AssistantToolSurface.RESTORE_TICKET) == true
        if (restoringTool && !AssistantToolSurface.acceptRestore(this, args!!.getLong(AssistantToolSurface.RESTORE_TICKET))) {
            // Do not call hide(): its asynchronous onHide would pause a newer owner.
            // setUiEnabled only changes this window, without a lifecycle onHide callback.
            if (!shown) setUiEnabled(false)
            return
        }
        if (!restoringTool) AssistantToolSurface.interruptForShow(this)
        if (context.getSystemService(KeyguardManager::class.java).isKeyguardLocked) { closeAll(); return }
        setUiEnabled(true)
        AssistantWorkspace.initialize(context)
        if (!restoringTool) AssistantWorkspace.reclaimFromMain()
        shown = true
        AssistantToolSurface.attach(this)
        if (args?.getBoolean("resume", false) != true) {
            AssistantReturnGate.invalidate(); awaitingCapture = false
        }
        AssistantBubbleService.stop(context)
        AssistantWorkspace.setVisible(true)
        AssistantReturnGate.consumeExpectedHide()
        refreshApproval()
        refreshRepairGuidance()
        if (restoringTool) AssistantToolSurface.onRestored(this)
    }
    override fun onHide() {
        shown = false
        val expected = AssistantReturnGate.consumeExpectedHide()
        if (!expected) {
            AssistantReturnGate.invalidate(); awaitingCapture = false
        }
        // Only an acknowledged, current tool-owned hide may keep the task alive.
        // Permission/capture hides retain their existing pause behaviour.
        if (!AssistantToolSurface.onHide(this, expected)) AssistantWorkspace.pause()
        AssistantWorkspace.setVisible(false); super.onHide()
    }
    internal fun toolSurfaceShown(): Boolean = shown
    internal fun toolSurfaceLocked(): Boolean = context.getSystemService(KeyguardManager::class.java).isKeyguardLocked
    internal fun restoreToolSurface(ticket: Long) {
        show(Bundle().apply { putBoolean("resume", true); putLong(AssistantToolSurface.RESTORE_TICKET, ticket) }, 0)
    }
    override fun onBackPressed() { minimize() }
    override fun onCloseSystemDialogs() {
        AssistantReturnGate.invalidate(); awaitingCapture = false
        AssistantWorkspace.pause(); hide()
    }
    override fun onLockscreenShown() { closeAll(); super.onLockscreenShown() }
    override fun onDestroy() { generation++; AssistantReturnGate.invalidate(); awaitingCapture = false; AssistantToolSurface.detach(this); scope.cancel(); AssistantWorkspace.pause(); AssistantWorkspace.setVisible(false); super.onDestroy() }
}
