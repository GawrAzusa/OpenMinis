package com.openminis.app.assistant

import com.openminis.app.data.model.LLMMessage
import com.openminis.app.provider.LLMProvider
import com.openminis.app.provider.gemini.GeminiProvider
import com.openminis.app.provider.openai.OpenAIProvider
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/** Raw audio only: never transcribes, substitutes text, or chooses another provider. */
object AssistantAudioSupport {
    const val SAMPLE_RATE = 16000
    const val MAX_SECONDS = 60
    const val MAX_PCM_BYTES = SAMPLE_RATE * 2 * MAX_SECONDS

    fun unavailableReason(provider: LLMProvider?): String? = when {
        provider == null -> "No model configured."
        provider !is GeminiProvider &&
            (provider !is OpenAIProvider || !provider.supportsAssistantAudioTransport) ->
            "Raw assistant audio requires Gemini or an OpenAI-compatible Chat Completions provider."
        "audio" !in provider.model.inputModalities.orEmpty() ->
            "The selected model does not declare audio input support. Select an audio-capable model."
        else -> null
    }

    /** Canonical bounded PCM16 mono WAV; rejecting other formats avoids mislabelled payloads. */
    fun validateWav(bytes: ByteArray): String? {
        if (bytes.size !in 46..(MAX_PCM_BYTES + 44) || (bytes.size - 44) % 2 != 0) {
            return "Record between one sample and 60 seconds of audio."
        }
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun tag(offset: Int, value: String) = bytes.copyOfRange(offset, offset + 4)
            .contentEquals(value.toByteArray(Charsets.US_ASCII))
        return if (tag(0, "RIFF") && tag(8, "WAVE") && tag(12, "fmt ") && tag(36, "data") &&
            b.getInt(4) == bytes.size - 8 && b.getInt(16) == 16 && b.getShort(20).toInt() == 1 &&
            b.getShort(22).toInt() == 1 && b.getInt(24) == SAMPLE_RATE &&
            b.getInt(28) == SAMPLE_RATE * 2 && b.getShort(32).toInt() == 2 &&
            b.getShort(34).toInt() == 16 && b.getInt(40) == bytes.size - 44
        ) null else "Audio must be 16 kHz mono PCM16 WAV."
    }

    fun wav(pcm: ByteArray): ByteArray {
        require(pcm.isNotEmpty() && pcm.size <= MAX_PCM_BYTES && pcm.size % 2 == 0)
        return ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + pcm.size); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(1); putInt(SAMPLE_RATE); putInt(SAMPLE_RATE * 2)
            putShort(2); putShort(16); put("data".toByteArray()); putInt(pcm.size); put(pcm)
        }.array()
    }

    fun part(wav: ByteArray): LLMMessage.AudioPart {
        require(validateWav(wav) == null)
        return LLMMessage.AudioPart("wav", Base64.getEncoder().encodeToString(wav))
    }

    fun persist(parts: String, audio: LLMMessage.AudioPart?): String = if (audio == null) parts else
        JSONArray(parts).put(JSONObject().put("type", "assistantAudio").put("value",
            JSONObject().put("format", audio.format).put("data", audio.base64Data))).toString()

    /** Read separately from permissive legacy history parsing: damaged audio must fail closed. */
    fun restore(parts: String): List<LLMMessage.AudioPart> {
        if (!parts.contains("assistantAudio")) return emptyList()
        val array = JSONArray(parts)
        return (0 until array.length()).mapNotNull { i ->
            val obj = array.getJSONObject(i)
            if (obj.optString("type") != "assistantAudio") null else {
                val value = obj.getJSONObject("value")
                require(value.getString("format") == "wav") { "Unsupported saved assistant audio format." }
                val data = value.getString("data")
                require(data.length <= (MAX_PCM_BYTES + 44 + 2) / 3 * 4) { "Saved audio is too large." }
                require(validateWav(Base64.getDecoder().decode(data)) == null) { "Saved assistant audio is damaged." }
                LLMMessage.AudioPart("wav", data)
            }
        }
    }
}
