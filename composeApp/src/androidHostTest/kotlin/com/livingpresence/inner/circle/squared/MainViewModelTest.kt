package com.livingpresence.inner.circle.squared

import com.livingpresence.mediakit.EventInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests [MainViewModel] state transitions: gallery visibility,
 * video loading + error paths.
 */
class MainViewModelTest {

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun init_loadsEventsAndPopulatesState() = runTest {
        val events = listOf(
            EventInfo(eventNumber = 3, isLive = false, durationMs = 10_000),
            EventInfo(eventNumber = 1, isLive = true, durationMs = 0),
        )
        val vm = MainViewModel(videoRepositoryWith(events))

        assertEquals(events, vm.uiState.value.availableEvents)
        assertFalse(vm.uiState.value.isLoadingVideos)
        assertNull(vm.uiState.value.videoLoadError)
        assertTrue(vm.uiState.value.isGalleryVisible)
    }
    
    @Test
    fun playVideo_hidesGallery() = runTest {
        val vm = MainViewModel(videoRepositoryWith(emptyList()))
        assertTrue(vm.uiState.value.isGalleryVisible)

        vm.playVideo(1)
        assertFalse(vm.uiState.value.isGalleryVisible)
    }

    @Test
    fun ensureVideosLoaded_isIdempotentWhenAlreadyPopulated() = runTest {
        val events = listOf(EventInfo(eventNumber = 1, isLive = false, durationMs = 1_000))
        var loadCount = 0
        val repo = object : VideoRepository(FakeHttpClient) {
            override suspend fun loadEvents(): List<EventInfo> {
                loadCount++
                return events
            }
        }
        val vm = MainViewModel(repo) // triggers init -> ensureVideosLoaded

        vm.ensureVideosLoaded()
        vm.ensureVideosLoaded()
        vm.ensureVideosLoaded()

        // Idempotent: a non-empty cached list means subsequent calls are no-ops.
        assertEquals(1, loadCount)
    }

    @Test
    fun retryLoadingVideos_forcesRefresh() = runTest {
        val events = listOf(EventInfo(eventNumber = 1, isLive = false, durationMs = 1_000))
        var loadCount = 0
        val repo = object : VideoRepository(FakeHttpClient) {
            override suspend fun loadEvents(): List<EventInfo> {
                loadCount++
                return events
            }
        }
        val vm = MainViewModel(repo) // load 1

        vm.ensureVideosLoaded() // no-op
        vm.retryLoadingVideos() // load 2

        assertEquals(2, loadCount)
    }

    private fun videoRepositoryWith(events: List<EventInfo>): VideoRepository =
        object : VideoRepository(FakeHttpClient) {
            override suspend fun loadEvents(): List<EventInfo> = events
        }
}

/** A no-op Ktor client; the fake repo overrides loadEvents so it's never used. */
private val FakeHttpClient = io.ktor.client.HttpClient()
