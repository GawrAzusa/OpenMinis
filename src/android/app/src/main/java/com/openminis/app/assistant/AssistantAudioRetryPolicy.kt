package com.openminis.app.assistant

import com.openminis.app.data.db.MessageEntity
import org.json.JSONArray

/** Retry always anchors to the original persisted request, never a UI ordinal or label. */
internal object AssistantAudioRetryPolicy {
    private const val AUDIO_LABEL = "Audio message"

    /** Null means no destructive action is allowed, including UI/memory truncation. */
    fun retryCutoff(rows: List<MessageEntity>, selectedId: String): Int? {
        val target = rows.singleOrNull { it.id == selectedId } ?: return null
        if (!canRetry(target) || target.sortOrder < 0 || target.sortOrder == Int.MAX_VALUE) return null
        return target.sortOrder + 1
    }

    fun canRetry(row: MessageEntity): Boolean =
        row.role == "user" && requestContent(row.partsJson) != null

    /** UI-only representation. Never persisted or used as the replayed request. */
    fun restoreUserText(role: String, partsJson: String, visibleText: String): String {
        if (role != "user" || visibleText.isNotBlank()) return visibleText
        return if (requestContent(partsJson)?.second == true) AUDIO_LABEL else visibleText
    }

    private fun requestContent(partsJson: String): Pair<String, Boolean>? = try {
        val parts = JSONArray(partsJson)
        val text = buildString {
            for (i in 0 until parts.length()) {
                val part = parts.getJSONObject(i)
                if (part.getString("type") == "text") {
                    // Do not coerce malformed objects/nulls into executable text.
                    val raw = part.get("value") as? String ?: return null
                    append(raw.replace(
                        Regex("<system-reminder>.*?(</system-reminder>|$)", RegexOption.DOT_MATCHES_ALL), "",
                    ).replace(
                        Regex("<user-attached-files>.*?(</user-attached-files>|$)", RegexOption.DOT_MATCHES_ALL), "",
                    ))
                }
            }
        }
        // Uses the same bounded WAV validation as history replay; damaged audio fails closed.
        val hasAudio = AssistantAudioSupport.restore(partsJson).isNotEmpty()
        if (text.isNotBlank() || hasAudio) text to hasAudio else null
    } catch (_: Exception) {
        null
    }
}
