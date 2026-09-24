package com.openminis.app.assistant

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.provider.gemini.GeminiProvider
import com.openminis.app.provider.openai.OpenAIProvider
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Local HTTP fixtures exercise the production serializer, not a remote Gemini model. */
class AssistantGeminiAudioTest {
    private val model = LLMModel("gemini-3-audio-test", "Audio fixture", "Google",
        inputModalities = listOf("text", "audio", "image"))
    private val wav = AssistantAudioSupport.wav(byteArrayOf(0, 0, 1, 0, -1, -1))
    private val audio = AssistantAudioSupport.part(wav)
    private lateinit var server: MockWebServer
    private val fixtureCredential = java.util.UUID.randomUUID().toString()
    private lateinit var provider: GeminiProvider

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        provider = GeminiProvider(fixtureCredential, model,
            basePath = server.url("/v1beta").toString().trimEnd('/'))
    }

    @After fun tearDown() { server.shutdown() }

    @Test fun capabilityRequiresDeclaredAudioAndKeepsResponsesUnsupported() {
        assertNull(AssistantAudioSupport.unavailableReason(provider))
        provider.model = model.copy(inputModalities = null)
        assertNotNull(AssistantAudioSupport.unavailableReason(provider))
        provider.model = model.copy(inputModalities = listOf("text", "image"))
        assertNotNull(AssistantAudioSupport.unavailableReason(provider))
        assertNotNull(AssistantAudioSupport.unavailableReason(null))
        assertNull(AssistantAudioSupport.unavailableReason(OpenAIProvider(fixtureCredential, model)))
        assertNotNull(AssistantAudioSupport.unavailableReason(
            OpenAIProvider(fixtureCredential, model, useResponsesAPI = true)))
    }

    @Test fun nativeAudioToolsUseValidatedModeWithoutForcingActions() = runBlocking {
        enqueueReply()
        provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "", audioParts = listOf(audio))),
            null, 100, tools = listOf(com.openminis.app.data.model.AgentToolDefinition("shell_execute", "Execute", emptyMap())))
        val body = JSONObject(server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8())
        assertEquals("VALIDATED", body.getJSONObject("toolConfig").getJSONObject("functionCallingConfig").getString("mode"))
        assertEquals("shell_execute", body.getJSONArray("tools").getJSONObject(0).getJSONArray("function_declarations").getJSONObject(0).getString("name"))
    }

    @Test fun textOnlyToolsKeepExistingMode() = runBlocking {
        enqueueReply()
        provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "hello")), null, 100,
            tools = listOf(com.openminis.app.data.model.AgentToolDefinition("shell_execute", "Execute", emptyMap())))
        assertFalse(JSONObject(server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8()).has("toolConfig"))
    }

    @Test fun audioWithoutToolsDoesNotEnableFunctionCalling() = runBlocking {
        enqueueReply()
        provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "", audioParts = listOf(audio))), null, 100)
        assertFalse(JSONObject(server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8()).has("toolConfig"))
    }

    @Test fun audioOnlyUsesGenerateContentWithOriginalWav() = runBlocking {
        enqueueReply()
        assertEquals("fixture reply", provider.sendMessage(listOf(
            LLMMessage(LLMMessage.Role.USER, "", audioParts = listOf(audio))), null, 100).text)
        val parts = takeContents(stream = false).getJSONObject(0).getJSONArray("parts")
        assertAudio(parts)
        assertEquals(1, parts.length())
    }

    @Test fun structuredCaptionAndImageKeepAudioInStreamingRequest() = runBlocking {
        enqueueReply(stream = true)
        val chunks = provider.streamMessage(listOf(LLMMessage(LLMMessage.Role.USER, "Look and listen",
            audioParts = listOf(audio), contentParts = listOf(
                AgentContentPart.Text("Look and listen"),
                AgentContentPart.ImageData(byteArrayOf(1, 2, 3), "image/png")))), null, 100).toList()
        assertEquals("fixture reply", chunks.filterIsInstance<LLMStreamChunk.Text>().single().text)
        val parts = takeContents(stream = true).getJSONObject(0).getJSONArray("parts")
        assertAudio(parts)
        assertEquals("Look and listen", parts.getJSONObject(0).getString("text"))
        assertEquals("image/png", parts.getJSONObject(1).getJSONObject("inlineData").getString("mimeType"))
        // Android Base64 is a default-return mock in local JVM tests; image byte encoding
        // is outside this test. Audio is already base64 and is checked byte-for-byte below.
        assertEquals(3, parts.length())
    }

    @Test fun legacyImageAndCaptionKeepAudio() = runBlocking {
        enqueueReply()
        provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "Caption", audioParts = listOf(audio))),
            null, 100, imageParts = listOf(LLMMessage.ImagePart(byteArrayOf(1, 2, 3), "image/png")))
        val parts = takeContents(stream = false).getJSONObject(0).getJSONArray("parts")
        assertAudio(parts)
        assertEquals("image/png", parts.getJSONObject(0).getJSONObject("inlineData").getString("mimeType"))
        assertEquals("Caption", parts.getJSONObject(1).getString("text"))
        assertEquals(3, parts.length())
    }

    @Test fun toolFollowupAndRestoredHistoryReplayKeepEveryAudioTurn() = runBlocking {
        val restored = AssistantAudioSupport.restore(AssistantAudioSupport.persist("[]", audio))
        val history = listOf(
            LLMMessage(LLMMessage.Role.USER, "Listen", audioParts = restored,
                contentParts = listOf(AgentContentPart.Text("Listen"))),
            LLMMessage(LLMMessage.Role.ASSISTANT, "", contentParts = listOf(
                AgentContentPart.ToolUse("call1", "example", JSONObject().put("key", "value"),
                    thoughtSignature = "fixture-signature"))),
            LLMMessage(LLMMessage.Role.USER, "", contentParts = listOf(
                AgentContentPart.ToolResult("call1", "example", "tool result", false))))
        for (stream in listOf(false, true)) {
            for (messages in listOf(history, history + LLMMessage(LLMMessage.Role.USER, "Next",
                audioParts = restored))) {
                enqueueReply(stream)
                if (stream) provider.streamMessage(messages, null, 100).toList()
                else provider.sendMessage(messages, null, 100)
                val contents = takeContents(stream)
                assertEquals(messages.size, contents.length())
                assertEquals("user", contents.getJSONObject(0).getString("role"))
                assertAudio(contents.getJSONObject(0).getJSONArray("parts"))
                val call = contents.getJSONObject(1).getJSONArray("parts").getJSONObject(0)
                assertEquals("model", contents.getJSONObject(1).getString("role"))
                assertEquals("fixture-signature", call.getString("thoughtSignature"))
                assertEquals("example", call.getJSONObject("functionCall").getString("name"))
                assertEquals("value", call.getJSONObject("functionCall").getJSONObject("args").getString("key"))
                val resultParts = contents.getJSONObject(2).getJSONArray("parts")
                assertEquals(1, resultParts.length())
                assertEquals("user", contents.getJSONObject(2).getString("role"))
                assertEquals("tool result", resultParts.getJSONObject(0).getJSONObject("functionResponse")
                    .getJSONObject("response").getString("result"))
                if (messages.size > history.size) assertAudio(contents.getJSONObject(3).getJSONArray("parts"))
            }
        }
    }

    @Test fun unsupportedAudioIsRejectedRatherThanMislabelledOrDropped() = runBlocking {
        for (message in listOf(
            LLMMessage(LLMMessage.Role.USER, "", audioParts = listOf(audio.copy(format = "mp3"))),
            LLMMessage(LLMMessage.Role.ASSISTANT, "", audioParts = listOf(audio)),
        )) {
            try {
                provider.sendMessage(listOf(message), null, 100)
                fail("Unsupported input must fail before sending HTTP")
            } catch (_: IllegalArgumentException) {
                assertEquals(0, server.requestCount)
            }
        }
    }

    private fun enqueueReply(stream: Boolean = false) {
        val fixture = """{"candidates":[{"content":{"parts":[{"text":"fixture reply"}]},"finishReason":"STOP"}]}"""
        server.enqueue(MockResponse().setHeader("Content-Type", if (stream) "text/event-stream" else "application/json")
            .setBody(if (stream) "data: $fixture\n\n" else fixture))
    }

    private fun takeContents(stream: Boolean): JSONArray {
        val request = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertEquals("POST", request.method)
        assertEquals("/v1beta/models/${model.id}:${if (stream) "streamGenerateContent" else "generateContent"}",
            request.requestUrl!!.encodedPath)
        if (stream) assertEquals("sse", request.requestUrl!!.queryParameter("alt"))
        assertEquals(fixtureCredential, request.getHeader("x-goog-api-key"))
        assertNull(request.requestUrl!!.queryParameter("key"))
        assertFalse(request.requestUrl.toString().contains(fixtureCredential))
        return JSONObject(request.body.readUtf8()).getJSONArray("contents")
    }

    private fun assertAudio(parts: JSONArray) {
        val data = (0 until parts.length()).map { parts.getJSONObject(it) }
            .mapNotNull { it.optJSONObject("inlineData") }
            .single { it.getString("mimeType") == "audio/wav" }
        assertEquals(audio.base64Data, data.getString("data"))
        assertArrayEquals(wav, Base64.getDecoder().decode(data.getString("data")))
    }
}
