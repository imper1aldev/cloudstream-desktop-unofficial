package com.lagradost.cloudstream3.desktop.player.vlcj2

import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.player.impl.PlayerLinkHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class Vlcj2Controller(private val engine: Vlcj2Engine) {
    init {
        engine.onError = { errorMsg ->
            val nextIndex = _currentLinkIndex.value + 1
            if (nextIndex < _links.value.size) {
                val savedPos = engine.positionMs
                _currentLinkIndex.value = nextIndex
                scope?.launch {
                    _toastMessage.emit("Stream failed. Retrying source ${nextIndex + 1}/${_links.value.size}...")
                }
                play(nextIndex, forceStartMs = savedPos)
            } else {
                scope?.launch {
                    _toastMessage.emit("All sources failed.")
                }
            }
        }
    }

    private val _state = PlayerStateFlow(engine)
    val state: StateFlow<PlayerUiState> = _state.state

    private val _links = MutableStateFlow<List<ExtractorLink>>(emptyList())
    val links: StateFlow<List<ExtractorLink>> = _links.asStateFlow()

    private val _currentLinkIndex = MutableStateFlow(0)
    val currentLinkIndex: StateFlow<Int> = _currentLinkIndex.asStateFlow()

    private val _subtitleTracks = MutableStateFlow<List<SubtitleTrackInfo>>(emptyList())
    val subtitleTracks: StateFlow<List<SubtitleTrackInfo>> = _subtitleTracks.asStateFlow()

    private val _toastMessage = MutableSharedFlow<String>()
    val toastMessage: SharedFlow<String> = _toastMessage.asSharedFlow()

    private var scope: CoroutineScope? = null
    private var subtitlePollingJob: Job? = null

    fun initialize(
        links: List<ExtractorLink>,
        initialIndex: Int,
        title: String?,
        startMs: Long,
        scope: CoroutineScope,
    ) {
        this.scope = scope
        _links.value = links
        _currentLinkIndex.value = initialIndex
        _state.launch(scope)
        startSubtitlePolling(scope)
        play(initialIndex, forceStartMs = startMs)
    }

    fun play(index: Int, forceStartMs: Long? = null) {
        val link = _links.value.getOrNull(index) ?: return
        _currentLinkIndex.value = index
        val validated = PlayerLinkHandler.validate(link, null).getOrElse { return }
        val headers = validated.headers.map { "${it.key}: ${it.value}" }
        val isHls = validated.streamKind == PlayerLinkHandler.StreamKind.HLS
        engine.play(
            validated.url,
            validated.displayTitle,
            headers,
            forceStartMs ?: if (index == _currentLinkIndex.value) 0L else 0L,
            isHls = isHls,
        )
    }

    fun switchServer(newIndex: Int) {
        if (newIndex == _currentLinkIndex.value) return
        val savedPosition = engine.positionMs
        engine.stopForSwitch()
        _currentLinkIndex.value = newIndex
        play(newIndex, forceStartMs = savedPosition)
    }

    fun togglePause() = engine.togglePause()
    fun seek(positionMs: Long) {
        if (engine.isHlsStream) {
            engine.seekByReopen(positionMs)
        } else {
            engine.seek(positionMs)
        }
    }
    fun setVolume(vol: Int) = engine.setVolume(vol)
    fun skipIntro() = seek(engine.positionMs + 88_000L)

    fun setSubtitleTrack(trackId: Int) {
        engine.setSubtitleTrack(trackId)
    }

    fun setSubtitleFile(path: String) {
        engine.setSubtitleFile(path)
    }

    fun setHwMode(mode: String) {
        scope?.launch(Dispatchers.IO) {
            engine.reinitialize(mode)
        }
    }

    fun retryNext() {
        val nextIndex = _currentLinkIndex.value + 1
        if (nextIndex < _links.value.size) {
            scope?.launch {
                _toastMessage.emit("Retrying source ${nextIndex + 1}/${_links.value.size}...")
            }
            play(nextIndex, forceStartMs = engine.positionMs)
        }
    }

    fun release() {
        subtitlePollingJob?.cancel()
        engine.release()
    }

    private fun startSubtitlePolling(scope: CoroutineScope) {
        subtitlePollingJob = scope.launch(Dispatchers.Main) {
            while (true) {
                delay(2000)
                _subtitleTracks.value = engine.subtitleTracks()
            }
        }
    }
}
