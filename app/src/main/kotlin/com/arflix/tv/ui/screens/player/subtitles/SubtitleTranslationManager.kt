package com.arflix.tv.ui.screens.player.subtitles

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

class SubtitleTranslationManager(
    private var service: SubtitleTranslationService,
    targetLanguage: String,
    private val scope: CoroutineScope
) {
    // Serialises mode changes, claims and result publication; never held across a suspension.
    private val lifecycleLock = Any()

    /**
     * Language the AI translates into (a name, e.g. "Simplified Chinese"). Cached translations
     * are keyed by it, so switching languages never shows a line translated for the old one, and
     * the on-screen frame is invalidated so an in-flight result for the old language is dropped.
     */
    @Volatile
    internal var targetLanguage: String = targetLanguage
        set(value) = synchronized(lifecycleLock) {
            if (field != value) {
                field = value
                bilingualBus.resetSecondary()
                invalidateWorkLocked()
            }
        }
    companion object {
        const val MOCK_MODE = false
        // Groq free tier: 30 RPM. Subtitles change every 2-4s naturally (~15-20 RPM).
        // No artificial rate limit needed — just don't fire concurrent requests.
        // If we get a 429, we back off 5s.
        private const val BATCH_WINDOW_MS = 150L  // wait up to 150ms for more items to batch
        /** Earlier lines (with their translations) sent as read-only context per request. */
        const val CONTEXT_LINES = 8
        /** Lines per look-ahead request: big enough that fixed prompt overhead is small. */
        const val CHUNK_LINES = 50
    }

    @Volatile
    var isEnabled: Boolean = false
        set(value) = synchronized(lifecycleLock) {
            if (field != value) {
                field = value
                bilingualBus.clear()
                invalidateWorkLocked()
            }
        }

    // ── Bilingual subtitles ─────────────────────────────────────────────────
    /**
     * On-screen AI subtitle frame (source text + translation) consumed by the Compose
     * BilingualSubtitleOverlay. While AI translation is active the renderer publishes here
     * instead of rewriting Media3 cue text.
     */
    val bilingualBus = com.arflix.tv.ui.screens.player.subtitles.bilingual.BilingualCueBus()

    /** What the AI subtitle layer shows. Synced from the persisted settings by PlayerScreen. */
    @Volatile
    var displayMode: com.arflix.tv.ui.screens.player.subtitles.bilingual.BilingualDisplayMode =
        com.arflix.tv.ui.screens.player.subtitles.bilingual.BilingualDisplayMode.BILINGUAL
        set(value) = synchronized(lifecycleLock) {
            if (field != value) {
                field = value
                if (!value.needsTranslation) {
                    bilingualBus.resetSecondary()
                    invalidateWorkLocked()
                }
            }
        }

    /**
     * Where the second language comes from while the bilingual layer is active:
     * AI translation of the primary (path B, ARVIO's existing Gemini/Groq flow), or a second
     * embedded subtitle track of the same file (path A — never calls the translation API).
     */
    enum class BilingualSource { AI_TRANSLATION, EMBEDDED_TRACK }

    @Volatile
    var bilingualSource: BilingualSource = BilingualSource.AI_TRANSLATION
        set(value) = synchronized(lifecycleLock) {
            if (field != value) {
                field = value
                bilingualBus.resetSecondary()
                invalidateWorkLocked()
            }
        }

    /** Path A's secondary-line provider (null = primary only). */
    @Volatile
    var secondarySource: com.arflix.tv.ui.screens.player.subtitles.bilingual.DualTrackSecondarySource? = null
        set(value) {
            if (field !== value) bilingualBus.resetSecondary()
            field = value
        }

    /** Short user-facing notice about the smart bilingual mode (shown briefly by the overlay). */
    val bilingualNotice = kotlinx.coroutines.flow.MutableStateFlow<com.arflix.tv.ui.screens.player.subtitles.bilingual.BilingualNotice?>(null)

    /** Called by the cue publisher after every path-A lookup (drives the low-confidence fallback). */
    @Volatile
    var onSecondaryLookup: ((com.arflix.tv.ui.screens.player.subtitles.bilingual.DualTrackSecondarySource.Lookup) -> Unit)? = null

    /** True when the current display mode needs translations at all (saves API calls otherwise). */
    val translationNeeded: Boolean
        get() = isEnabled && displayMode.needsTranslation && bilingualSource == BilingualSource.AI_TRANSLATION

    /**
     * Translates [text] through the normal queue/cache and reports whether it really succeeded.
     * On failure the existing queue completes with the source text and does not cache it.
     */
    suspend fun translateDetailed(text: String): Pair<String, Boolean> {
        val (owner, pending) = synchronized(lifecycleLock) {
            if (!translationNeeded) return text to false
            val owner = session
            owner to claimLocked(owner, text)
        }
        val result = pending.await()
        return synchronized(lifecycleLock) { if (isCurrent(owner)) result else text to false }
    }

    private fun cacheKey(lang: String, text: String): String = lang + '\u0001' + text
    var removeHearingImpaired: Boolean = true
    @Volatile var removeSubtitleHearingImpaired: Boolean = false

    var onTranslatingChanged: ((Boolean) -> Unit)? = null
    var onBatchResult: ((success: Boolean, error: String?) -> Unit)? = null
    /**
     * Fired when translation is active but the cues carry no extractable text — i.e. the chosen
     * AI source is an image track (PGS/VOBSUB). Metadata alone can't be trusted to catch this
     * (containers report null/generic MIME), so this is the runtime backstop that stops AI from
     * silently rendering the untranslated source language.
     */
    var onUntranslatableSource: (() -> Unit)? = null

    val translatedCount: Int get() = cache.size

    @Volatile var isTranslating: Boolean = false
        private set

    private val cache = ConcurrentHashMap<String, String>()

    /** Persistent store behind [cache] (set by the player; null in tests). */
    @Volatile var diskCache: TranslationDiskCache? = null
        set(value) {
            field = value
            if (value != null) scope.launch(kotlinx.coroutines.Dispatchers.IO) { value.load() }
        }

    /** Title of what is playing ("Show S01E02 – Episode"), sent so the model knows the setting. */
    @Volatile var contentTitle: String? = null

    /** The latest translated lines in playback order (source → translation), sent as context. */
    private val recent = ArrayDeque<Pair<String, String>>()
    private val recentLock = Any()

    private fun contextFor(batch: List<String>, lang: String): List<Pair<String, String>> = synchronized(recentLock) {
        val skip = batch.toHashSet()
        recent.filter { it.first !in skip && cache[cacheKey(lang, it.first)] != null }.takeLast(CONTEXT_LINES)
    }

    private fun remember(lang: String, pairs: List<Pair<String, String>>) {
        synchronized(recentLock) {
            pairs.forEach { recent.addLast(it) }
            while (recent.size > CONTEXT_LINES * 2) recent.removeFirst()
        }
        diskCache?.let { disk ->
            val entries = pairs.filter { it.first != it.second }.associate { cacheKey(lang, it.first) to it.second }
            if (entries.isNotEmpty()) scope.launch(kotlinx.coroutines.Dispatchers.IO) { disk.putAll(entries) }
        }
    }

    /** Memory first, then the persistent cache (promoted into memory). */
    private fun cached(key: String): String? =
        cache[key] ?: diskCache?.get(key)?.also { cache[key] = it }

    private data class PendingItem(
        val text: String,
        val lang: String,
        val deferred: CompletableDeferred<Pair<String, Boolean>> = CompletableDeferred(),
    )

    /** A fresh queue/worker per lifecycle, so cancellation never strands a newer request. */
    private class TranslationSession {
        val queue = Channel<PendingItem>(Channel.UNLIMITED)
        var worker: Job? = null
    }

    private var session = TranslationSession()
    // Accessed only under lifecycleLock. Both live cues and lookahead atomically join these claims.
    private val inFlight = HashMap<String, PendingItem>()
    private var hideTranslatingJob: Job? = null

    fun updateService(
        apiKey: String,
        model: SubtitleAiModel,
        customConfigProvider: () -> com.arflix.tv.ui.screens.player.subtitles.ai.CustomAiConfig? = { null },
    ) {
        service = SubtitleTranslationService(
            apiKeyProvider = { apiKey },
            modelProvider = { model },
            customConfigProvider = customConfigProvider,
        )
    }

    private fun isCurrent(owner: TranslationSession): Boolean = session === owner && translationNeeded

    /** Must hold lifecycleLock. The deferred belongs to the manager, not any individual waiter. */
    private fun claimLocked(owner: TranslationSession, text: String): Deferred<Pair<String, Boolean>> {
        if (!isCurrent(owner)) return CompletableDeferred(text to false)
        val lang = targetLanguage
        val key = cacheKey(lang, text)
        cached(key)?.let { return CompletableDeferred(it to true) }
        inFlight[key]?.let { return it.deferred }

        val item = PendingItem(text, lang)
        inFlight[key] = item
        owner.queue.trySend(item)
        hideTranslatingJob?.cancel()
        if (!isTranslating) {
            isTranslating = true
            onTranslatingChanged?.invoke(true)
        }
        if (isCurrent(owner) && owner.worker == null) {
            val worker = scope.launch(start = CoroutineStart.LAZY) { processBatches(owner) }
            owner.worker = worker
            // Also runs if the parent scope was already cancelled before the worker could start.
            worker.invokeOnCompletion {
                synchronized(lifecycleLock) {
                    if (session === owner) invalidateWorkLocked()
                }
            }
            worker.start()
        }
        return item.deferred
    }

    private suspend fun processBatches(owner: TranslationSession) {
        while (true) {
            val batch = mutableListOf(owner.queue.receive())
            // A single deadline works with both real and virtual coroutine clocks. Lookahead and
            // live cues share this worker, so neither can send a duplicate/concurrent request.
            withTimeoutOrNull(BATCH_WINDOW_MS) {
                while (batch.size < CHUNK_LINES) batch += owner.queue.receive()
            }
            currentCoroutineContext().ensureActive()
            val activeService = synchronized(lifecycleLock) {
                if (!isCurrent(owner)) return
                service
            }
            val lang = batch.first().lang
            val texts = batch.map { it.text }
            val result = try {
                activeService.translateBatch(texts, lang, contextFor(texts, lang), contentTitle)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                TranslationResult(emptyList(), false, e.message ?: "Translation error")
            }
            currentCoroutineContext().ensureActive()
            synchronized(lifecycleLock) {
                // A cancelled HTTP implementation may still return. Never publish its cache,
                // callback or completion into a newer mode/lifecycle.
                if (!isCurrent(owner)) return
                val pairs = batch.mapIndexed { i, item ->
                    item.text to if (result.success) result.lines.getOrElse(i) { item.text } else item.text
                }
                if (result.success) {
                    pairs.forEach { (text, translated) -> cache[cacheKey(lang, text)] = translated }
                    remember(lang, pairs)
                }
                batch.forEachIndexed { i, item ->
                    // Identity-aware removal is important if a reset re-queues the same text.
                    inFlight.remove(cacheKey(item.lang, item.text), item)
                    item.deferred.complete(pairs[i].second to result.success)
                }
                if (isCurrent(owner)) {
                    scheduleIdleLocked(owner)
                    onBatchResult?.invoke(result.success, result.errorMessage)
                }
            }
            if (!result.success) delay(5_000L)
        }
    }

    private fun scheduleIdleLocked(owner: TranslationSession) {
        if (inFlight.isNotEmpty()) return
        hideTranslatingJob?.cancel()
        hideTranslatingJob = scope.launch {
            delay(1500)
            synchronized(lifecycleLock) {
                if (isCurrent(owner) && inFlight.isEmpty() && isTranslating) {
                    isTranslating = false
                    onTranslatingChanged?.invoke(false)
                }
            }
        }
    }

    /**
     * Immediately releases all waiters and drains old queued work. Worker cancellation is
     * cooperative: SubtitleTranslationService's blocking OkHttp execute() is NOT Call.cancel().
     * The session guard also rejects non-cooperative/late responses; already-paid HTTP may finish.
     */
    private fun invalidateWorkLocked() {
        val old = session
        session = TranslationSession()
        val abandoned = inFlight.values.toList()
        inFlight.clear()
        old.queue.cancel() // Unlike close(), cancel also discards every buffered item.
        hideTranslatingJob?.cancel()
        hideTranslatingJob = null
        synchronized(recentLock) { recent.clear() }
        old.worker?.cancel()
        val wasTranslating = isTranslating
        isTranslating = false
        if (wasTranslating) onTranslatingChanged?.invoke(false)
        abandoned.forEach { it.deferred.complete(it.text to false) }
    }

    /** Cached translation of [text] into the CURRENT target language. */
    fun getCached(text: String): String? = cached(cacheKey(targetLanguage, text))

    fun isInFlight(text: String): Boolean = synchronized(lifecycleLock) {
        inFlight.containsKey(cacheKey(targetLanguage, text))
    }

    suspend fun translate(text: String): String = translateDetailed(text).first

    /**
     * Clears what is on screen and what is queued. Translations stay cached: they are keyed by
     * target language and source text, so they are valid for any track, episode or re-watch, and
     * clearing them only paid for the same lines again.
     */
    fun reset() = synchronized(lifecycleLock) {
        bilingualBus.clear()
        invalidateWorkLocked()
    }

    suspend fun preTranslateWindow(texts: List<String>) {
        val (owner, uncached) = synchronized(lifecycleLock) {
            if (!translationNeeded) return
            val lang = targetLanguage
            session to texts.distinct().filter { cached(cacheKey(lang, it)) == null }
        }
        uncached.chunked(CHUNK_LINES).forEach { chunk ->
            val claims = synchronized(lifecycleLock) {
                // Check the original lifecycle before EVERY chunk, including after off/on/reset.
                if (!isCurrent(owner)) return
                chunk.map { claimLocked(owner, it) }
            }
            if (claims.map { it.await() }.any { !it.second }) return
        }
    }
}
