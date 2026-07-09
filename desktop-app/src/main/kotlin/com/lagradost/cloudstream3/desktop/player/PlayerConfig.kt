package com.lagradost.cloudstream3.desktop.player

import com.lagradost.common.storage.DesktopDataStore

object PlayerConfig {
    // General prefs
    const val PREF_AUTO_PLAY = "player_auto_play"
    const val PREF_AUTO_PLAY_TIMEOUT = "player_auto_play_timeout"

    // VLCJ pref keys
    const val PREF_AUTO_PLAY_NEXT = "player_auto_play_next"     // Boolean
    const val PREF_SKIP_INTRO_DURATION = "player_skip_intro_duration"       // String — seconds, default "90"
    const val PREF_SKIP_INTRO_COMPENSATION = "player_skip_intro_compensation" // String — seconds, default "3"
}
