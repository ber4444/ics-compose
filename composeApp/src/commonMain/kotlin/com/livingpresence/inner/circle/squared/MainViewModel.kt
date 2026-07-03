package com.livingpresence.inner.circle.squared

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val LIVE_EVENTS_PASSWORD = "SECRET"
private const val DEFAULT_VIDEO_LOAD_ERROR = "Unable to load videos"

data class MainUiState(
    val password: String = "",
    val isVideosDialogVisible: Boolean = false,
    val availableVideos: List<Int> = emptyList(),
    val isLoadingVideos: Boolean = false,
    val videoLoadError: String? = null,
) {
    val isLiveEventsEnabled: Boolean
        get() = password == LIVE_EVENTS_PASSWORD
}

class MainViewModel(
    private val videoRepository: VideoRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    fun onPasswordChanged(password: String) {
        _uiState.update { it.copy(password = password) }
    }

    fun showVideosDialog() {
        _uiState.update {
            it.copy(
                isVideosDialogVisible = true,
                videoLoadError = null,
            )
        }

        if (_uiState.value.availableVideos.isEmpty()) {
            loadVideos()
        }
    }

    fun dismissVideosDialog() {
        _uiState.update { it.copy(isVideosDialogVisible = false) }
    }

    fun retryLoadingVideos() {
        loadVideos(forceRefresh = true)
    }

    /**
     * Ensures the available-events list has been fetched at least once. Idempotent —
     * a no-op when a non-empty list is already cached. Used by the feed/gallery screen
     * (Phase 2) to trigger loading on entry without forcing a refresh.
     */
    fun ensureVideosLoaded() {
        loadVideos()
    }

    fun playVideo(eventNumber: Int) {
        _uiState.update { it.copy(isVideosDialogVisible = false) }
    }

    private fun loadVideos(forceRefresh: Boolean = false) {
        val currentState = _uiState.value
        if (currentState.isLoadingVideos) {
            return
        }
        if (!forceRefresh && currentState.availableVideos.isNotEmpty()) {
            return
        }

        _uiState.update {
            it.copy(
                isLoadingVideos = true,
                videoLoadError = null,
            )
        }

        viewModelScope.launch {
            runCatching { videoRepository.getAvailableVideos() }
                .onSuccess { videoList ->
                    _uiState.update {
                        it.copy(
                            availableVideos = videoList,
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
