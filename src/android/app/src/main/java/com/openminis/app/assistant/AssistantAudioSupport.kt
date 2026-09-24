package com.openminis.app.assistant

import com.openminis.app.data.model.LLMMessage
import com.openminis.app.provider.LLMProvider
import com.openminis.app.provider.gemini.GeminiProvider
import com.openminis.app.provider.openai.OpenAIProvider
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID
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

    /** Legacy inline encoding for compatibility; production sends use the file-backed overload. */
    fun persist(parts: String, audio: LLMMessage.AudioPart?): String = if (audio == null) parts else
        JSONArray(parts).put(JSONObject().put("type", "assistantAudio").put("value",
            JSONObject().put("format", audio.format).put("data", audio.base64Data))).toString()

    private const val UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
    private val savedPath = Regex("minis-sessions/$UUID_PATTERN/assistant-audio/$UUID_PATTERN\\.wav")

    /** Only this newly created file belongs to the pending DB write. Never sweep a directory. */
    class PersistedAudio internal constructor(
        val partsJson: String,
        private val discardFile: () -> Unit,
    ) {
        fun discard() = discardFile()
    }

    /** Publish a complete WAV before the DB reference. Session deletion already owns this subtree. */
    fun persist(parts: String, audio: LLMMessage.AudioPart?, filesRoot: File, sessionId: String): PersistedAudio {
        if (audio == null) return PersistedAudio(parts) {}
        require(Regex(UUID_PATTERN).matches(sessionId)) { "Invalid persisted audio session." }
        require(audio.format == "wav")
        val bytes = decodeWav(audio.base64Data)
        val relative = "minis-sessions/$sessionId/assistant-audio/${UUID.randomUUID()}.wav"
        val file = resolveFile(filesRoot, relative, createDirectories = true)
        val staging = File(file.parentFile, "${file.name}.tmp")
        var staged = false
        var published = false
        require(!Files.exists(file.toPath(), NOFOLLOW_LINKS)) { "Saved audio file already exists." }
        try {
            java.nio.channels.FileChannel.open(staging.toPath(), setOf(StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE, NOFOLLOW_LINKS)).use { channel ->
                staged = true
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            // No non-atomic fallback: failure must not publish a partial reference.
            Files.move(staging.toPath(), file.toPath(), ATOMIC_MOVE)
            published = true
            val json = JSONArray(parts).put(JSONObject().put("type", "assistantAudio").put("value",
                JSONObject().put("format", "wav").put("path", relative)
                    .put("length", bytes.size).put("sha256", sha256(bytes)))).toString()
            // Do not let the repository's unchanged safety cap turn this reference into text.
            require(json.length <= 500_000) { "Audio message metadata is too large." }
            return PersistedAudio(json) {
                runCatching { Files.deleteIfExists(resolveFile(filesRoot, relative).toPath()) }
                Unit
            }
        } catch (failure: Throwable) {
            if (staged) runCatching { Files.deleteIfExists(staging.toPath()) }
            if (published) runCatching { Files.deleteIfExists(resolveFile(filesRoot, relative).toPath()) }
            throw failure
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun decodeWav(data: String): ByteArray {
        require(data.length <= (MAX_PCM_BYTES + 44 + 2) / 3 * 4) { "Saved audio is too large." }
        return Base64.getDecoder().decode(data).also {
            require(validateWav(it) == null) { "Saved assistant audio is damaged." }
        }
    }

    private fun resolveFile(root: File, relative: String, createDirectories: Boolean = false): File {
        require(savedPath.matches(relative)) { "Invalid saved audio path." }
        // Canonicalize the trusted app root only; reject symlinks in every untrusted component.
        var current = root.canonicalFile.toPath()
        val components = relative.split('/')
        for ((index, component) in components.withIndex()) {
            current = current.resolve(component)
            require(!Files.isSymbolicLink(current)) { "Saved audio symlink is not allowed." }
            if (index < components.lastIndex) {
                if (createDirectories && !Files.exists(current, NOFOLLOW_LINKS)) Files.createDirectory(current)
                require(Files.isDirectory(current, NOFOLLOW_LINKS)) { "Saved audio directory is missing." }
            }
        }
        return current.toFile()
    }

    private fun readSavedWav(value: JSONObject, filesRoot: File?): ByteArray {
        require(filesRoot != null) { "Saved audio storage is unavailable." }
        require(!value.has("data")) { "Ambiguous saved audio." }
        val lengthValue = value.get("length")
        require(lengthValue is Int || lengthValue is Long) { "Invalid saved audio length." }
        val length = (lengthValue as Number).toLong()
        require(length in 46L..(MAX_PCM_BYTES + 44).toLong()) { "Saved audio is too large." }
        val hash = value.getString("sha256")
        require(Regex("[0-9a-f]{64}").matches(hash)) { "Invalid saved audio hash." }
        val file = resolveFile(filesRoot, value.getString("path"))
        require(Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS)) { "Saved audio is missing." }
        val bytes = ByteArray(length.toInt())
        Files.newByteChannel(file.toPath(), setOf(StandardOpenOption.READ, NOFOLLOW_LINKS)).use { channel ->
            require(channel.size() == length) { "Saved audio length mismatch." }
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) require(channel.read(buffer) > 0) { "Saved audio is incomplete." }
            require(channel.read(ByteBuffer.allocate(1)) == -1) { "Saved audio length mismatch." }
        }
        require(validateWav(bytes) == null && sha256(bytes) == hash) { "Saved assistant audio is damaged." }
        return bytes
    }

    /** Read separately from permissive legacy history parsing: damaged audio must fail closed. */
    fun restore(parts: String, filesRoot: File? = null): List<LLMMessage.AudioPart> {
        if (!parts.contains("assistantAudio")) return emptyList()
        val array = JSONArray(parts)
        return (0 until array.length()).mapNotNull { i ->
            val obj = array.getJSONObject(i)
            if (obj.optString("type") != "assistantAudio") null else {
                val value = obj.getJSONObject("value")
                require(value.getString("format") == "wav") { "Unsupported saved assistant audio format." }
                if (value.has("path")) {
                    part(readSavedWav(value, filesRoot))
                } else {
                    val data = value.getString("data")
                    decodeWav(data)
                    LLMMessage.AudioPart("wav", data)
                }
            }
        }
    }
}
