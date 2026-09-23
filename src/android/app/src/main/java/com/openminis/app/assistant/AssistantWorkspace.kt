package com.openminis.app.assistant

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.lifecycle.ViewModelProvider
import com.openminis.app.MinisApp
import com.openminis.app.assistant.screen.AssistantScreenCapture
import com.openminis.app.speech.ReadAloudPlayer
import com.openminis.app.speech.VoiceOutputState
import com.openminis.app.ui.chat.ChatViewModel
import com.openminis.app.ui.chat.ChatViewModelStore
import com.openminis.app.ui.chat.clearAttachments
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.util.UUID

/** Process-local workspace; permissions, recording and execution never restore after death. */
object AssistantWorkspace {
    data class Line(val role: String, val text: String)
    data class State(
        val lines: List<Line> = emptyList(), val busy: Boolean = false,
        val paused: Boolean = false, val canResume: Boolean = false,
        val recording: Boolean = false, val level: Float = 0f,
        val error: String? = null, val model: String = "正在准备模型…",
        val draft: String = "", val preview: File? = null, val audio: ByteArray? = null,
        val speech: Boolean = false, val voiceLabel: String = "",
    )
    private val mutable = MutableStateFlow(State())
    val state: StateFlow<State> = mutable.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observers: Job? = null
    private var vm: ChatViewModel? = null
    private var recorder: AssistantAudioRecorder? = null
    private var speaker: ReadAloudPlayer? = null
    private var app: MinisApp? = null
    private var visible = false
    internal val toolOwnership = AssistantOperationOwnership()
    private var mainDraftShared = false
    private var captureEpoch = 0
    // Retained until the VM acknowledges DB + history, not its synchronous busy claim.
    private var pendingTurn: State? = null
    private var discardPendingMedia = false
    private var spoken = mutableSetOf<String>()
    var sessionKey: String? = null
        private set

    fun initialize(context: Context): Boolean {
        if (vm != null) return true
        val application = context.applicationContext as? MinisApp ?: return false
        if (!application.subsystemsReady()) {
            error("Minis 尚未就绪，请打开应用检查初始化状态。")
            return false
        }
        app = application
        val key = "__new__${UUID.randomUUID()}"
        sessionKey = key
        vm = ViewModelProvider(ChatViewModelStore.ownerFor(key), ChatViewModel.factory(
            key, application.chatRepository, application.providerRepository, application,
            application.memoryRepository, application.skillRepository, application.mcpRepository,
        ))[ChatViewModel::class.java]
        recorder = AssistantAudioRecorder(application)
        VoiceOutputState.init(application)
        speaker = ReadAloudPlayer(application)
        val model = vm!!
        observers = scope.launch {
            launch {
                var wasBusy = false
                combine(model.messages, model.streamingById, model.isStreaming, model.error, model.modelName) {
                    messages, streaming, busy, problem, name ->
                    val lines = messages.filter { it.role == "user" || it.role == "assistant" }.takeLast(16)
                        .map { Line(it.role, (streaming[it.id]?.content ?: it.content).take(12000)) }
                    mutable.update { it.copy(lines = lines, busy = busy,
                        error = problem ?: messages.lastOrNull { row -> row.role == "assistant" }?.error ?: it.error, model = name.ifBlank { "尚未配置模型" }) }
                    // Only the final answer of a completed turn, never intermediate narration/history.
                    if (wasBusy && !busy) {
                        val answer = completedAssistantReply(messages)
                        if (answer != null && answer.id !in spoken && problem == null && visible &&
                            !mutable.value.paused && mutable.value.speech && !mutable.value.recording) {
                            speaker?.speakQueued(answer.content)
                        }
                        spoken.addAll(messages.filter { it.role == "assistant" }.map { it.id })
                    }
                    wasBusy = busy
                }.collect()
            }
            launch { model.canResume.collect { value -> mutable.update { it.copy(canResume = value) } } }
            launch { speaker!!.outputRoute.collect { route ->
                if (route.kind != "idle") mutable.update { it.copy(voiceLabel = when {
                    route.fallback -> "语音服务不可用 · 已回退系统声音"
                    route.kind == "provider" -> "语音输出 · ${route.modelLabel.orEmpty()}"
                    route.kind == "unavailable" -> "语音输出不可用"
                    else -> "系统声音 · 可在设置中换自然语音"
                }) }
            } }
        }
        refreshVoiceLabel()
        return true
    }

    fun error(message: String?) { mutable.update { it.copy(error = message) } }
    fun setDraft(text: String) { if (pendingTurn == null) mutable.update { it.copy(draft = text) } }
    fun setVisible(value: Boolean) {
        visible = value
        if (!value) { cancelRecording(); speaker?.stop() }
        refreshVoiceLabel()
    }
    fun refreshVoiceLabel() {
        val choice = app?.providerRepository?.resolveVoiceOutputChoice()
        val label = choice?.entry?.second?.model?.displayName
        mutable.update { it.copy(voiceLabel = if (label != null) "语音输出 · $label" else "系统声音 · 可在设置中换自然语音") }
    }
    fun toggleSpeech() {
        val enabled = !mutable.value.speech
        VoiceOutputState.setEnabled(enabled)
        if (enabled) VoiceOutputState.setMuted(false) else speaker?.stop()
        mutable.update { it.copy(speech = enabled) }
    }
    fun beginRecording(context: Context) {
        if (!initialize(context) || mutable.value.busy || pendingTurn != null) return
        val reason = vm?.assistantAudioUnavailableReason()
        if (reason != null) { error(reason); return }
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            AssistantPermissionActivity.open(context, "microphone")
            return
        }
        cancelRecording()
        val epoch = ++captureEpoch
        speaker?.stop()
        error(null)
        val started = recorder?.start(
            onLevel = { level -> scope.launch { if (epoch == captureEpoch) mutable.update { it.copy(level = level) } } },
            onError = { problem -> scope.launch { if (epoch == captureEpoch) {
                cancelRecording(); error(problem)
            } } },
        ) == true
        mutable.update { it.copy(recording = started, audio = null) }
        if (started) scope.launch {
            delay(AssistantAudioSupport.MAX_SECONDS * 1000L)
            if (captureEpoch == epoch && mutable.value.recording) endRecording()
        }
    }
    fun endRecording() {
        if (!mutable.value.recording) return
        val epoch = captureEpoch
        mutable.update { it.copy(recording = false, level = 0f) }
        scope.launch {
            val wav = recorder?.stop()
            if (captureEpoch == epoch && visible) {
                mutable.update { it.copy(audio = wav, error = if (wav == null) "没有录到有效音频，请重试。" else null) }
            }
        }
    }
    fun cancelRecording() {
        captureEpoch++
        recorder?.cancel()
        mutable.update { it.copy(recording = false, level = 0f) }
    }
    fun discardAudio() { mutable.update { it.copy(audio = null) } }
    fun setPreview(file: File?) { mutable.update { it.copy(preview = file) } }
    fun discardPreview() {
        mutable.value.preview?.let { if (it.parentFile?.name == "assistant-preview") it.delete() }
        mutable.update { it.copy(preview = null) }
    }
    fun imageUnavailableReason(): String? = vm?.assistantImageUnavailableReason() ?: if (vm == null) "模型尚未就绪" else null

    fun send() {
        val model = vm ?: return
        val current = mutable.value.copy(audio = mutable.value.audio?.copyOf())
        if (current.busy || current.recording || pendingTurn != null) return
        if (current.draft.isBlank() && current.audio == null && current.preview == null) return
        val frameVersion = AssistantScreenCapture.state.value.selectionVersion
        if (current.preview != null) {
            model.assistantImageUnavailableReason()?.let { error(it); return }
            if (!current.preview.exists()) { setPreview(null); error("截图已失效，请重新截取。"); return }
            if (!model.addAssistantPreview(current.preview) {
                mutable.value.preview == current.preview && current.preview.isFile &&
                    AssistantScreenCapture.state.value.selectionVersion == frameVersion
            }) {
                error("无法附加截图，请重试。"); return
            }
        }
        pendingTurn = current
        discardPendingMedia = false
        mutable.update { it.copy(error = null, paused = false) }
        val onAdmission: (Boolean) -> Unit = { committed ->
            if (pendingTurn === current) {
                pendingTurn = null
                if (committed) {
                    mutable.update { it.copy(draft = "", audio = null,
                        preview = if (it.preview == current.preview) null else it.preview) }
                } else {
                    // A screen file is borrowed, and may have been revoked while setup waited.
                    // Never resurrect it or privately copy it to evade capture invalidation.
                    val previewAvailable = current.preview == null ||
                        (mutable.value.preview == current.preview && current.preview.isFile &&
                            AssistantScreenCapture.state.value.selectionVersion == frameVersion)
                    mutable.update { it.copy(draft = current.draft,
                        audio = if (discardPendingMedia) null else current.audio,
                        preview = if (previewAvailable && !discardPendingMedia) it.preview else null,
                        error = if (discardPendingMedia) it.error
                            else if (!previewAvailable) "截图已失效，请重新截取；文字和原声音频已保留。"
                            else model.error.value ?: "请求未写入对话，输入已保留，请重试。") }
                }
            }
        }
        val accepted = if (current.audio != null)
            model.sendAssistantAudio(current.audio, current.draft, onAdmission)
        else model.sendAssistantText(current.draft, onAdmission)
        if (!accepted) {
            model.clearAttachments()
            onAdmission(false)
        }
    }
    fun pause() {
        // A hidden voice-session callback (or its global dismissal receiver) must not
        // cancel work the user explicitly started in the full app after handoff.
        if (toolOwnership.handedOff) return
        pauseOwnedTask()
    }
    private fun pauseOwnedTask() {
        toolOwnership.invalidate()
        mutable.update { it.copy(paused = true) }
        cancelRecording()
        speaker?.stop()
        vm?.pauseAssistantTask()
        mutable.update { it.copy(paused = true, busy = false) }
    }

    /** Settings stays reachable with raw media; full chat cannot compose that audio. */
    fun handoffToMain(settings: Boolean = false): Boolean {
        val current = mutable.value
        if (!settings && (pendingTurn != null || current.recording || current.audio != null || current.preview != null)) {
            error("原声或截图尚未发送，完整聊天不能接管这些附件。请先在助理中发送或移除；模型未配置可先打开设置，返回助理后继续。输入仍保留。")
            return false
        }
        pause()
        AssistantReturnGate.invalidate()
        // Same cached VM, draft only: never put text in an auto-send deep link.
        mainDraftShared = pendingTurn == null && vm != null
        if (mainDraftShared) vm?.setInputText(mutable.value.draft)
        toolOwnership.handoff()
        return true
    }

    /** Pause this VM's full-chat task BEFORE reclaiming visual control. */
    fun reclaimFromMain() {
        if (!toolOwnership.handedOff) return
        pauseOwnedTask()
        if (mainDraftShared) mutable.update { it.copy(draft = vm?.inputText?.value.orEmpty()) }
        mainDraftShared = false
        toolOwnership.reclaim()
    }
    fun resume() {
        if (pendingTurn != null) { error("正在确认输入是否已保存，请稍候。"); return }
        vm?.resume()
        mutable.update { it.copy(paused = false, error = null) }
    }
    fun close(context: Context) {
        discardPendingMedia = true // Still consume a late commit acknowledgement to avoid duplicate drafts.
        pause()
        AssistantScreenCapture.stop(context)
        discardPreview(); discardAudio()
        AssistantBubbleService.stop(context)
        visible = false
    }
    fun conversationUri(): Uri = Uri.parse("minis://session/${vm?.activeSessionId ?: sessionKey.orEmpty()}")
    fun newConversation(context: Context) {
        if (mutable.value.busy || pendingTurn != null) { error("请先停止当前任务并等待输入保存完成，再开始新对话。"); return }
        close(context)
        observers?.cancel(); speaker?.shutdown()
        sessionKey?.let { ChatViewModelStore.release(it) }
        vm = null; recorder = null; speaker = null; spoken.clear()
        mutable.value = State()
        initialize(context)
        visible = true
    }
}
