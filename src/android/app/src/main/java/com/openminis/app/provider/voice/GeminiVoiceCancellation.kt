package com.openminis.app.provider.voice

import kotlinx.coroutines.ensureActive
import kotlin.coroutines.CoroutineContext

/** Keep cancellation effective even when an empty recording short-circuits HTTP. */
internal fun CoroutineContext.ensureActiveForGeminiVoice() = ensureActive()
