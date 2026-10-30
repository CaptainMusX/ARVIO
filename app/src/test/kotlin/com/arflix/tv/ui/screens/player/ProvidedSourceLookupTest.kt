package com.arflix.tv.ui.screens.player

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import com.arflix.tv.data.model.MediaType
import com.arflix.tv.data.model.StreamSource
import com.arflix.tv.data.repository.StreamRepository
import com.arflix.tv.data.repository.StreamResult
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.lang.reflect.InvocationTargetException
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = android.app.Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class ProvidedSourceLookupTest {
    private val sources = mockk<StreamRepository>(relaxed = true)
    private val store = ViewModelStore()
    private lateinit var model: PlayerViewModel
    private val fakeJobs = mutableListOf<Job>()
    private val placeholder = stream("placeholder", "https://addon.example/static/downloading.mp4")

    @Before fun setup() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        coEvery { sources.resolveMovieStreams(any(), any(), any(), any(), any()) } returns StreamResult(emptyList(), emptyList())
        coEvery { sources.resolveStreamForPlayback(any()) } coAnswers { firstArg() }
        model = PlayerViewModel(
            context = mockk(relaxed = true), profileManager = mockk(relaxed = true),
            mediaRepository = mockk(relaxed = true), streamRepository = sources,
            traktRepository = mockk(relaxed = true), remoteSyncManager = mockk(relaxed = true),
            watchHistoryRepository = mockk(relaxed = true), cloudSyncRepository = mockk(relaxed = true),
            launcherContinueWatchingRepository = mockk(relaxed = true), animeMapper = mockk(relaxed = true),
            tmdbApi = mockk(relaxed = true), skipIntroRepository = mockk(relaxed = true),
            playbackTelemetryRepository = mockk(relaxed = true), pluginManager = mockk(relaxed = true),
            streamIntegrationRepository = mockk(relaxed = true), recentPlayedSourceRepository = mockk(relaxed = true),
            kidsContentFilter = mockk(relaxed = true)
        )
        store.put("player", model)
        field("currentMediaId").setInt(model, 42)
        state().value = state().value.copy(streams = listOf(placeholder), selectedStream = placeholder,
            selectedStreamUrl = placeholder.url, sourceSearchActive = true, savedPosition = 2_100_000L)
    }

    @After fun cleanup() {
        fakeJobs.forEach { it.cancel() }
        val job = model.viewModelScope.coroutineContext[Job]
        try {
            store.clear()
            runBlocking { withTimeout(15_000) { job?.join() } }
        } finally { Dispatchers.resetMain() }
    }

    @Test fun addonCompletionWaitsForHomeServerInsteadOfStoppingPlaceholderRetry() = runBlocking {
        val home = pendingJob("homeServerAppendJob")
        populate()
        assertTrue(field("primaryStreamResolutionFinal").getBoolean(model))
        assertTrue(model.uiState.value.sourceSearchActive)
        home.complete()
        finishSupplemental(home)
        assertFalse(model.uiState.value.sourceSearchActive)
    }

    @Test fun emptyAddonResultsWaitForIptvToo() = runBlocking {
        val iptv = pendingJob("vodAppendJob")
        populate()
        assertTrue(model.uiState.value.sourceSearchActive)
        iptv.complete()
        finishSupplemental(iptv)
        assertFalse(model.uiState.value.sourceSearchActive)
    }

    @Test fun lookupFinishesNormallyWithoutSupplementalProviders() = runBlocking {
        populate()
        assertFalse(model.uiState.value.sourceSearchActive)
        assertEquals(placeholder.url, model.uiState.value.selectedStreamUrl)
    }

    @Test fun addonFailureStillAllowsLateSupplementalSourcesAndCanFinish() = runBlocking {
        val home = pendingJob("homeServerAppendJob")
        coEvery { sources.resolveMovieStreams(any(), any(), any(), any(), any()) } throws java.io.IOException("offline")
        populate()
        assertTrue(model.uiState.value.sourceSearchActive)
        assertTrue(field("primaryStreamResolutionFinal").getBoolean(model))
        home.complete()
        finishSupplemental(home)
        assertFalse(model.uiState.value.sourceSearchActive)
    }

    @Test fun cancelledOldLookupDoesNotFinishTheNewMediaSearch() = runBlocking {
        val pending = CompletableDeferred<StreamResult>()
        coEvery { sources.resolveMovieStreams(any(), any(), any(), any(), any()) } coAnswers { pending.await() }
        val job = launch(start = CoroutineStart.UNDISPATCHED) { populate() }
        job.cancelAndJoin()
        assertFalse(field("primaryStreamResolutionFinal").getBoolean(model))
        assertTrue(model.uiState.value.sourceSearchActive)
    }

    @Test fun lateHomeAndIptvSourcesAppendInOrderWithoutLosingTheResumePosition() = runBlocking {
        val homeSource = stream("home_server", "https://home.example/movie.mkv")
        val vodSource = stream("iptv_vod", "https://iptv.example/movie.mp4")
        coEvery { sources.resolveMovieHomeServerSources(any(), any(), any(), any(), any()) } returns listOf(homeSource)
        coEvery { sources.resolveMovieVodSources(any(), any(), any(), any(), any(), any()) } returns listOf(vodSource)
        val homeReady = CompletableDeferred<Unit>()
        val vodReady = CompletableDeferred<Unit>()
        val home = launch(start = CoroutineStart.LAZY) {
            homeReady.await()
            invokeSuspend("appendHomeServerSourcesInBackground", MediaType.MOVIE, "tt42", null, null, 1_000L)
        }
        val vod = launch(start = CoroutineStart.LAZY) {
            vodReady.await()
            invokeSuspend("appendVodSourceInBackground", MediaType.MOVIE, "tt42", null, null, 1_000L)
        }
        field("homeServerAppendJob").set(model, home)
        field("vodAppendJob").set(model, vod)
        home.start(); vod.start()
        populate()
        assertTrue(model.uiState.value.sourceSearchActive)
        homeReady.complete(Unit)
        withTimeout(5_000) { home.join() }
        assertTrue(model.uiState.value.streams.contains(homeSource))
        assertTrue(model.uiState.value.sourceSearchActive)
        vodReady.complete(Unit)
        withTimeout(5_000) { vod.join() }
        assertFalse(model.uiState.value.sourceSearchActive)
        assertTrue(model.uiState.value.streams.containsAll(listOf(homeSource, vodSource)))
        assertTrue(canTryFailoverSource(homeSource, placeholder, placeholder.url,
            setOf(playbackRequestIdentity(placeholder)!!)))
        model.selectStream(homeSource)
        assertEquals(homeSource.url, model.uiState.value.selectedStreamUrl)
        assertEquals(2_100_000L, model.uiState.value.savedPosition)
    }

    private fun stream(id: String, url: String) = StreamSource(id, id, id, "1080p", "2 GB", url = url)
    private fun field(name: String) = PlayerViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }
    @Suppress("UNCHECKED_CAST")
    private fun state() = field("_uiState").get(model) as MutableStateFlow<PlayerUiState>
    private fun pendingJob(fieldName: String): CompletableJob = Job().also {
        fakeJobs += it
        field(fieldName).set(model, it)
    }
    private fun finishSupplemental(job: Job) {
        PlayerViewModel::class.java.declaredMethods.single { it.name == "finishSupplementalSourceLookupIfReady" }
            .apply { isAccessible = true }.invoke(model, job, PlayerMessage.Raw("No sources"))
    }
    private suspend fun populate() = invokeSuspend("populateStreamsForProvidedUrl", MediaType.MOVIE,
        42, null, null, "tt42", placeholder.url)
    private suspend fun invokeSuspend(name: String, vararg arguments: Any?): Unit = suspendCoroutine { continuation ->
        try {
            val method = PlayerViewModel::class.java.declaredMethods.single { it.name == name }.apply { isAccessible = true }
            val result = method.invoke(model, *(arguments.toList() + continuation).toTypedArray())
            if (result !== COROUTINE_SUSPENDED) continuation.resume(Unit)
        } catch (error: InvocationTargetException) {
            continuation.resumeWithException(error.targetException)
        } catch (error: Exception) {
            continuation.resumeWithException(error)
        }
    }
}
