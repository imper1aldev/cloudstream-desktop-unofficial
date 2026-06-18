package com.lagradost.cloudstream3.desktop.ui.screens.player.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream3.desktop.ui.screens.player.PlayerState
import kotlinx.coroutines.delay
import java.awt.Point
import java.awt.Toolkit
import java.awt.image.BufferedImage

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun NativePlayerControls(
    playerState: PlayerState,
    launchData: com.lagradost.cloudstream3.desktop.ui.VideoLaunchData,
    currentLinkIndex: Int,
    isFullscreen: Boolean,
    onLinkSelected: (Int) -> Unit,
    hasNextEpisode: Boolean = false,
    hasPrevEpisode: Boolean = false,
    onNextEpisode: () -> Unit = {},
    onPrevEpisode: () -> Unit = {},
    onEpisodeSelected: (com.lagradost.cloudstream3.Episode) -> Unit = {},
    onCloseClick: () -> Unit,
    onFullscreenToggle: () -> Unit,
    modifier: Modifier = Modifier,
    showSources: Boolean = false,
    onShowSourcesChange: (Boolean) -> Unit = {},
    failedLinks: Set<Int> = emptySet(),
) {
    val showControls by playerState.showControls.collectAsState()

    var lastInteractionTime by remember { mutableStateOf(System.currentTimeMillis()) }
    var showSettings by remember { mutableStateOf(false) }
    var showEpisodes by remember { mutableStateOf(false) }

    var showSettingsTab by remember { mutableStateOf(0) }
    val focusRequester = remember { FocusRequester() }

    val blankCursor = remember {
        val img = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
        val customCursor = Toolkit.getDefaultToolkit().createCustomCursor(img, Point(0, 0), "blank")
        PointerIcon(customCursor)
    }

    LaunchedEffect(lastInteractionTime, showSettings, showSources, showEpisodes) {
        playerState.showControls.value = true
        if (!showSettings && !showSources && !showEpisodes) {
            kotlinx.coroutines.delay(3000)
            playerState.showControls.value = false
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .then(if (!showControls) Modifier.pointerHoverIcon(blankCursor) else Modifier)
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = {
                        onFullscreenToggle()
                    },
                    onTap = {
                        if (showEpisodes || showSettings || showSources) {
                            showEpisodes = false
                            showSettings = false
                            onShowSourcesChange(false)
                        } else {
                            playerState.togglePlayPause()
                            // Force controls to show when toggling play/pause via click
                            lastInteractionTime = System.currentTimeMillis()
                        }
                    },
                )
            }
            .onPointerEvent(PointerEventType.Move) {
                lastInteractionTime = System.currentTimeMillis()
            }
            .onPointerEvent(PointerEventType.Press) {
                lastInteractionTime = System.currentTimeMillis()
            }
            .onPointerEvent(PointerEventType.Scroll) { event ->
                if (event.changes.any { it.isConsumed }) return@onPointerEvent

                lastInteractionTime = System.currentTimeMillis()
                // Mouse wheel volume control
                val delta = event.changes.first().scrollDelta.y
                if (delta > 0) {
                    playerState.setVolume(playerState.volume.value - 5f)
                } else if (delta < 0) {
                    playerState.setVolume(playerState.volume.value + 5f)
                }
                event.changes.forEach { it.consume() }
            },
    ) {
        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Top gradient
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp)
                        .align(Alignment.TopCenter)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent),
                            ),
                        ),
                )

                // Bottom gradient
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp)
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f)),
                            ),
                        ),
                )

                TopBarOverlay(
                    title = launchData.title ?: "Playing",
                    onBackClick = onCloseClick,
                    modifier = Modifier.align(Alignment.TopCenter),
                )

                TimelineBar(
                    playerState = playerState,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 32.dp, vertical = 24.dp),
                    onNextClick = if (hasNextEpisode) onNextEpisode else null,
                    onPrevClick = if (hasPrevEpisode) onPrevEpisode else null,
                    onEpisodesClick = { showEpisodes = !showEpisodes },
                    onAudioClick = {
                        showSettings = true
                        showSettingsTab = 0
                    },
                    onSubtitlesClick = {
                        showSettings = true
                        showSettingsTab = 1
                    },
                    onSpeedClick = {
                        showSettings = true
                        showSettingsTab = 2
                    },
                    onSourcesClick = { onShowSourcesChange(true) },
                    onFullscreenClick = onFullscreenToggle,
                    isFullscreen = isFullscreen,
                    onAspectRatioClick = { playerState.cycleAspectRatio() },
                )
            }
        }

        // Overlays
        StatsOverlay(
            playerState = playerState,
            modifier = Modifier.align(Alignment.TopStart).padding(top = 100.dp),
        )

        // Side Panels and Dialogs
        AnimatedVisibility(
            visible = showEpisodes,
            enter = androidx.compose.animation.slideInHorizontally(initialOffsetX = { it }),
            exit = androidx.compose.animation.slideOutHorizontally(targetOffsetX = { it }),
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            EpisodesOverlay(
                launchData = launchData,
                onClose = { showEpisodes = false },
                onPlayEpisode = {
                    onEpisodeSelected(it)
                    showEpisodes = false
                },
            )
        }

        if (showSettings) {
            val proxyAudioTracks by com.lagradost.player.impl.proxy.LocalStreamProxyState.lazyAudioTracks.collectAsState()
            val proxySubtitleTracks by com.lagradost.player.impl.proxy.LocalStreamProxyState.lazySubtitleTracks.collectAsState()

            SettingsDialog(
                playerState = playerState,
                subtitles = launchData.subtitles,
                lazyAudioTracks = proxyAudioTracks.map { PlayerState.LazyTrack(it.url, it.name, it.language) },
                lazySubtitleTracks = proxySubtitleTracks.map { PlayerState.LazyTrack(it.url, it.name, it.language) },
                initialTab = showSettingsTab,
                onDismissRequest = { showSettings = false },
            )
        }

        AnimatedVisibility(
            visible = showSources,
            enter = androidx.compose.animation.slideInHorizontally(initialOffsetX = { it }),
            exit = androidx.compose.animation.slideOutHorizontally(targetOffsetX = { it }),
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            SourcesOverlay(
                links = launchData.links,
                currentIndex = currentLinkIndex,
                failedLinks = failedLinks,
                onLinkSelected = onLinkSelected,
                onClose = { onShowSourcesChange(false) },
            )
        }
    }
}
