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
        // CRITICAL WINDOWS RENDERING FIXES:
        // When MPV is embedded inside a Java AWT Canvas (child window), Windows DXGI flip-model 
        // presentation (the default) causes severe frame pacing issues, making the video play in 
        // slow motion while audio continues perfectly. We MUST disable flip-model for embedded windows.
        lib.mpv_set_option_string(handle, "d3d11-flip", "no")
        lib.mpv_set_option_string(handle, "gpu-context", "d3d11")

        // Hardware Acceleration (Default: auto-copy for embedded rendering safety)
        var hwdec = DesktopDataStore.getKey<String>(PREF_HWDEC) ?: "auto-copy"
        if (hwdec == "auto-safe") hwdec = "auto-copy" // Force migration from unsafe default
        lib.mpv_set_option_string(handle, "hwdec", hwdec)

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
        lib.mpv_set_option_string(handle, "msg-level", "all=v")
        lib.mpv_set_option_string(handle, "terminal", "yes")

        // Fast Startup Optimizations
        lib.mpv_set_option_string(handle, "cache", "yes")
        lib.mpv_set_option_string(handle, "demuxer-max-bytes", "150M") // Generous buffer
        lib.mpv_set_option_string(handle, "demuxer-max-back-bytes", "50M")
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
