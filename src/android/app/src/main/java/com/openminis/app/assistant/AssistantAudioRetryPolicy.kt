package com.openminis.app.assistant

import com.openminis.app.data.db.MessageEntity
import java.io.File
import org.json.JSONArray

/** Retry always anchors to the original persisted request, never a UI ordinal or label. */
internal object AssistantAudioRetryPolicy {
    private const val AUDIO_LABEL = "Audio message"

    /** Null means no destructive action is allowed, including UI/memory truncation. */
    fun retryCutoff(rows: List<MessageEntity>, selectedId: String, filesRoot: File? = null): Int? {
        val target = rows.singleOrNull { it.id == selectedId } ?: return null
        if (!canRetry(target, filesRoot) || target.sortOrder < 0 || target.sortOrder == Int.MAX_VALUE) return null
        return target.sortOrder + 1
    }

    fun canRetry(row: MessageEntity, filesRoot: File? = null): Boolean =
        row.role == "user" && requestContent(row.partsJson, filesRoot) != null

    /** UI-only representation. Never persisted or used as the replayed request. */
    fun restoreUserText(role: String, partsJson: String, visibleText: String, filesRoot: File? = null): String {
        if (role != "user" || visibleText.isNotBlank()) return visibleText
        return if (requestContent(partsJson, filesRoot)?.second == true) AUDIO_LABEL else visibleText
    }

    private fun requestContent(partsJson: String, filesRoot: File?): Pair<String, Boolean>? = try {
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
        val hasAudio = AssistantAudioSupport.restore(partsJson, filesRoot).isNotEmpty()
        if (text.isNotBlank() || hasAudio) text to hasAudio else null
    } catch (_: Exception) {
        null
    }
}
