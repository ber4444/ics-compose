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
 * Tests [MainViewModel] state transitions (plan.md Phase 6): login gating,
 * gallery visibility, video loading + error paths.
 */
class MainViewModelTest {

    private val correctPassword = "SECRET"
    private val wrongPassword = "nope"

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun isLiveEventsEnabled_isFalseUntilCorrectPasswordEntered() = runTest {
        val vm = MainViewModel(VideoRepository(FakeHttpClient), correctPassword)
        assertFalse(vm.uiState.value.isLiveEventsEnabled)

        vm.onPasswordChanged(wrongPassword)
        assertFalse(vm.uiState.value.isLiveEventsEnabled)

        vm.onPasswordChanged(correctPassword)
        assertTrue(vm.uiState.value.isLiveEventsEnabled)
    }

    @Test
    fun emptyPassword_doesNotEnable() = runTest {
        val vm = MainViewModel(VideoRepository(FakeHttpClient), correctPassword)
        vm.onPasswordChanged("")
        assertFalse(vm.uiState.value.isLiveEventsEnabled)
    }

    @Test
    fun showGallery_setsGalleryVisible() = runTest {
        val vm = MainViewModel(videoRepositoryWith(emptyList()), correctPassword)
        assertFalse(vm.uiState.value.isGalleryVisible)

        vm.showGallery()
        assertTrue(vm.uiState.value.isGalleryVisible)
    }

    @Test
    fun showGallery_loadsEventsAndPopulatesState() = runTest {
        val events = listOf(
            EventInfo(eventNumber = 3, isLive = false, durationMs = 10_000),
            EventInfo(eventNumber = 1, isLive = true, durationMs = 0),
        )
        val vm = MainViewModel(videoRepositoryWith(events), correctPassword)

        vm.showGallery()

        assertEquals(events, vm.uiState.value.availableEvents)
        assertFalse(vm.uiState.value.isLoadingVideos)
        assertNull(vm.uiState.value.videoLoadError)
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
        val vm = MainViewModel(repo, correctPassword)

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
        val vm = MainViewModel(repo, correctPassword)

        vm.ensureVideosLoaded()
        vm.retryLoadingVideos()

        assertEquals(2, loadCount)
    }

    private fun videoRepositoryWith(events: List<EventInfo>): VideoRepository =
        object : VideoRepository(FakeHttpClient) {
            override suspend fun loadEvents(): List<EventInfo> = events
        }
}

/** A no-op Ktor client; the fake repo overrides loadEvents so it's never used. */
private val FakeHttpClient = io.ktor.client.HttpClient()
