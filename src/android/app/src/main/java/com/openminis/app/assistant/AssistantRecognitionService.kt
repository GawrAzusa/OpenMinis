package com.openminis.app.assistant

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/**
 * Android requires a recognizer in voice-service metadata. Preserve conventional dictation by
 * delegating explicitly to an installed SYSTEM recognizer, never back to ourselves. The assistant
 * window does NOT use this service: its original audio goes to the task model.
 */
class AssistantRecognitionService : RecognitionService() {
    private var recognizer: SpeechRecognizer? = null
    private var owner: Callback? = null
    override fun onStartListening(intent: Intent, listener: Callback) {
        if (owner != null) { listener.error(SpeechRecognizer.ERROR_RECOGNIZER_BUSY); return }
        val candidates = packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
        val service = candidates.mapNotNull { it.serviceInfo }.firstOrNull {
            it.packageName != packageName && (it.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        }
        if (service == null) { listener.error(SpeechRecognizer.ERROR_CLIENT); return }
        owner = listener
        try {
            val engine = SpeechRecognizer.createSpeechRecognizer(this, ComponentName(service.packageName, service.name))
            recognizer = engine
            engine.setRecognitionListener(object : RecognitionListener {
                private fun dispatch(action: (Callback) -> Unit) { owner?.let { runCatching { action(it) } } }
                override fun onReadyForSpeech(params: Bundle?) { dispatch { it.readyForSpeech(params ?: Bundle()) } }
                override fun onBeginningOfSpeech() { dispatch { it.beginningOfSpeech() } }
                override fun onRmsChanged(rmsdB: Float) { dispatch { it.rmsChanged(rmsdB) } }
                override fun onBufferReceived(buffer: ByteArray?) { if (buffer != null) dispatch { it.bufferReceived(buffer) } }
                override fun onEndOfSpeech() { dispatch { it.endOfSpeech() } }
                override fun onError(error: Int) { dispatch { it.error(error) }; cleanup() }
                override fun onResults(results: Bundle?) { dispatch { it.results(results ?: Bundle()) }; cleanup() }
                override fun onPartialResults(partialResults: Bundle?) { dispatch { it.partialResults(partialResults ?: Bundle()) } }
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
            engine.startListening(intent)
        } catch (e: RuntimeException) { runCatching { listener.error(SpeechRecognizer.ERROR_CLIENT) }; cleanup() }
    }
    override fun onStopListening(listener: Callback) { if (owner === listener) recognizer?.stopListening() }
    override fun onCancel(listener: Callback) { if (owner === listener) cleanup() }
    private fun cleanup() { owner = null; recognizer?.cancel(); recognizer?.destroy(); recognizer = null }
    override fun onDestroy() { cleanup(); super.onDestroy() }
}
