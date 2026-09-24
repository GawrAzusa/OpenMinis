package com.openminis.app.provider.voice

import okhttp3.Request
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Request construction only: no HTTP calls, real credentials, or audio synthesis. */
class GeminiVoiceRequestTest {
    @Test fun keyIsOnlyInHeaderAndDefaultRequestIsPreserved() {
        val request = GeminiVoiceProvider("fixture", "https://example.invalid", "fixture-key")
            .buildVoiceOutputRequest(VoiceOutputRequest(input = "Hello"))
        assertEquals("https://example.invalid/v1beta/models/gemini-2.5-flash-preview-tts:generateContent",
            request.url.toString())
        assertEquals("fixture-key", request.header("x-goog-api-key"))
        assertNull(request.url.query)
        assertNull(request.header("Authorization"))
        assertEquals("POST", request.method)
        val body = body(request)
        assertEquals("Hello", body.getJSONArray("contents").getJSONObject(0)
            .getJSONArray("parts").getJSONObject(0).getString("text"))
        val config = body.getJSONObject("generationConfig")
        assertEquals("AUDIO", config.getJSONArray("responseModalities").getString(0))
        assertEquals("Kore", voice(body))
        assertFalse(body.toString().contains("fixture-key"))
    }

    @Test fun configuredBaseModelAndExistingVoiceAreUnchanged() {
        val request = GeminiVoiceProvider("fixture", "https://example.invalid/proxy/v1beta/", "fixture-key")
            .buildVoiceOutputRequest(VoiceOutputRequest(input = "Hi", model = "custom-tts", voice = "Puck"))
        assertEquals("https://example.invalid/proxy/v1beta/models/custom-tts:generateContent",
            request.url.toString())
        assertEquals("fixture-key", request.header("x-goog-api-key"))
        assertNull(request.url.query)
        assertEquals("Puck", voice(body(request)))
    }

    @Test fun absentKeyIsNotAppendedToUrlAndUnknownVoiceKeepsExistingDefault() {
        val request = GeminiVoiceProvider("fixture", "https://example.invalid/v1beta", null)
            .buildVoiceOutputRequest(VoiceOutputRequest(input = "Hi", voice = "not-a-voice"))
        assertNull(request.url.query)
        assertEquals("", request.header("x-goog-api-key"))
        assertEquals("Kore", voice(body(request)))
    }

    private fun body(request: Request): JSONObject {
        val buffer = Buffer()
        request.body!!.writeTo(buffer)
        return JSONObject(buffer.readUtf8())
    }

    private fun voice(body: JSONObject): String = body.getJSONObject("generationConfig")
        .getJSONObject("speechConfig").getJSONObject("voiceConfig")
        .getJSONObject("prebuiltVoiceConfig").getString("voiceName")
}
