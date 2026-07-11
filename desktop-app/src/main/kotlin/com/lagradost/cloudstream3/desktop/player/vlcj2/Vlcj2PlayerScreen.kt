package com.lagradost.cloudstream3.desktop.player.vlcj2

import androidx.compose.runtime.*

import com.lagradost.cloudstream3.desktop.ui.VideoLaunchData
import com.lagradost.cloudstream3.desktop.player.PlayerConfig
import com.lagradost.common.logging.AppLogger
import com.lagradost.common.storage.DesktopDataStore
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput

private const val TAG = "Vlcj2PlayerScreen"

private fun KeyEvent.handlePlayerShortcut(
    onTogglePause: () -> Unit,
    onSeekRelative: (Long) -> Unit,
    onVolumeChange: (Int) -> Unit,
    onToggleFullscreen: () -> Unit,
    onToggleMute: () -> Unit,
    isFullscreen: Boolean,
): Boolean {
    if (type != KeyEventType.KeyUp) return false
    return when (key) {
        Key.Spacebar -> { onTogglePause(); true }
        Key.DirectionLeft -> { onSeekRelative(-10_000L); true }
        Key.DirectionRight -> { onSeekRelative(10_000L); true }
        Key.DirectionUp -> { onVolumeChange(5); true }
        Key.DirectionDown -> { onVolumeChange(-5); true }
        Key.F -> { onToggleFullscreen(); true }
        Key.Escape -> { if (isFullscreen) onToggleFullscreen(); true }
        Key.M -> { onToggleMute(); true }
        else -> false
    }
}

@Composable
fun Vlcj2PlayerScreen(
    launchData: VideoLaunchData,
    onClose: () -> Unit,
) {
    var isFullscreen by remember { mutableStateOf(false) }
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
    val focusRequester = remember { FocusRequester() }

    val toggleFullscreen: () -> Unit = {
        try {
            val frame = java.awt.Window.getWindows()
                .firstOrNull { it is java.awt.Frame && it.isVisible }
            if (frame is java.awt.Frame) {
                val device = frame.graphicsConfiguration.device
                if (device.fullScreenWindow == frame) {
                    device.fullScreenWindow = null
                    isFullscreen = false
                } else {
                    device.fullScreenWindow = frame
                    isFullscreen = true
                }
            }
        } catch (e: Exception) {
            AppLogger.e("$TAG — fullscreen toggle failed", e)
        }
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .focusTarget()
            .onPreviewKeyEvent { event ->
                event.handlePlayerShortcut(
                    onTogglePause = controller::togglePause,
                    onSeekRelative = { controller.seek(engine.positionMs + it) },
                    onVolumeChange = controller::setVolume,
                    onToggleFullscreen = {
                        toggleFullscreen()
                    },
                    onToggleMute = controller::toggleMute,
                    isFullscreen = isFullscreen,
                )
            }
    ) {
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
            onMuteToggle = controller::toggleMute,
            onServerSelect = controller::switchServer,
            onSubtitleSelect = controller::setSubtitleTrack,
            onSkipIntro = controller::skipIntro,
            onAutoplayNext = { launchData.onNextEpisode?.invoke() ?: controller.retryNext() },
            onCancelAutoplay = { /* cancel logic handled inside HUD */ },
            onNextEpisode = launchData.onNextEpisode,
            showAutoplay = launchData.onNextEpisode != null,
            onClose = {
                launchData.onClosed?.invoke()
                onClose()
            },
            isFullscreen = isFullscreen,
            onToggleFullscreen = {
                toggleFullscreen()
            },
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(onDoubleTap = {
                            toggleFullscreen()
                        })
                    }
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                if (event.type == PointerEventType.Scroll && event.changes.isNotEmpty()) {
                                    val deltaY = event.changes.firstOrNull()?.scrollDelta?.y ?: continue
                                    val delta = if (deltaY < 0f) 5 else -5
                                    val newVol = (engine.volume + delta).coerceIn(0, 100)
                                    controller.setVolume(newVol)
                                    event.changes.forEach { it.consume() }
                                }
                            }
                        }
                    }
            ) {
                Vlcj2VideoSurface(engine = engine)
            }
        }
    }
}
