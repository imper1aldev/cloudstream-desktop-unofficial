package com.lagradost.cloudstream3.desktop.player.vlcj2

import androidx.compose.runtime.*
import androidx.compose.ui.window.WindowPlacement
import com.lagradost.cloudstream3.desktop.ui.LocalWindowState
import com.lagradost.cloudstream3.desktop.ui.VideoLaunchData
import com.lagradost.cloudstream3.desktop.player.PlayerConfig
import com.lagradost.common.logging.AppLogger
import com.lagradost.common.storage.DesktopDataStore

private const val TAG = "Vlcj2PlayerScreen"

@Composable
fun Vlcj2PlayerScreen(
    launchData: VideoLaunchData,
    onClose: () -> Unit,
) {
    val windowState = LocalWindowState.current
    val isFullscreen = windowState?.placement == WindowPlacement.Fullscreen
    val isEpisode = launchData.history.episode != null || launchData.history.season != null
    val autoPlayNext = DesktopDataStore.getKey<Boolean>(PlayerConfig.PREF_AUTO_PLAY_NEXT) ?: true

    // ── Engine + Controller lifecycle ────────────────────────────────
    val engine = remember { Vlcj2Engine() }
    val controller = remember { Vlcj2Controller(engine) }
    val scope = rememberCoroutineScope()

    // Collect state from controller
    val state by controller.state.collectAsState()
    val links by controller.links.collectAsState()
    val subtitleTracks by controller.subtitleTracks.collectAsState()
    var toastMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        controller.toastMessage.collect { msg ->
            toastMessage = msg
        }
    }

    // ── Initialize ──────────────────────────────────────────────────
    LaunchedEffect(Unit) {
        val found = BundledVlcDiscovery.discover()
        if (!found) {
            AppLogger.e("$TAG — VLC native libraries not found")
            return@LaunchedEffect
        }
        try {
            engine.initialize()
            controller.initialize(
                links = launchData.links,
                initialIndex = launchData.initialIndex,
                title = launchData.title,
                startMs = launchData.startPositionMs,
                scope = scope,
            )
            AppLogger.i("$TAG — controller initialized")
        } catch (e: Exception) {
            AppLogger.e("$TAG — init failed", e)
        }
    }

    // ── Watch-history save on close ─────────────────────────────────
    var lastPositionSec by remember { mutableStateOf(0L) }
    var lastDurationSec by remember { mutableStateOf(0L) }

    // Track position from state
    LaunchedEffect(state) {
        if (state is PlayerUiState.Playing) {
            val playing = state as PlayerUiState.Playing
            lastPositionSec = playing.positionMs / 1000L
            lastDurationSec = playing.durationMs / 1000L
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            if (lastDurationSec > 0 && lastPositionSec > 0) {
                DesktopDataStore.setLastWatched(
                    launchData.history.copy(
                        position = lastPositionSec,
                        duration = lastDurationSec,
                        updateTime = System.currentTimeMillis(),
                    )
                )
            }
            controller.release()
        }
    }

    // ── UI ──────────────────────────────────────────────────────────
    Vlcj2PlayerHud(
        state = state,
        links = links,
        currentLinkIndex = controller.currentLinkIndex.collectAsState().value,
        subtitleTracks = subtitleTracks,
        isEpisode = isEpisode,
        title = launchData.title,
        episodeName = launchData.episodeName,
        autoPlayEnabled = autoPlayNext,
        toastMessage = toastMessage,
        onPlayPause = controller::togglePause,
        onSeek = { ratio -> controller.seek((ratio * engine.durationMs).toLong()) },
        onVolumeChange = controller::setVolume,
        onServerSelect = controller::switchServer,
        onSubtitleSelect = controller::setSubtitleTrack,
        onHwModeChange = controller::setHwMode,
        onSkipIntro = controller::skipIntro,
        onAutoplayNext = { controller.retryNext() },
        onCancelAutoplay = { /* cancel logic handled inside HUD */ },
        onClose = {
            launchData.onClosed?.invoke()
            onClose()
        },
    ) {
        Vlcj2VideoSurface(engine = engine)
    }
}
