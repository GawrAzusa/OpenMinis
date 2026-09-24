package com.openminis.app.assistant

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.provider.openai.OpenAIProvider
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AssistantAudioSupportTest {
    private val model = LLMModel("audio-test", "Audio test", "OpenAI", inputModalities = listOf("audio", "text"))

    @Test fun pcmWavRoundTripAndBounds() {
        val pcm = byteArrayOf(0, 0, 127, 1, -1, -1)
        val wav = AssistantAudioSupport.wav(pcm)
        assertNull(AssistantAudioSupport.validateWav(wav))
        assertArrayEquals(pcm, wav.copyOfRange(44, wav.size))
        assertNotNull(AssistantAudioSupport.validateWav(byteArrayOf()))
        assertNotNull(AssistantAudioSupport.validateWav(wav.copyOf().apply { this[24] = 1 }))
        assertNotNull(AssistantAudioSupport.validateWav(ByteArray(AssistantAudioSupport.MAX_PCM_BYTES + 46)))
        val audio = AssistantAudioSupport.part(wav)
        assertEquals(listOf(audio), AssistantAudioSupport.restore(AssistantAudioSupport.persist("[]", audio)))
    }

    @Test fun replySpeechSourceNeverQueuesToolMetadata() {
        // Source-level safety contract, not a device playback test.
        val suffix = "src/main/java/com/openminis/app/ui/chat/ChatScreen.kt"
        val source = listOf(java.io.File(suffix), java.io.File("app/$suffix"),
            java.io.File("src/android/app/$suffix")).first { it.isFile }.readText()
        assertFalse(source.contains("ToolSpeech.announcement"))
        assertTrue(source.contains("replyTts.appendText(text.substring(spokenUpTo))"))
    }

    @Test fun gatesUnknownModelAndResponsesWithoutSwitching() {
        assertNotNull(AssistantAudioSupport.unavailableReason(null))
        assertNull(AssistantAudioSupport.unavailableReason(OpenAIProvider("test", model)))
        assertNotNull(AssistantAudioSupport.unavailableReason(OpenAIProvider("test", model.copy(inputModalities = null))))
        assertNotNull(AssistantAudioSupport.unavailableReason(OpenAIProvider("test", model, useResponsesAPI = true)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun corruptHistoryFailsInsteadOfBecomingText() {
        AssistantAudioSupport.restore("""[{"type":"assistantAudio","value":{"format":"wav","data":"AAAA"}}]""")
    }

    @Test fun structuredAudioSurvivesToolFollowupAndHistoryReplay() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val provider = OpenAIProvider("test", model, basePath = server.url("/").toString().trimEnd('/'))
            val audio = AssistantAudioSupport.part(AssistantAudioSupport.wav(byteArrayOf(0, 0, 1, 0)))
            val restored = AssistantAudioSupport.restore(AssistantAudioSupport.persist("[]", audio))
            val user = LLMMessage(LLMMessage.Role.USER, "Listen", audioParts = restored,
                contentParts = listOf(AgentContentPart.Text("Listen")))
            val history = listOf(user,
                LLMMessage(LLMMessage.Role.ASSISTANT, "", contentParts = listOf(
                    AgentContentPart.ToolUse("call1", "example", JSONObject()))),
                LLMMessage(LLMMessage.Role.USER, "", contentParts = listOf(
                    AgentContentPart.ToolResult("call1", "example", "result", false))))
            for (messages in listOf(listOf(user.copy(content = "", contentParts = emptyList())), listOf(user), history,
                history + LLMMessage(LLMMessage.Role.USER, "Next question"))) {
                server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                    "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":null}]}\n\n" +
                        "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"))
                provider.sendMessage(messages, null, 100)
                val wire = JSONObject(server.takeRequest().body.readUtf8()).getJSONArray("messages")
                val content = wire.getJSONObject(0).getJSONArray("content")
                val input = (0 until content.length()).map { content.getJSONObject(it) }
                    .single { it.getString("type") == "input_audio" }.getJSONObject("input_audio")
                assertEquals(audio.base64Data, input.getString("data"))
                assertEquals("wav", input.getString("format"))
            }
        } finally { server.shutdown() }
    }
}
