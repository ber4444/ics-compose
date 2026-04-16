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

data class PlaybackRequest(
    val url: String,
    val audioOnly: Boolean,
)

data class MainUiState(
    val password: String = "",
    val audioOnly: Boolean = false,
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

    fun onAudioOnlyChanged(audioOnly: Boolean) {
        _uiState.update { it.copy(audioOnly = audioOnly) }
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

    fun createPlayback(eventNumber: Int): PlaybackRequest {
        val currentState = _uiState.value
        val playbackRequest = PlaybackRequest(
            url = getUrl(eventNumber, currentState.audioOnly),
            audioOnly = currentState.audioOnly,
        )

        _uiState.update {
            it.copy(isVideosDialogVisible = false)
        }

        return playbackRequest
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
