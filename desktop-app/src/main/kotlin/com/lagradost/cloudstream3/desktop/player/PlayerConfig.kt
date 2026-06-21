package com.lagradost.cloudstream3.desktop.player

import com.lagradost.common.storage.DesktopDataStore
import com.sun.jna.Pointer

object PlayerConfig {
    const val PREF_HWDEC = "player_hwdec"
    const val PREF_SUB_SIZE = "player_sub_size"
    const val PREF_SUB_COLOR = "player_sub_color"
    const val PREF_SUB_BG = "player_sub_bg"
    const val PREF_YTDL_FORMAT = "player_ytdl_format"
    const val PREF_AUTO_PLAY = "player_auto_play"
    const val PREF_AUTO_PLAY_TIMEOUT = "player_auto_play_timeout"
    const val PREF_INTERPOLATION = "player_interpolation_enabled"

    fun applyMpvSettings(handle: Pointer, lib: MpvLibrary) {
        // --- VO / GPU path ---
        // gpu-next is the modern rendering API recommended by upstream MPV for Windows,
        // offering vastly superior D3D11 swapchain synchronization inside embedded child windows.
        lib.mpv_set_option_string(handle, "vo", "gpu-next")
        lib.mpv_set_option_string(handle, "gpu-api", "d3d11")
        lib.mpv_set_option_string(handle, "d3d11-sync-interval", "1")
        lib.mpv_set_option_string(handle, "d3d11-flip", "yes")
        lib.mpv_set_option_string(handle, "d3d11-warp", "false")

        // Hardware Acceleration (Default: auto)
        val hwdec = DesktopDataStore.getKey<String>(PREF_HWDEC) ?: "auto"
        lib.mpv_set_option_string(handle, "hwdec", hwdec)
        lib.mpv_set_option_string(handle, "hwdec-codecs", "h264,hevc,vp9,av1,mpeg4")

        // Subtitles Size (Default: 45)
        val subSize = DesktopDataStore.getKey<String>(PREF_SUB_SIZE) ?: "45"
        lib.mpv_set_option_string(handle, "sub-font-size", subSize)

        // Subtitle Color (Default: #FFFFFF)
        val subColor = DesktopDataStore.getKey<String>(PREF_SUB_COLOR) ?: "#FFFFFF"
        lib.mpv_set_option_string(handle, "sub-color", subColor)

        // Subtitle Background (Default: None/Transparent -> #00000000)
        val subBg = DesktopDataStore.getKey<String>(PREF_SUB_BG) ?: "#00000000"
        lib.mpv_set_option_string(handle, "sub-bg-color", subBg)

        // YTDL Format / Quality Selection
        val ytdlFormat = DesktopDataStore.getKey<String>(PREF_YTDL_FORMAT) ?: "bestvideo[height<=?1080]+bestaudio/best"
        lib.mpv_set_option_string(handle, "ytdl-format", ytdlFormat)

        // Verbose Logging for Dev Console
        lib.mpv_set_option_string(handle, "msg-level", "all=warn")
        lib.mpv_set_option_string(handle, "terminal", "no")

        // Fast Startup Optimizations
        // NOTE: demuxer-max-bytes / demuxer-max-back-bytes are NOT set here.
        // Each stream kind (HLS/DASH/PROGRESSIVE) sets its own optimal values
        // in ComposeMpvPlayer after init, via mpv_set_property_string.
        lib.mpv_set_option_string(handle, "cache", "yes")
        lib.mpv_set_option_string(handle, "cache-pause", "yes") // Allow MPV to pause to buffer, preventing video freeze with audio continuing

        // Interpolation / Blending (Smooth motion for 24fps/30fps videos on high refresh rate displays)
        val useInterpolation = DesktopDataStore.getKey<Boolean>(PREF_INTERPOLATION) ?: false
        if (useInterpolation) {
            lib.mpv_set_option_string(handle, "video-sync", "display-resample")
            lib.mpv_set_option_string(handle, "interpolation", "yes")
            lib.mpv_set_option_string(handle, "tscale", "oversample")
        } else {
            lib.mpv_set_option_string(handle, "video-sync", "audio")
            lib.mpv_set_option_string(handle, "interpolation", "no")
        }
    }
}
