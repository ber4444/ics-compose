package com.livingpresence.inner.circle.squared

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.livingpresence.mediakit.EventInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val DEFAULT_VIDEO_LOAD_ERROR = "Unable to load videos"

data class MainUiState(
    val isGalleryVisible: Boolean = true,
    val availableEvents: List<EventInfo> = emptyList(),
    val isLoadingVideos: Boolean = false,
    val videoLoadError: String? = null,
)

class MainViewModel(
    private val videoRepository: VideoRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    init {
        ensureVideosLoaded()
    }

    fun retryLoadingVideos() {
        loadVideos(forceRefresh = true)
    }

    /**
     * Ensures the available-events list has been fetched at least once. Idempotent —
     * a no-op when a non-empty list is already cached or a load is in flight.
     */
    fun ensureVideosLoaded() {
        loadVideos()
    }

    fun playVideo(eventNumber: Int) {
        _uiState.update { it.copy(isGalleryVisible = false) }
    }

    private fun loadVideos(forceRefresh: Boolean = false) {
        val currentState = _uiState.value
        if (currentState.isLoadingVideos) {
            return
        }
        if (!forceRefresh && currentState.availableEvents.isNotEmpty()) {
            return
        }

        _uiState.update {
            it.copy(
                isLoadingVideos = true,
                videoLoadError = null,
            )
        }

        viewModelScope.launch {
            runCatching { videoRepository.loadEvents() }
                .onSuccess { events ->
                    _uiState.update {
                        it.copy(
                            availableEvents = events,
                            isLoadingVideos = false,
                            videoLoadError = null,
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isLoadingVideos = false,
                            videoLoadError = error.message ?: DEFAULT_VIDEO_LOAD_ERROR,
                        )
                    }
                }
        }
    }
}
