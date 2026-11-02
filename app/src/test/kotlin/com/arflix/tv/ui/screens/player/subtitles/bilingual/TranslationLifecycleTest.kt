package com.arflix.tv.ui.screens.player.subtitles.bilingual

import com.arflix.tv.ui.screens.player.subtitles.SubtitleTranslationManager
import com.arflix.tv.ui.screens.player.subtitles.SubtitleTranslationService
import com.arflix.tv.ui.screens.player.subtitles.TranslationResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/** Exercises the real manager/queue. Only the network boundary is mocked; no Android SDK needed. */
@OptIn(ExperimentalCoroutinesApi::class)
class TranslationLifecycleTest {
    private fun TestScope.manager(service: SubtitleTranslationService) =
        SubtitleTranslationManager(service, "Simplified Chinese", backgroundScope).apply { isEnabled = true }

    private fun TestScope.flushBatch() {
        runCurrent()
        advanceTimeBy(151)
        runCurrent()
    }

    private val exits: List<(SubtitleTranslationManager) -> Unit> = listOf(
        { it.isEnabled = false },
        { it.bilingualSource = SubtitleTranslationManager.BilingualSource.EMBEDDED_TRACK },
        { it.displayMode = BilingualDisplayMode.OFF },
        { it.displayMode = BilingualDisplayMode.ORIGINAL_ONLY },
    )

    @Test fun `every mode exit cancels active work drains the queue and gates both entry points`() = runTest {
        exits.forEach { exit ->
            val service = mockk<SubtitleTranslationService>()
            val cancelled = CompletableDeferred<Unit>()
            coEvery { service.translateBatch(any(), any(), any(), any()) } coAnswers {
                try {
                    awaitCancellation()
                } finally {
                    cancelled.complete(Unit)
                }
            }
            val m = manager(service)
            val notices = mutableListOf<Boolean>()
            m.onTranslatingChanged = { notices += it }
            val active = async { m.translateDetailed("active") }
            flushBatch()
            val queued = (1..70).map { n -> async { m.translateDetailed("queued $n") } }
            runCurrent()
            assertTrue(m.isInFlight("queued 70"))

            exit(m)
            runCurrent()
            assertTrue(cancelled.isCompleted)
            assertEquals("active" to false, active.await())
            queued.forEachIndexed { i, deferred -> assertEquals("queued ${i + 1}" to false, deferred.await()) }
            assertFalse(m.isInFlight("active"))
            assertFalse(m.isInFlight("queued 70"))
            assertFalse(m.isTranslating)
            assertEquals(listOf(true, false), notices)
            assertEquals("later" to false, m.translateDetailed("later"))
            assertEquals("later", m.translate("later"))
            m.preTranslateWindow(listOf("later"))
            advanceTimeBy(10_000)
            runCurrent()
            coVerify(exactly = 1) { service.translateBatch(any(), any(), any(), any()) }
            assertEquals(0, m.translatedCount)
        }
    }

    @Test fun `reset before the batching deadline releases waiters without a request`() = runTest {
        val service = mockk<SubtitleTranslationService>(relaxed = true)
        val m = manager(service)
        val pending = async { m.translateDetailed("not sent") }
        runCurrent()
        assertTrue(m.isInFlight("not sent"))
        m.reset()
        assertEquals("not sent" to false, pending.await())
        advanceTimeBy(10_000)
        runCurrent()
        coVerify(exactly = 0) { service.translateBatch(any(), any(), any(), any()) }
        assertFalse(m.isTranslating)
        assertNull(m.bilingualBus.frame.value)
    }

    @Test fun `late old success or error cannot publish or remove a new same-text claim`() = runTest {
        listOf(true, false).forEach { oldSuccess ->
            val service = mockk<SubtitleTranslationService>()
            val replies = mutableListOf<Continuation<TranslationResult>>()
            coEvery { service.translateBatch(any(), any(), any(), any()) } coAnswers {
                // Deliberately non-cooperative, like a blocking HTTP call that cannot be aborted.
                suspendCoroutine { replies += it }
            }
            val m = manager(service)
            val results = mutableListOf<Pair<Boolean, String?>>()
            m.onBatchResult = { success, error -> results += success to error }
            val old = async { m.translateDetailed("same") }
            flushBatch()
            assertEquals(1, replies.size)
            m.reset()
            assertEquals("same" to false, old.await())
            val fresh = async { m.translateDetailed("same") }
            flushBatch()
            assertEquals(2, replies.size)

            replies[0].resume(TranslationResult(listOf("old"), oldSuccess, if (oldSuccess) null else "OLD_ERROR"))
            runCurrent()
            assertTrue(m.isInFlight("same"))
            assertTrue(m.isTranslating)
            assertFalse(fresh.isCompleted)
            assertNull(m.getCached("same"))
            assertTrue(results.isEmpty())
            val joined = async { m.translateDetailed("same") }
            runCurrent()
            coVerify(exactly = 2) { service.translateBatch(any(), any(), any(), any()) }

            replies[1].resume(TranslationResult(listOf("fresh"), true))
            runCurrent()
            assertEquals("fresh" to true, fresh.await())
            assertEquals("fresh" to true, joined.await())
            assertEquals("fresh", m.getCached("same"))
            assertFalse(m.isInFlight("same"))
            assertEquals(listOf(true to null), results)
            advanceTimeBy(1501)
            runCurrent()
            assertFalse(m.isTranslating)
        }
    }

    @Test fun `old multi-chunk lookahead cannot continue after mode exit reset or rapid re-enable`() = runTest {
        (exits + listOf<(SubtitleTranslationManager) -> Unit>({ it.reset() })).forEach { exit ->
            val service = mockk<SubtitleTranslationService>()
            val replies = mutableListOf<Continuation<TranslationResult>>()
            val batches = mutableListOf<List<String>>()
            coEvery { service.translateBatch(any(), any(), any(), any()) } coAnswers {
                batches += firstArg<List<String>>()
                suspendCoroutine { replies += it }
            }
            val m = manager(service)
            val callbacks = mutableListOf<Boolean>()
            m.onBatchResult = { success, _ -> callbacks += success }
            val window = async { m.preTranslateWindow((1..120).map { "line $it" }) }
            runCurrent()
            assertEquals(50, batches.single().size)
            exit(m)
            m.bilingualSource = SubtitleTranslationManager.BilingualSource.AI_TRANSLATION
            m.displayMode = BilingualDisplayMode.BILINGUAL
            m.isEnabled = true
            runCurrent()
            window.await()
            replies.single().resume(TranslationResult(batches.single().map { "old:$it" }, true))
            flushBatch()
            coVerify(exactly = 1) { service.translateBatch(any(), any(), any(), any()) }
            assertTrue(callbacks.isEmpty())
            assertEquals(0, m.translatedCount)
            assertFalse(m.isInFlight("line 1"))
            assertFalse(m.isInFlight("line 51"))
            assertFalse(m.isTranslating)
        }
    }

    @Test fun `mode change from a valid batch callback stops later chunks but keeps that cache`() = runTest {
        val service = mockk<SubtitleTranslationService>()
        coEvery { service.translateBatch(any(), any(), any(), any()) } coAnswers {
            TranslationResult(firstArg<List<String>>().map { "translated:$it" }, true)
        }
        val m = manager(service)
        m.onBatchResult = { _, _ -> m.isEnabled = false; m.isEnabled = true }
        val window = async { m.preTranslateWindow((1..120).map { "line $it" }) }
        flushBatch()
        window.await()
        coVerify(exactly = 1) { service.translateBatch(any(), any(), any(), any()) }
        assertEquals(50, m.translatedCount)
        assertEquals("translated:line 1", m.getCached("line 1"))
        assertNull(m.getCached("line 51"))
        assertFalse(m.isTranslating)
    }

    @Test fun `a completed response cannot escape through a waiter after its mode changed`() = runTest {
        val service = mockk<SubtitleTranslationService>()
        coEvery { service.translateBatch(any(), any(), any(), any()) } returns TranslationResult(listOf("valid cache"), true)
        val m = manager(service)
        m.onBatchResult = { _, _ -> m.isEnabled = false; m.isEnabled = true }
        val old = async { m.translateDetailed("same") }
        flushBatch()
        assertEquals("same" to false, old.await())
        assertEquals("valid cache", m.getCached("same"))
        assertEquals("valid cache" to true, m.translateDetailed("same"))
        coVerify(exactly = 1) { service.translateBatch(any(), any(), any(), any()) }
    }

    @Test fun `completed cache survives mode exits and reset without another paid request`() = runTest {
        val service = mockk<SubtitleTranslationService>()
        coEvery { service.translateBatch(any(), any(), any(), any()) } coAnswers {
            TranslationResult(firstArg<List<String>>().map { "translated:$it" }, true)
        }
        val m = manager(service)
        val translated = async { m.translateDetailed("cached") }
        flushBatch()
        assertEquals("translated:cached" to true, translated.await())
        (exits + listOf<(SubtitleTranslationManager) -> Unit>({ it.reset() })).forEach { exit ->
            exit(m)
            assertEquals("translated:cached", m.getCached("cached"))
            m.bilingualSource = SubtitleTranslationManager.BilingualSource.AI_TRANSLATION
            m.displayMode = BilingualDisplayMode.BILINGUAL
            m.isEnabled = true
            assertEquals("translated:cached" to true, m.translateDetailed("cached"))
        }
        coVerify(exactly = 1) { service.translateBatch(any(), any(), any(), any()) }
    }

    @Test fun `cancelling one waiter cannot cancel the shared claim or corrupt busy state`() = runTest {
        val service = mockk<SubtitleTranslationService>()
        val reply = CompletableDeferred<TranslationResult>()
        coEvery { service.translateBatch(any(), any(), any(), any()) } coAnswers { reply.await() }
        val m = manager(service)
        val notices = mutableListOf<Boolean>()
        m.onTranslatingChanged = { notices += it }
        val cancelled = async { m.translateDetailed("shared") }
        flushBatch()
        val joined = async { m.translateDetailed("shared") }
        runCurrent()
        cancelled.cancel()
        runCurrent()
        assertTrue(m.isTranslating)
        assertTrue(m.isInFlight("shared"))
        reply.complete(TranslationResult(listOf("translated"), true))
        runCurrent()
        assertEquals("translated" to true, joined.await())
        advanceTimeBy(1501)
        runCurrent()
        assertFalse(m.isTranslating)
        assertEquals(listOf(true, false), notices)
        coVerify(exactly = 1) { service.translateBatch(any(), any(), any(), any()) }
    }

    @Test fun `service failure or exception releases lookahead claims and reset cancels its cooldown`() = runTest {
        listOf(false, true).forEach { throws ->
            val service = mockk<SubtitleTranslationService>()
            coEvery { service.translateBatch(any(), any(), any(), any()) } coAnswers {
                if (throws) throw IllegalStateException("broken")
                TranslationResult(emptyList(), false, "broken")
            }
            val m = manager(service)
            val callbacks = mutableListOf<Pair<Boolean, String?>>()
            m.onBatchResult = { success, error -> callbacks += success to error }
            val window = async { m.preTranslateWindow((1..120).map { "line $it" }) }
            runCurrent()
            window.await()
            assertEquals(listOf(false to "broken"), callbacks)
            assertFalse(m.isInFlight("line 1"))
            assertEquals(0, m.translatedCount)
            coVerify(exactly = 1) { service.translateBatch(any(), any(), any(), any()) }
            m.reset()
            coEvery { service.translateBatch(any(), any(), any(), any()) } returns TranslationResult(listOf("new"), true)
            val fresh = async { m.translateDetailed("fresh") }
            flushBatch()
            assertEquals("new" to true, fresh.await())
            assertFalse(m.isInFlight("fresh"))
            coVerify(exactly = 2) { service.translateBatch(any(), any(), any(), any()) }
        }
    }

    @Test fun `service cancellation releases every claim and permits a fresh worker`() = runTest {
        val service = mockk<SubtitleTranslationService>()
        coEvery { service.translateBatch(any(), any(), any(), any()) } throws CancellationException("cancelled service")
        val m = manager(service)
        val callbacks = mutableListOf<Boolean>()
        m.onBatchResult = { success, _ -> callbacks += success }
        val window = async { m.preTranslateWindow((1..120).map { "line $it" }) }
        runCurrent()
        window.await()
        assertFalse(m.isInFlight("line 1"))
        assertFalse(m.isTranslating)
        assertTrue(callbacks.isEmpty())
        coEvery { service.translateBatch(any(), any(), any(), any()) } returns TranslationResult(listOf("fresh"), true)
        val fresh = async { m.translateDetailed("fresh") }
        flushBatch()
        assertEquals("fresh" to true, fresh.await())
        coVerify(exactly = 2) { service.translateBatch(any(), any(), any(), any()) }
    }

    @Test fun `language switch invalidates old results and lookahead without mixing language caches`() = runTest {
        val service = mockk<SubtitleTranslationService>()
        val replies = mutableListOf<Continuation<TranslationResult>>()
        coEvery { service.translateBatch(any(), any(), any(), any()) } coAnswers {
            suspendCoroutine { replies += it }
        }
        val m = manager(service)
        val old = async { m.preTranslateWindow((1..120).map { "line $it" }) }
        runCurrent()
        m.targetLanguage = "French"
        runCurrent()
        old.await()
        val fresh = async { m.translateDetailed("line 1") }
        flushBatch()
        replies[0].resume(TranslationResult(List(50) { "old" }, true))
        runCurrent()
        assertNull(m.getCached("line 1"))
        assertTrue(m.isInFlight("line 1"))
        replies[1].resume(TranslationResult(listOf("bonjour"), true))
        runCurrent()
        assertEquals("bonjour" to true, fresh.await())
        m.targetLanguage = "Simplified Chinese"
        assertNull(m.getCached("line 1"))
        coVerify(exactly = 2) { service.translateBatch(any(), any(), any(), any()) }
    }

    @Test fun `scope shutdown including an already cancelled scope never strands a waiter`() = runTest {
        val service = mockk<SubtitleTranslationService>(relaxed = true)
        val owner = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val m = SubtitleTranslationManager(service, "French", owner).apply { isEnabled = true }
        try {
            val pending = async { m.translateDetailed("pending") }
            runCurrent()
            owner.cancel()
            runCurrent()
            assertEquals("pending" to false, pending.await())
            assertFalse(m.isInFlight("pending"))
            assertFalse(m.isTranslating)
            assertEquals("after shutdown" to false, m.translateDetailed("after shutdown"))
            assertFalse(m.isInFlight("after shutdown"))
            assertFalse(m.isTranslating)
            coVerify(exactly = 0) { service.translateBatch(any(), any(), any(), any()) }
        } finally {
            owner.cancel()
        }
    }
}
