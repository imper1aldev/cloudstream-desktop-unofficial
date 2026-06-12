package com.lagradost.cloudstream3.desktop.ui.screens.player

import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.desktop.ui.VideoLaunchData
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class EmbeddedPlayerViewModel(private val coroutineScope: CoroutineScope) {

    private val _launchData = MutableStateFlow<VideoLaunchData?>(null)
    val launchData: StateFlow<VideoLaunchData?> = _launchData.asStateFlow()

    private val _isLoadingNextEpisode = MutableStateFlow(false)
    val isLoadingNextEpisode: StateFlow<Boolean> = _isLoadingNextEpisode.asStateFlow()

    private val _nextEpisodeLinks = MutableStateFlow<List<ExtractorLink>>(emptyList())
    val nextEpisodeLinks: StateFlow<List<ExtractorLink>> = _nextEpisodeLinks.asStateFlow()

    private val _nextEpisodeSubtitles = MutableStateFlow<List<SubtitleFile>>(emptyList())
    val nextEpisodeSubtitles: StateFlow<List<SubtitleFile>> = _nextEpisodeSubtitles.asStateFlow()

    private val _targetEpisodeData = MutableStateFlow<Episode?>(null)
    val targetEpisodeData: StateFlow<Episode?> = _targetEpisodeData.asStateFlow()

    private var loadLinksJob: Job? = null

    fun init(initialData: VideoLaunchData) {
        if (_launchData.value == null) {
            val isFinished = initialData.history.duration > 0 && initialData.history.position >= initialData.history.duration - 15
            val adjustedData = if (isFinished) {
                initialData.copy(
                    startPositionMs = 0L,
                    history = initialData.history.copy(position = 0L),
                )
            } else {
                initialData
            }
            _launchData.value = adjustedData

            // Auto-scrape initial episode if links are empty
            if (adjustedData.links.isEmpty() && adjustedData.history.episodeId != null) {
                val apiName = adjustedData.loadResponse?.apiName
                val provider = APIHolder.getApiFromNameNull(apiName ?: "")
                if (provider != null) {
                    _isLoadingNextEpisode.value = true
                    _targetEpisodeData.value = provider.newEpisode(adjustedData.history.episodeId!!) {
                        this.name = adjustedData.history.showName
                        this.season = adjustedData.history.season
                        this.episode = adjustedData.history.episode
                    }
                    _nextEpisodeLinks.value = emptyList()
                    _nextEpisodeSubtitles.value = adjustedData.subtitles

                    loadLinksJob = coroutineScope.launch(Dispatchers.IO) {
                        try {
                            provider.loadLinks(
                                data = adjustedData.history.episodeId!!,
                                isCasting = false,
                                subtitleCallback = { sub ->
                                    _nextEpisodeSubtitles.value = _nextEpisodeSubtitles.value + sub
                                },
                                callback = { link ->
                                    _nextEpisodeLinks.value = _nextEpisodeLinks.value + link
                                },
                            )

                            kotlinx.coroutines.withContext(Dispatchers.Main) {
                                if (_nextEpisodeLinks.value.isNotEmpty()) {
                                    val newLaunchData = adjustedData.copy(
                                        links = _nextEpisodeLinks.value.toList(),
                                        subtitles = _nextEpisodeSubtitles.value.toList(),
                                        initialIndex = 0,
                                    )
                                    _launchData.value = newLaunchData
                                }
                                _isLoadingNextEpisode.value = false
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                            _isLoadingNextEpisode.value = false
                        }
                    }
                }
            }
        }
    }

    fun loadEpisode(episode: Episode) {
        val currentData = _launchData.value ?: return

        loadLinksJob?.cancel()
        _isLoadingNextEpisode.value = true
        _nextEpisodeLinks.value = emptyList()
        _nextEpisodeSubtitles.value = emptyList()
        _targetEpisodeData.value = episode

        val apiName = currentData.loadResponse?.apiName
        val provider = APIHolder.getApiFromNameNull(apiName ?: "")

        if (provider != null && episode.data.isNotBlank()) {
            loadLinksJob = coroutineScope.launch(Dispatchers.IO) {
                try {
                    provider.loadLinks(
                        data = episode.data,
                        isCasting = false,
                        subtitleCallback = { sub ->
                            _nextEpisodeSubtitles.value = _nextEpisodeSubtitles.value + sub
                        },
                        callback = { link ->
                            _nextEpisodeLinks.value = _nextEpisodeLinks.value + link
                        },
                    )

                    // Auto-play as soon as links are loaded
                    kotlinx.coroutines.withContext(Dispatchers.Main) {
                        if (_nextEpisodeLinks.value.isNotEmpty()) {
                            playLoadedEpisode()
                        } else {
                            _isLoadingNextEpisode.value = false
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    _isLoadingNextEpisode.value = false
                }
            }
        } else {
            _isLoadingNextEpisode.value = false
        }
    }

    fun cancelLoading() {
        loadLinksJob?.cancel()
        _isLoadingNextEpisode.value = false
    }

    fun playLoadedEpisode() {
        val currentData = _launchData.value ?: return
        val epData = _targetEpisodeData.value ?: return
        if (_nextEpisodeLinks.value.isEmpty()) return

        loadLinksJob?.cancel()

        val newHistory = currentData.history.copy(
            episodeId = epData.data,
            episode = epData.episode,
            season = epData.season,
            position = 0L,
            duration = 0L,
        )

        val newLaunchData = currentData.copy(
            links = _nextEpisodeLinks.value.toList(),
            subtitles = _nextEpisodeSubtitles.value.toList(),
            history = newHistory,
            initialIndex = 0,
            startPositionMs = 0L, // Ensure start position is 0
            title = buildString {
                append(newHistory.showName)
                if (newHistory.season != null && newHistory.episode != null) {
                    append(" - S${newHistory.season}E${newHistory.episode}")
                } else if (newHistory.episode != null) {
                    append(" - E${newHistory.episode}")
                }
            },
        )

        _isLoadingNextEpisode.value = false
        _targetEpisodeData.value = null
        _nextEpisodeLinks.value = emptyList()
        _nextEpisodeSubtitles.value = emptyList()
        _launchData.value = newLaunchData
    }

    fun getEpisodesList(): List<Episode> {
        val currentData = _launchData.value ?: return emptyList()
        return when (val resp = currentData.loadResponse) {
            is com.lagradost.cloudstream3.TvSeriesLoadResponse -> resp.episodes
            is com.lagradost.cloudstream3.AnimeLoadResponse -> {
                val dub = resp.episodes.entries.firstOrNull { entry -> entry.value.any { it.data == currentData.history.episodeId } }?.key
                resp.episodes[dub] ?: emptyList()
            }
            else -> emptyList()
        }
    }

    fun loadNextEpisode() {
        val episodes = getEpisodesList()
        val currentData = _launchData.value ?: return
        val currentIndex = episodes.indexOfFirst { it.data == currentData.history.episodeId }
        val nextEpisode = if (currentIndex != -1 && currentIndex + 1 < episodes.size) episodes[currentIndex + 1] else null

        if (nextEpisode != null) {
            loadEpisode(nextEpisode)
        }
    }

    fun loadPrevEpisode() {
        val episodes = getEpisodesList()
        val currentData = _launchData.value ?: return
        val currentIndex = episodes.indexOfFirst { it.data == currentData.history.episodeId }
        val prevEpisode = if (currentIndex > 0) episodes[currentIndex - 1] else null

        if (prevEpisode != null) {
            loadEpisode(prevEpisode)
        }
    }

    fun hasNextEpisode(): Boolean {
        val episodes = getEpisodesList()
        val currentData = _launchData.value ?: return false
        val currentIndex = episodes.indexOfFirst { it.data == currentData.history.episodeId }
        return currentIndex != -1 && currentIndex + 1 < episodes.size
    }

    fun hasPrevEpisode(): Boolean {
        val episodes = getEpisodesList()
        val currentData = _launchData.value ?: return false
        val currentIndex = episodes.indexOfFirst { it.data == currentData.history.episodeId }
        return currentIndex > 0
    }
}
