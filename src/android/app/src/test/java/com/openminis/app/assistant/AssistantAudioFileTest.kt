package com.openminis.app.assistant

import com.openminis.app.data.db.MessageEntity
import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AssistantAudioFileTest {
    @get:Rule val temp = TemporaryFolder()
    private val session = UUID.randomUUID().toString()
    private fun wave(seconds: Int) = AssistantAudioSupport.wav(
        ByteArray(seconds * AssistantAudioSupport.SAMPLE_RATE * 2) { (it % 251).toByte() })
    private fun save(seconds: Int = 15) = AssistantAudioSupport.persist(
        "[]", AssistantAudioSupport.part(wave(seconds)), temp.root, session)
    private fun value(json: String) = JSONArray(json).getJSONObject(0).getJSONObject("value")
    private fun file(json: String) = File(temp.root, value(json).getString("path"))
    private fun changed(json: String, key: String, replacement: Any): String {
        val parts = JSONArray(json)
        parts.getJSONObject(0).getJSONObject("value").put(key, replacement)
        return parts.toString()
    }
    private fun rejected(json: String) {
        try {
            AssistantAudioSupport.restore(json, temp.root)
            fail("Invalid saved audio must not restore")
        } catch (_: Exception) { }
        val row = MessageEntity(id = "original", sessionId = session, role = "user",
            partsJson = json, sortOrder = 17, createdAt = 0)
        assertFalse(AssistantAudioRetryPolicy.canRetry(row, temp.root))
        assertNull(AssistantAudioRetryPolicy.retryCutoff(listOf(row), row.id, temp.root))
    }

    @Test fun fifteenAndSixtySecondWavesRemainExactAndBelowDatabaseCap() {
        for (seconds in listOf(15, 60)) {
            val original = wave(seconds)
            val legacy = AssistantAudioSupport.persist("[]", AssistantAudioSupport.part(original))
            assertTrue("Demonstrates the old cap overflow", legacy.length > 500_000)
            val saved = save(seconds)
            assertTrue(saved.partsJson.length < 1024)
            println("${seconds}s: WAV=${original.size} bytes, legacy=${legacy.length} chars, reference=${saved.partsJson.length} chars")
            assertFalse(value(saved.partsJson).has("data"))
            assertArrayEquals(original, file(saved.partsJson).readBytes())
            val restored = AssistantAudioSupport.restore(String(saved.partsJson.toCharArray()), temp.root).single()
            assertArrayEquals(original, Base64.getDecoder().decode(restored.base64Data))
            assertEquals(original.size, value(saved.partsJson).getInt("length"))
            assertFalse(file(saved.partsJson).parentFile.listFiles()!!.any { it.name.endsWith(".tmp") })
        }
    }

    @Test fun legacyInlineStillRestoresWithoutStorage() {
        val original = AssistantAudioSupport.part(wave(15))
        assertEquals(listOf(original), AssistantAudioSupport.restore(AssistantAudioSupport.persist("[]", original)))
    }

    @Test fun audioOnlyReloadPreservesOriginalRetryIdentityAndBytes() {
        val saved = save()
        val row = MessageEntity(id = "persisted-not-ui-ordinal", sessionId = session, role = "user",
            partsJson = saved.partsJson, sortOrder = 17, createdAt = 0)
        val rows = listOf(row, row.copy(id = "answer", role = "assistant", sortOrder = 25))
        assertEquals("Audio message", AssistantAudioRetryPolicy.restoreUserText("user", saved.partsJson, "", temp.root))
        assertEquals(18, AssistantAudioRetryPolicy.retryCutoff(rows, row.id, temp.root))
        assertNull(AssistantAudioRetryPolicy.retryCutoff(rows, "0", temp.root))
        assertFalse(saved.partsJson.contains("Audio message"))
        assertArrayEquals(wave(15), Base64.getDecoder().decode(
            AssistantAudioSupport.restore(row.partsJson, temp.root).single().base64Data))
        file(saved.partsJson).delete()
        val unchanged = rows.toList()
        val cutoff = AssistantAudioRetryPolicy.retryCutoff(rows, row.id, temp.root)
        assertNull(cutoff)
        assertEquals(unchanged, rows)
    }

    @Test fun missingTamperedTruncatedAndInvalidWavFailClosed() {
        val saved = save()
        val target = file(saved.partsJson)
        target.delete()
        rejected(saved.partsJson)
        target.writeBytes(wave(15).apply { this[100] = (this[100].toInt() xor 1).toByte() })
        rejected(saved.partsJson)
        target.writeBytes(wave(15).copyOf(100))
        rejected(saved.partsJson)
        target.writeBytes(wave(15).apply { this[0] = 0 })
        rejected(saved.partsJson)
        rejected(changed(saved.partsJson, "length", AssistantAudioSupport.MAX_PCM_BYTES + 46))
        rejected(changed(saved.partsJson, "length", "480044"))
        rejected(changed(saved.partsJson, "sha256", "0".repeat(64)))
        rejected(changed(saved.partsJson, "data", "AAAA"))
        try { AssistantAudioSupport.restore(saved.partsJson); fail("Storage required") } catch (_: Exception) { }
    }

    @Test fun traversalAbsoluteAndSymlinkReferencesFailClosed() {
        val saved = save()
        val target = file(saved.partsJson)
        for (path in listOf(target.absolutePath, "../${target.name}",
            "minis-sessions/$session/assistant-audio/../${target.name}",
            "minis-sessions/__new__$session/assistant-audio/${target.name}",
            "minis-sessions/$session/assistant-audio/not-a-uuid.wav")) {
            rejected(changed(saved.partsJson, "path", path))
        }
        val outside = temp.newFile("outside.wav").apply { writeBytes(wave(15)) }
        target.delete()
        Files.createSymbolicLink(target.toPath(), outside.toPath())
        rejected(saved.partsJson)
        Files.delete(target.toPath())
        val directory = target.parentFile
        directory.delete()
        val elsewhere = temp.newFolder("elsewhere")
        File(elsewhere, target.name).writeBytes(wave(15))
        Files.createSymbolicLink(directory.toPath(), elsewhere.toPath())
        rejected(saved.partsJson)
        try { save(); fail("Cannot persist through symlink") } catch (_: Exception) { }
        assertArrayEquals(wave(15), outside.readBytes())
    }

    @Test fun failedDatabaseWriteCleanupOnlyDiscardsItsOwnWave() {
        val previous = save()
        val staged = save()
        val unrelated = File(file(staged.partsJson).parentFile, "unrelated.txt").apply { writeText("keep") }
        try { throw IllegalStateException("simulated DB failure") } catch (_: IllegalStateException) { staged.discard() }
        staged.discard()
        assertFalse(file(staged.partsJson).exists())
        assertTrue(file(previous.partsJson).exists())
        assertEquals("keep", unrelated.readText())
    }

    @Test fun failedMetadataSerializationLeavesNoNewWave() {
        val before = save()
        try {
            AssistantAudioSupport.persist("not JSON", AssistantAudioSupport.part(wave(15)), temp.root, session)
            fail("Must reject malformed metadata")
        } catch (_: Exception) { }
        assertEquals(listOf(file(before.partsJson).name), file(before.partsJson).parentFile.list()!!.toList())
    }
}
