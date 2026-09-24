package com.openminis.app.provider.voice

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Native Google provider wiring over loopback only; no credentials or external services. */
class GeminiVoiceIntegrationTest {
    @Test fun modelsAndInputOutputRequestsRemainIndependent() {
        val provider = GeminiVoiceProvider("google", "https://example.invalid/v1beta/", "fixture-key")
        assertTrue(provider.supportsVoiceInput)
        val input = provider.buildVoiceInputRequest(VoiceInputRequest(byteArrayOf(1, 2)))
        assertTrue(input.url.toString().endsWith("/models/gemini-3.5-transcribe:generateContent"))
        assertEquals("fixture-key", input.header("x-goog-api-key"))
        assertNull(input.header("Authorization"))
        assertNull(input.url.query)
        val configured = provider.buildVoiceInputRequest(VoiceInputRequest(byteArrayOf(1, 2), model = "gemini-2.5-flash"))
        assertTrue(configured.url.toString().endsWith("/models/gemini-2.5-flash:generateContent"))
        assertTrue(JSONObject(Buffer().also { configured.body!!.writeTo(it) }.readUtf8()).has("systemInstruction"))
        for (model in listOf("gemini-3.8-flash-lite-tts", "gemini-3.8-flash-tts")) {
            val output = provider.buildVoiceOutputRequest(VoiceOutputRequest("literal text", model, "Kore", 0.8f))
            val body = JSONObject(Buffer().also { output.body!!.writeTo(it) }.readUtf8())
            val part = body.getJSONArray("contents").getJSONObject(0).getJSONArray("parts").getJSONObject(0)
            assertEquals("literal text", part.getString("text"))
            assertTrue(part.has("speechMetadata"))
            assertFalse(body.getJSONObject("generationConfig").has("responseMimeType"))
        }
        assertEquals("gemini-2.5-flash-preview-tts", provider.defaultVoiceOutputModel())
    }

    @Test fun actualHttpTranscriptionReturnsOnlyText() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"candidates":[{"finishReason":"STOP","content":{"parts":[{"text":"打开浏览器"}]}}]}"""))
            val provider = GeminiVoiceProvider("fixture", server.url("/").toString(), null)
            assertEquals("打开浏览器", provider.transcribe(VoiceInputRequest(byteArrayOf(1, 2))).text)
            assertEquals("/v1beta/models/gemini-3.5-transcribe:generateContent", server.takeRequest().path)
        }
    }

    @Test fun emptyRecordingIsNotUploaded() = runBlocking {
        val provider = GeminiVoiceProvider("fixture", "https://example.invalid", null)
        assertEquals("", provider.transcribe(VoiceInputRequest(byteArrayOf())).text)
    }

    @Test fun actualHttpWavPassesThroughAndPcmIsWrapped() = runBlocking {
        val pcm = byteArrayOf(1, 2, 3, 4)
        val wav = VoiceProvider.wrapPcm16InWav(pcm, 24000)
        MockWebServer().use { server ->
            val provider = GeminiVoiceProvider("fixture", server.url("/").toString(), null)
            for ((mime, bytes) in listOf("audio/wav" to wav, "audio/L16;rate=24000" to pcm)) {
                val encoded = java.util.Base64.getEncoder().encodeToString(bytes)
                server.enqueue(MockResponse().setBody("""{"candidates":[{"content":{"parts":[{"inlineData":{"mimeType":"$mime","data":"$encoded"}}]}}]}"""))
                assertArrayEquals(wav, provider.synthesize(VoiceOutputRequest("hello", "gemini-3.8-flash-lite-tts")))
            }
        }
    }

    @Test fun malformedHttpSuccessIsNotSilentlyAccepted() = runBlocking {
        MockWebServer().use { server ->
            val provider = GeminiVoiceProvider("fixture", server.url("/").toString(), null)
            server.enqueue(MockResponse().setBody("{}"))
            try {
                provider.transcribe(VoiceInputRequest(byteArrayOf(1, 2)))
                fail("Missing candidate must fail")
            } catch (_: VoiceProviderException.Parse) { }
            server.enqueue(MockResponse().setBody("{}"))
            try {
                provider.synthesize(VoiceOutputRequest("hello"))
                fail("Missing audio must fail")
            } catch (_: VoiceProviderException.Parse) { }
        }
    }

    @Test fun cancellingTranscriptionCancelsTheUnderlyingCall() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val provider = GeminiVoiceProvider("fixture", server.url("/").toString(), null)
            val pending = async(Dispatchers.IO) { provider.transcribe(VoiceInputRequest(byteArrayOf(1, 2))) }
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            val call = VoiceProvider.httpClient.dispatcher.runningCalls().single { it.request().url.port == server.port }
            pending.cancelAndJoin()
            assertTrue(call.isCanceled())
            try { pending.await(); fail("Cancellation must not return a transcript") } catch (_: CancellationException) { }
        }
    }
}
