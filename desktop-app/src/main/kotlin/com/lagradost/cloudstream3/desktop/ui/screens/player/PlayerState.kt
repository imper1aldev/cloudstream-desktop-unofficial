package com.lagradost.cloudstream3.desktop.ui.screens.player

import com.lagradost.cloudstream3.desktop.player.MpvLibrary
import kotlinx.coroutines.flow.MutableStateFlow

class PlayerState {
    val positionMs = MutableStateFlow(0L)
    val durationMs = MutableStateFlow(0L)
    val bufferMs = MutableStateFlow(0L)
    val isPaused = MutableStateFlow(false)
    val isBuffering = MutableStateFlow(true)
    val volume = MutableStateFlow(100f) // 0 to 130 in MPV usually, let's say 0 to 100
    val playbackSpeed = MutableStateFlow(1.0f)
    val showControls = MutableStateFlow(true)
    val subtitleDelayMs = MutableStateFlow(0L)
    val isInterpolationEnabled = MutableStateFlow(
        com.lagradost.common.storage.DesktopDataStore.getKey<Boolean>(com.lagradost.cloudstream3.desktop.player.PlayerConfig.PREF_INTERPOLATION) ?: false,
    )

    data class VideoTrack(
        val id: Int,
        val name: String,
        val isSelected: Boolean,
    )

    val subtitleTracks = MutableStateFlow<List<VideoTrack>>(emptyList())
    val audioTracks = MutableStateFlow<List<VideoTrack>>(emptyList())

    // Video Stats
    val videoCodec = MutableStateFlow("")
    val audioCodec = MutableStateFlow("")
    val hwdecCurrent = MutableStateFlow("")
    val droppedFrames = MutableStateFlow(0L)
    val fps = MutableStateFlow(0.0)
    val resolution = MutableStateFlow("")
    val videoBitrate = MutableStateFlow(0L)
    val audioBitrate = MutableStateFlow(0L)
    val showStats = MutableStateFlow(false)

    private var mpvHandle: com.sun.jna.Pointer? = null

    fun attachMpv(handle: com.sun.jna.Pointer) {
        mpvHandle = handle
    }

    fun detachMpv() {
        mpvHandle = null
    }

    fun togglePlayPause() {
        mpvHandle?.let {
            val currentlyPaused = isPaused.value
            val nextState = if (currentlyPaused) "no" else "yes"
            MpvLibrary.INSTANCE.mpv_set_property_string(it, "pause", nextState)
            // State will be updated by the observer loop in ComposeMpvPlayer
            isPaused.value = !currentlyPaused
        }
    }

    fun pause() {
        mpvHandle?.let {
            MpvLibrary.INSTANCE.mpv_set_property_string(it, "pause", "yes")
            isPaused.value = true
        }
    }

    fun play() {
        mpvHandle?.let {
            MpvLibrary.INSTANCE.mpv_set_property_string(it, "pause", "no")
            isPaused.value = false
        }
    }

    fun seekTo(positionMs: Long) {
        mpvHandle?.let {
            val posSec = positionMs / 1000.0
            MpvLibrary.INSTANCE.mpv_set_property_string(it, "time-pos", posSec.toString())
            this.positionMs.value = positionMs
        }
    }

    fun seekBy(offsetMs: Long) {
        mpvHandle?.let {
            val offsetSec = offsetMs / 1000.0
            MpvLibrary.INSTANCE.mpv_command_string(it, "seek $offsetSec relative")
        }
    }

    fun setSpeed(speed: Float) {
        mpvHandle?.let {
            MpvLibrary.INSTANCE.mpv_set_property_string(it, "speed", speed.toString())
            playbackSpeed.value = speed
        }
    }

    fun setVolume(vol: Float) {
        mpvHandle?.let {
            val safeVol = vol.coerceIn(0f, 130f)
            MpvLibrary.INSTANCE.mpv_set_property_string(it, "volume", safeVol.toString())
            volume.value = safeVol
        }
    }

    fun setInterpolation(enabled: Boolean) {
        com.lagradost.common.storage.DesktopDataStore.setKey(com.lagradost.cloudstream3.desktop.player.PlayerConfig.PREF_INTERPOLATION, enabled)
        isInterpolationEnabled.value = enabled
        mpvHandle?.let {
            if (enabled) {
                MpvLibrary.INSTANCE.mpv_set_property_string(it, "video-sync", "display-resample")
                MpvLibrary.INSTANCE.mpv_set_property_string(it, "interpolation", "yes")
                MpvLibrary.INSTANCE.mpv_set_property_string(it, "tscale", "oversample")
            } else {
                MpvLibrary.INSTANCE.mpv_set_property_string(it, "video-sync", "audio")
                MpvLibrary.INSTANCE.mpv_set_property_string(it, "interpolation", "no")
            }
        }
    }

    fun setSubtitleDelay(delayMs: Long) {
        mpvHandle?.let {
            val delaySec = delayMs / 1000.0
            MpvLibrary.INSTANCE.mpv_set_property_string(it, "sub-delay", delaySec.toString())
            subtitleDelayMs.value = delayMs
        }
    }

    fun setSubtitleTrack(id: Int?) {
        mpvHandle?.let {
            if (id == null) {
                MpvLibrary.INSTANCE.mpv_set_property_string(it, "sid", "no")
            } else {
                MpvLibrary.INSTANCE.mpv_set_property_string(it, "sid", id.toString())
            }
        }
    }

    fun setAudioTrack(id: Int?) {
        mpvHandle?.let {
            if (id == null) {
                MpvLibrary.INSTANCE.mpv_set_property_string(it, "aid", "no")
            } else {
                MpvLibrary.INSTANCE.mpv_set_property_string(it, "aid", id.toString())
            }
        }
    }

    data class LazyTrack(
        val url: String,
        val name: String,
        val language: String,
    )

    fun loadLazyAudioTrack(track: LazyTrack) {
        mpvHandle?.let {
            val safeUrl = track.url.replace("\\", "\\\\").replace("\"", "\\\"")
            val safeName = track.name.replace("\\", "\\\\").replace("\"", "\\\"")
            val safeLang = track.language.replace("\\", "\\\\").replace("\"", "\\\"")
            // MPV command: audio-add <url> select <title> <lang>
            val cmd = "audio-add \"$safeUrl\" select \"$safeName\" \"$safeLang\""
            MpvLibrary.INSTANCE.mpv_command_string(it, cmd)
        }
    }

    fun loadLazySubtitleTrack(track: LazyTrack) {
        mpvHandle?.let {
            val safeUrl = track.url.replace("\\", "\\\\").replace("\"", "\\\"")
            val safeName = track.name.replace("\\", "\\\\").replace("\"", "\\\"")
            val safeLang = track.language.replace("\\", "\\\\").replace("\"", "\\\"")
            // MPV command: sub-add <url> select <title> <lang>
            val cmd = "sub-add \"$safeUrl\" select \"$safeName\" \"$safeLang\""
            MpvLibrary.INSTANCE.mpv_command_string(it, cmd)
        }
    }

    fun loadExternalSubtitle(url: String) {
        mpvHandle?.let {
            val safeUrl = url.replace("\\", "\\\\").replace("\"", "\\\"")
            val cmd = "sub-add \"$safeUrl\""
            MpvLibrary.INSTANCE.mpv_command_string(it, cmd)
        }
    }

    val aspectRatioMode = MutableStateFlow(0) // 0=Fit, 1=Fill, 2=Crop

    fun cycleAspectRatio() {
        mpvHandle?.let {
            val nextMode = (aspectRatioMode.value + 1) % 3
            aspectRatioMode.value = nextMode
            when (nextMode) {
                0 -> { // Fit
                    MpvLibrary.INSTANCE.mpv_set_property_string(it, "video-aspect-override", "no")
                    MpvLibrary.INSTANCE.mpv_set_property_string(it, "panscan", "0.0")
                }
                1 -> { // Fill/Stretch
                    MpvLibrary.INSTANCE.mpv_set_property_string(it, "video-aspect-override", "window")
                    MpvLibrary.INSTANCE.mpv_set_property_string(it, "panscan", "0.0")
                }
                2 -> { // Crop
                    MpvLibrary.INSTANCE.mpv_set_property_string(it, "video-aspect-override", "no")
                    MpvLibrary.INSTANCE.mpv_set_property_string(it, "panscan", "1.0")
                }
            }
        }
    }
}
