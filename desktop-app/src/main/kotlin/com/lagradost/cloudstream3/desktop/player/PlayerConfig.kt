package com.lagradost.cloudstream3.desktop.player

import com.lagradost.common.storage.DesktopDataStore
import com.sun.jna.Pointer

object PlayerConfig {
    // MPV-era prefs (kept for backward compat, VLCJ may repurpose some)
    const val PREF_HWDEC = "player_hwdec"
    const val PREF_SUB_SIZE = "player_sub_size"
    const val PREF_SUB_COLOR = "player_sub_color"
    const val PREF_SUB_BG = "player_sub_bg"
    const val PREF_YTDL_FORMAT = "player_ytdl_format"
    const val PREF_AUTO_PLAY = "player_auto_play"
    const val PREF_AUTO_PLAY_TIMEOUT = "player_auto_play_timeout"

    // VLCJ pref keys
    const val PREF_USE_VLCJ = "player_use_vlcj"                 // Boolean — use VLCJ instead of MPV
    const val PREF_HW_MODE = "player_hw_mode"                   // "none", "auto", "dxva2"
    const val PREF_AUTO_PLAY_NEXT = "player_auto_play_next"     // Boolean
    const val PREF_SKIP_INTRO_DURATION = "player_skip_intro_duration"       // String — seconds, default "90"
    const val PREF_SKIP_INTRO_COMPENSATION = "player_skip_intro_compensation" // String — seconds, default "3"

    fun applyMpvSettings(handle: Pointer, lib: MpvLibrary) {
        // Hardware Acceleration (Default: auto-copy)
        val hwdec = DesktopDataStore.getKey<String>(PREF_HWDEC) ?: "auto-copy"
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
        lib.mpv_set_option_string(handle, "cache-pause", "no") // Start playing IMMEDIATELY without waiting to fill the buffer
    }
}
