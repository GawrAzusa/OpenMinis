package com.openminis.app.assistant

import com.openminis.app.data.db.MessageEntity
import java.io.File
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class AssistantAudioRetryPolicyTest {
    // Synthetic two-sample PCM16/16 kHz/mono WAV, not recorded user data.
    private val wav = "UklGRigAAABXQVZFZm10IBAAAAABAAEAgD4AAAB9AAACABAAZGF0YQQAAAAAAAEA"
    private val audio = """[{"type":"assistantAudio","value":{"format":"wav","data":"$wav"}}]"""
    private fun text(value: String) = org.json.JSONArray().put(
        org.json.JSONObject().put("type", "text").put("value", value),
    ).toString()
    private fun row(id: String, order: Int, parts: String, role: String = "user") =
        MessageEntity(id = id, sessionId = "session", role = role, partsJson = parts,
            sortOrder = order, createdAt = 0)

    private fun history() = listOf(
        row("earlier", 3, text("Earlier question")),
        row("tool", 8, """[{"type":"toolUse","value":{"toolUseId":"call1"}}]""", "assistant"),
        row("result", 9, """[{"type":"toolResult","value":{"toolUseId":"call1","output":"done"}}]"""),
        row("reminder", 12, text("<system-reminder>Continue</system-reminder>")),
        row("audio-original", 17, audio),
        row("audio-answer", 20, text("Old audio answer"), "assistant"),
        row("selected-text", 31, text("Later question")),
        row("text-answer", 45, text("Old text answer"), "assistant"),
    )

    @Test fun laterTextRetryDoesNotDependOnPreviouslyHiddenAudioOrVisibleOrdinal() {
        val rows = history()
        // Before the fix, reload dropped this captionless row. Exercise the production
        // UI restoration helper, then select by persisted identity, NOT a user count.
        assertEquals("Audio message", AssistantAudioRetryPolicy.restoreUserText("user", audio, ""))
        val selectedId = "selected-text"
        val cutoff = AssistantAudioRetryPolicy.retryCutoff(rows, selectedId)!!
        assertEquals(32, cutoff)
        val retained = rows.filter { it.sortOrder < cutoff }
        assertEquals(selectedId, retained.last().id)
        assertEquals(rows.dropLast(1), retained)
        // Even an old/stale UI without the audio bubble cannot change the anchor.
        val legacyVisibleIds = listOf("earlier", "audio-answer", selectedId, "text-answer")
        assertEquals(cutoff, AssistantAudioRetryPolicy.retryCutoff(rows, legacyVisibleIds.single { it == selectedId }))
    }

    @Test fun reloadedAudioItselfCanRetryWithExactWaveformAndEarlierToolHistory() {
        val rows = history()
        val selected = rows.single { it.id == "audio-original" }
        assertEquals("Audio message", AssistantAudioRetryPolicy.restoreUserText(selected.role, selected.partsJson, ""))
        val cutoff = AssistantAudioRetryPolicy.retryCutoff(rows, selected.id)!!
        assertEquals(18, cutoff)
        val retained = rows.filter { it.sortOrder < cutoff }
        assertSame(selected, retained.last())
        assertEquals(rows.takeWhile { it.id != selected.id }, retained.dropLast(1))
        val restored = AssistantAudioSupport.restore(retained.last().partsJson).single()
        assertEquals(wav, restored.base64Data)
        assertArrayEquals(Base64.getDecoder().decode(wav), Base64.getDecoder().decode(restored.base64Data))
        assertEquals(audio, retained.last().partsJson)
        assertFalse(retained.last().partsJson.contains("Audio message"))
    }

    @Test fun missingTargetFailsClosedWithoutFallbackToAnotherRequest() {
        val rows = history().toMutableList()
        val before = rows.toList()
        val cutoff = AssistantAudioRetryPolicy.retryCutoff(rows, "missing-original")
        assertNull(cutoff)
        if (cutoff != null) rows.removeAll { it.sortOrder >= cutoff }
        assertEquals(before, rows)
        assertNull(AssistantAudioRetryPolicy.retryCutoff(rows, ""))
    }

    @Test fun malformedBlankToolAndReminderRowsCannotAuthorizeRetry() {
        val invalid = listOf("", "not JSON", "[]", "[null]", text(" \n"),
            text("<system-reminder>Continue</system-reminder>"),
            text("  <system-reminder>unclosed"), text("<user-attached-files>private paths</user-attached-files>"),
            """[{"type":"text","value":{"unexpected":"object"}}]""",
            """[{"type":"text","value":null}]""",
            """[{"type":"toolResult","value":"result"}]""",
            """[{"type":"assistantAudio","value":{"format":"wav","data":""}}]""",
            audio.replace(wav, "invalid-base64"), audio.replace("wav", "mp3"))
        for (parts in invalid) {
            val selected = row("selected", 1, parts)
            assertFalse(parts, AssistantAudioRetryPolicy.canRetry(selected))
            assertNull(parts, AssistantAudioRetryPolicy.retryCutoff(listOf(selected), selected.id))
            assertEquals(parts, "", AssistantAudioRetryPolicy.restoreUserText("user", parts, ""))
        }
        assertFalse(AssistantAudioRetryPolicy.canRetry(row("assistant", 1, audio, "assistant")))
    }

    @Test fun labelIsShortUiOnlyAndCaptionIsUnchanged() {
        assertEquals("Audio message", AssistantAudioRetryPolicy.restoreUserText("user", audio, " \n"))
        assertEquals("My caption", AssistantAudioRetryPolicy.restoreUserText("user", audio, "My caption"))
        assertEquals("", AssistantAudioRetryPolicy.restoreUserText("assistant", audio, ""))
        assertTrue(AssistantAudioRetryPolicy.canRetry(row("text", 1, text("Retry text"))))
        assertTrue(AssistantAudioRetryPolicy.canRetry(row("mixed", 1,
            AssistantAudioSupport.persist(text("caption"), AssistantAudioSupport.restore(audio).single()))))
    }

    @Test fun ambiguousIdentityAndOverflowFailClosed() {
        val target = row("duplicate", 1, audio)
        assertNull(AssistantAudioRetryPolicy.retryCutoff(listOf(target, target.copy(sortOrder = 3)), target.id))
        assertNull(AssistantAudioRetryPolicy.retryCutoff(listOf(target.copy(sortOrder = Int.MAX_VALUE)), target.id))
    }

    @Test fun viewModelValidatesIdentityBeforeEveryDestructiveRetryEffect() {
        val source = source()
        val retry = source.substringAfter("fun retryFromMessage(messageId: String) {")
            .substringBefore("fun deleteFromMessage(messageId: String)")
        val validation = retry.indexOf("AssistantAudioRetryPolicy.retryCutoff(")
        val failClosed = retry.indexOf("return@launch", validation)
        assertTrue(validation >= 0 && failClosed > validation)
        assertTrue(retry.contains("dbMessages, messageId,"))
        for (effect in listOf("deleteMessagesAfter(sid, cutoffSortOrder)", "_messages.value =",
            "_promptQueue.value =", "retainStreamFlushStates(keptIds)",
            "revokeMemoryWritesInDeletedMessages(deletedMessages)", "agentHistory.clear()")) {
            assertTrue("Must validate before $effect", retry.indexOf(effect) > failClosed)
        }
        assertFalse(retry.contains("visibleUserIndex"))
        assertFalse(retry.contains("visibleUserCount"))
        assertFalse(retry.contains("sendMessage("))
        assertFalse(retry.contains("appendMessage("))
        assertTrue(retry.contains("agentHistory.add(entity.toLLMMessage())"))
        assertTrue(retry.contains("runRerunStreamTail(provider, \"retryFromMessage\")"))
    }

    @Test fun reloadWiresAudioLabelBeforeBlankFilterWithoutChangingLlmHistory() {
        val source = source()
        val reload = source.substringAfter("private fun List<MessageEntity>.toChatMessages()")
            .substringBefore("private data class ToolResultData")
        val label = reload.indexOf("AssistantAudioRetryPolicy.restoreUserText(")
        assertTrue(label >= 0)
        assertTrue(reload.indexOf("if (entity.role == \"user\" && text.isBlank()") > label)
        assertTrue(reload.contains("id = entity.id,"))
        val llm = source.substringAfter("private fun MessageEntity.toLLMMessage()")
        assertFalse(llm.contains("restoreUserText("))
        assertTrue(llm.contains("audioParts = com.openminis.app.assistant.AssistantAudioSupport.restore(partsJson)"))
    }

    private fun source(): String {
        val suffix = "src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt"
        return listOf(File(suffix), File("app/$suffix"), File("src/android/app/$suffix"))
            .first { it.isFile }.readText()
    }
}
