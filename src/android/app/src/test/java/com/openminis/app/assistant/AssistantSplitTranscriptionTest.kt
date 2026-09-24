package com.openminis.app.assistant

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class AssistantSplitTranscriptionTest {
    private val owner = AssistantSplitTranscription()
    private fun ticket(draft: String = "", revision: Long = 0) = owner.begin("chat", 1, draft, revision)!!

    @Test fun successfulTranscriptAddsToUnchangedDraftAndClaimsOnlyOnce() = runBlocking {
        val ticket = ticket("请帮我")
        assertNull(owner.begin("chat", 1, "请帮我", 0))
        assertEquals(AssistantSplitTranscription.Result.Text("请帮我\n查看日程"),
            owner.run(ticket, { "  查看日程 \n" }, { ticket }))
        assertEquals(AssistantSplitTranscription.Result.Stale,
            owner.complete(ticket, "重复", "chat", 1, "请帮我", 0, true))
    }

    @Test fun blankNeverSendsEvenWithAnExistingDraft() = runBlocking {
        val ticket = ticket("保留草稿")
        assertEquals(AssistantSplitTranscription.Result.Blank, owner.run(ticket, { " \n\t" }, { ticket }))
        assertNotNull(owner.begin("chat", 1, "保留草稿", 0))
    }

    @Test fun changedDraftDuringAwaitIsNotOverwritten() = runBlocking {
        val ticket = ticket("原草稿")
        val response = CompletableDeferred<String>()
        var current = ticket
        val result = async(start = CoroutineStart.UNDISPATCHED) { owner.run(ticket, { response.await() }, { current }) }
        current = ticket.copy(draft = "用户正在输入", revision = 1)
        response.complete("过期听写")
        assertEquals(AssistantSplitTranscription.Result.Stale, result.await())
        assertEquals("用户正在输入", current.draft)
    }

    @Test fun editingAndRestoringSameDraftStillInvalidatesAwaitedReply() = runBlocking {
        val ticket = ticket("相同文字")
        assertEquals(AssistantSplitTranscription.Result.Stale,
            owner.run(ticket, { "旧结果" }, { ticket.copy(revision = 2) }))
    }

    @Test fun replacedSessionCaptureAndHiddenWindowRejectLateReplies() = runBlocking {
        for (change in listOf<(AssistantSplitTranscription.Ticket) -> AssistantSplitTranscription.Ticket?>(
            { it.copy(session = "new chat") }, { it.copy(capture = 2) }, { null }, { it.copy(session = null) },
        )) {
            val ticket = ticket()
            val response = CompletableDeferred<String>()
            var current: AssistantSplitTranscription.Ticket? = ticket
            val result = async(start = CoroutineStart.UNDISPATCHED) { owner.run(ticket, { response.await() }, { current }) }
            current = change(ticket)
            response.complete("旧请求")
            assertEquals(AssistantSplitTranscription.Result.Stale, result.await())
        }
    }

    @Test fun pauseTypingHomeCloseCancellationRejectsNonCooperativeLateSuccess() = runBlocking {
        val ticket = ticket()
        val response = CompletableDeferred<String>()
        val result = async(start = CoroutineStart.UNDISPATCHED) { owner.run(ticket, { response.await() }, { ticket }) }
        owner.cancel()
        response.complete("不应发送")
        assertEquals(AssistantSplitTranscription.Result.Stale, result.await())
    }

    @Test fun activeCancellationReachesSuspendedProviderAndNeverBecomesFailure() = runBlocking {
        val ticket = ticket()
        var cancelled = false
        var delivered = false
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            owner.run(ticket, {
                try { awaitCancellation() } finally { cancelled = true }
            }, { ticket })
            delivered = true
        }
        owner.cancel()
        job.cancelAndJoin()
        assertTrue(cancelled)
        assertFalse(delivered)
        assertNotNull(owner.begin("chat", 2, "", 0))
    }

    @Test fun nonCooperativeProviderCannotReturnAfterJobCancellation() = runBlocking {
        val ticket = ticket()
        val response = CompletableDeferred<String>()
        var delivered = false
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            owner.run(ticket, { withContext(NonCancellable) { response.await() } }, { ticket })
            delivered = true
        }
        job.cancel()
        response.complete("迟到")
        job.join()
        assertFalse(delivered)
    }

    @Test fun genuineFailureLeavesRequestAvailableForSameAudioRetry() = runBlocking {
        val wav = byteArrayOf(82, 73, 70, 70, 1, 2, 3)
        var tries = 0
        val transcribe: suspend () -> String = {
            assertArrayEquals(byteArrayOf(82, 73, 70, 70, 1, 2, 3), wav)
            if (++tries == 1) throw IllegalStateException("failure")
            "成功"
        }
        val first = ticket("草稿")
        assertEquals(AssistantSplitTranscription.Result.Failure, owner.run(first, transcribe, { first }))
        val retry = ticket("草稿")
        assertEquals(AssistantSplitTranscription.Result.Text("草稿\n成功"), owner.run(retry, transcribe, { retry }))
        assertEquals(2, tries)
    }

    @Test fun oldFailureDoesNotConsumeNewCaptureOrReplaceItsError() = runBlocking {
        val first = ticket()
        val response = CompletableDeferred<String>()
        val result = async(start = CoroutineStart.UNDISPATCHED) { owner.run(first, { response.await() }, { first }) }
        owner.cancel()
        val second = ticket("新草稿")
        response.completeExceptionally(IllegalStateException("old failure"))
        assertEquals(AssistantSplitTranscription.Result.Stale, result.await())
        assertEquals(AssistantSplitTranscription.Result.Text("新草稿\n新结果"), owner.run(second, { "新结果" }, { second }))
    }
}
