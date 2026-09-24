package com.openminis.app.assistant

/**
 * Local energy-only turn boundary detector, not a speech recognizer. A loud fan or music can
 * qualify; quiet speech can be missed. Does not inspect or change the recorded waveform.
 *
 * Use one instance per recording, from one capture thread. Durations are PCM block durations,
 * not time since the previous callback. Terminal decisions are latched: the caller must handle
 * the first non-CONTINUE result once and stop feeding blocks (not send on every SEND result).
 */
class AssistantVoiceEndpoint {
    enum class Decision { CONTINUE, SEND, SILENT }

    /** Sustained above-threshold energy has occurred; this does not assert semantic speech. */
    var hasSpeech: Boolean = false
        private set

    private var elapsedMs = 0L
    private var candidateMs = 0L
    private var candidateBlocks = 0
    private var quietMs = 0L
    private var terminal: Decision? = null

    /**
     * [level] must be finite normalized RMS in [0, 1]; [durationMs] must be nonnegative.
     * Invalid input throws before changing state. Zero duration is a no-op. Once terminal,
     * all input is ignored. Counters saturate at deadlines, including for Long.MAX_VALUE.
     */
    fun accept(level: Float, durationMs: Long): Decision {
        terminal?.let { return it }
        require(level.isFinite() && level in 0f..1f) { "RMS must be finite and in [0, 1]" }
        require(durationMs >= 0) { "PCM duration must be nonnegative" }
        if (durationMs == 0L) return Decision.CONTINUE

        val blockMs = minOf(durationMs, MAX_DURATION_MS - elapsedMs)
        // Only evidence before the initial deadline can qualify a previously silent turn.
        val evidenceMs = if (hasSpeech) blockMs else minOf(blockMs, INITIAL_TIMEOUT_MS - elapsedMs)
        elapsedMs += blockMs
        if (level >= ENERGY_THRESHOLD) {
            quietMs = 0
            // Require both sustained energy and multiple observations: one averaged noisy
            // block, however long, is not enough to authorize sending audio.
            candidateMs = minOf(MIN_ENERGY_MS, candidateMs + evidenceMs)
            candidateBlocks = minOf(MIN_ENERGY_BLOCKS, candidateBlocks + 1)
            if (candidateMs >= MIN_ENERGY_MS && candidateBlocks >= MIN_ENERGY_BLOCKS) {
                hasSpeech = true
            }
        } else {
            candidateMs = 0
            candidateBlocks = 0
            quietMs = minOf(TRAILING_PAUSE_MS, quietMs + blockMs)
        }

        val decision = when {
            !hasSpeech && elapsedMs >= INITIAL_TIMEOUT_MS -> Decision.SILENT
            hasSpeech && quietMs >= TRAILING_PAUSE_MS -> Decision.SEND
            hasSpeech && elapsedMs >= MAX_DURATION_MS -> Decision.SEND
            else -> Decision.CONTINUE
        }
        if (decision != Decision.CONTINUE) terminal = decision
        return decision
    }

    private companion object {
        const val ENERGY_THRESHOLD = 0.02f
        const val MIN_ENERGY_MS = 240L
        const val MIN_ENERGY_BLOCKS = 3
        const val INITIAL_TIMEOUT_MS = 10_000L
        const val TRAILING_PAUSE_MS = 1_500L
        const val MAX_DURATION_MS = 60_000L
    }
}
