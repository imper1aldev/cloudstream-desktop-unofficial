package com.lagradost.cloudstream3.desktop.ui.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import com.lagradost.cloudstream3.desktop.player.ComposeMpvPlayer
import com.lagradost.cloudstream3.desktop.ui.LocalFullscreenController
import com.lagradost.cloudstream3.desktop.ui.LocalWindowState
import com.lagradost.cloudstream3.desktop.ui.VideoLaunchData
import com.lagradost.common.storage.DesktopDataStore
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

@Composable
fun EmbeddedVideoPlayer(
    launchData: VideoLaunchData,
    onClose: () -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    val viewModel = remember { EmbeddedPlayerViewModel(coroutineScope) }

    LaunchedEffect(launchData) {
        viewModel.init(launchData)
    }

    val currentLaunchData by viewModel.launchData.collectAsState()
    val isLoadingNextEpisode by viewModel.isLoadingNextEpisode.collectAsState()
    val nextEpisodeLinks by viewModel.nextEpisodeLinks.collectAsState()
    val targetEpisodeData by viewModel.targetEpisodeData.collectAsState()

    if (currentLaunchData == null) return

    val actualLaunchData = currentLaunchData!!
    var currentLinkIndex by remember(actualLaunchData) { mutableStateOf(actualLaunchData.initialIndex) }

    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var failedLinks by remember { mutableStateOf(setOf<Int>()) }
    var showSources by remember { mutableStateOf(false) }
    var isFinished by remember { mutableStateOf(false) }
    var lastPositionSec by remember { mutableStateOf(0L) }
    var lastDurationSec by remember { mutableStateOf(0L) }
    var lastSavedPositionSec by remember { mutableStateOf(0L) }

    val windowState = LocalWindowState.current
    val fullscreenController = LocalFullscreenController.current
    val isFullscreen = fullscreenController?.isFullscreen?.value ?: false
    val initialPlacement = remember { windowState?.placement ?: WindowPlacement.Floating }

    // PlayerState is hoisted to top level so it can be reset on episode/source changes
    val playerState = remember { PlayerState() }

    // Reset all playback state when a new episode loads
    LaunchedEffect(actualLaunchData) {
        isLoading = true
        errorMessage = null
        lastPositionSec = 0L
        lastDurationSec = 0L
        lastSavedPositionSec = 0L
        playerState.reset()
        com.lagradost.player.impl.proxy.LocalStreamProxyState.loadingStatus.value = null
    }

    // Reset loading spinner + player state when switching between sources
    LaunchedEffect(currentLinkIndex) {
        isLoading = true
        playerState.reset()
    }

    DisposableEffect(Unit) {
        onDispose {
            if (lastDurationSec > 0 && lastPositionSec > 0) {
                val updatedHistory = actualLaunchData.history.copy(
                    position = lastPositionSec,
                    duration = lastDurationSec,
                    updateTime = System.currentTimeMillis(),
                )
                DesktopDataStore.setLastWatched(updatedHistory)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        BoxWithConstraints(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            val playerMaxWidth = maxWidth
            val playerMaxHeight = maxHeight

            // --- Main Video + Controls ---
            if (errorMessage == null && !isFinished) {
                var countdownToNextEpisode by remember { mutableStateOf<Int?>(null) }

                LaunchedEffect(countdownToNextEpisode) {
                    if (countdownToNextEpisode != null) {
                        if (countdownToNextEpisode!! > 0) {
                            kotlinx.coroutines.delay(1000)
                            countdownToNextEpisode = countdownToNextEpisode!! - 1
                        } else {
                            countdownToNextEpisode = null
                            viewModel.loadNextEpisode()
                        }
                    }
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    // --- MPV Player ---
                    if (actualLaunchData.links.isNotEmpty()) {
                        var saveJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
                        var activelyPlayingLink by remember { mutableStateOf<com.lagradost.cloudstream3.utils.ExtractorLink?>(null) }
                        var lastLinkIndex by remember { mutableStateOf(-1) }

                        // Lock the playing link so background scraper additions don't interrupt playback
                        if (lastLinkIndex != currentLinkIndex || activelyPlayingLink == null) {
                            lastLinkIndex = currentLinkIndex
                            activelyPlayingLink = actualLaunchData.links.getOrNull(currentLinkIndex)
                        }

                        val safeLink = activelyPlayingLink ?: return@Box

                        ComposeMpvPlayer(
                            link = safeLink,
                            title = actualLaunchData.title,
                            subtitles = actualLaunchData.subtitles,
                            startPositionMs = if (currentLinkIndex != 0 && lastPositionSec > 0) lastPositionSec * 1000L else actualLaunchData.startPositionMs,
                            onPlaybackReady = {
                                isLoading = false
                            },
                            onPositionChange = { posMs, durMs ->
                                val currentPosSec = posMs / 1000L
                                val currentDurSec = durMs / 1000L
                                lastPositionSec = currentPosSec
                                lastDurationSec = currentDurSec

                                if (kotlin.math.abs(currentPosSec - lastSavedPositionSec) >= 5) {
                                    lastSavedPositionSec = currentPosSec
                                    val updatedHistory = actualLaunchData.history.copy(
                                        position = currentPosSec,
                                        duration = currentDurSec,
                                        updateTime = System.currentTimeMillis(),
                                    )
                                    saveJob?.cancel()
                                    saveJob = coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                        delay(2000)
                                        DesktopDataStore.setLastWatched(updatedHistory)
                                    }
                                }
                            },
                            onCloseRequest = {
                                actualLaunchData.onClosed?.invoke()
                                onClose()
                            },
                            onFinished = {
                                if (viewModel.hasNextEpisode()) {
                                    countdownToNextEpisode = 5
                                } else {
                                    isFinished = true
                                }
                            },
                            onPlaybackError = { err ->
                                val newFailed = failedLinks + currentLinkIndex
                                failedLinks = newFailed

                                val nextIndex = (0 until actualLaunchData.links.size)
                                    .firstOrNull { it > currentLinkIndex && it !in newFailed }

                                when {
                                    nextIndex != null -> {
                                        currentLinkIndex = nextIndex
                                        isLoading = true
                                        errorMessage = null
                                    }
                                    newFailed.size >= actualLaunchData.links.size -> {
                                        errorMessage = "All sources failed. Please try again later."
                                        showSources = true
                                        isLoading = false
                                    }
                                    else -> {
                                        val anyUntried = (0 until actualLaunchData.links.size)
                                            .firstOrNull { it !in newFailed }
                                        if (anyUntried != null) {
                                            currentLinkIndex = anyUntried
                                            isLoading = true
                                            errorMessage = null
                                        } else {
                                            errorMessage = "All sources failed. Please try again later."
                                            showSources = true
                                            isLoading = false
                                        }
                                    }
                                }
                            },
                            onFullscreenToggle = {
                                fullscreenController?.toggle?.invoke()
                            },
                            playerState = playerState,
                        )
                    }

                    // --- Focus tracking for popup visibility ---
                    val isWindowFocused = androidx.compose.ui.platform.LocalWindowInfo.current.isWindowFocused
                    var isAppFocused by remember { mutableStateOf(true) }

                    LaunchedEffect(isWindowFocused) {
                        if (isWindowFocused) {
                            isAppFocused = true
                        } else {
                            // 500ms debounce: clicking popup buttons causes brief focus flicker
                            kotlinx.coroutines.delay(500)
                            isAppFocused = isWindowFocused
                        }
                    }

                    // --- Controls Popup ---
                    // The Popup is ALWAYS rendered (never conditionally created/destroyed).
                    // Conditional creation caused a Windows bug: every new JWindow creation
                    // drops the app out of exclusive fullscreen mode.
                    // Instead, NativePlayerControls hides itself based on isAppFocused.
                    androidx.compose.ui.window.Popup(
                        alignment = Alignment.Center,
                        properties = androidx.compose.ui.window.PopupProperties(focusable = false),
                    ) {
                        // AWT hack: forcefully remove focusability from the popup JWindow.
                        // Also re-apply isAlwaysOnTop on fullscreen change to ensure Z-order
                        // above the native MPV surface.
                        LaunchedEffect(isFullscreen) {
                            kotlinx.coroutines.delay(80)
                            java.awt.Window.getWindows().forEach { w ->
                                if ((w is javax.swing.JWindow || w is javax.swing.JDialog) &&
                                    w !is androidx.compose.ui.awt.ComposeWindow
                                ) {
                                    w.focusableWindowState = false
                                    w.isAlwaysOnTop = isFullscreen
                                }
                            }
                        }

                        Box(
                            modifier = Modifier
                                .size(width = playerMaxWidth, height = playerMaxHeight)
                                .clipToBounds()
                                .focusProperties { canFocus = false },
                        ) {
                            com.lagradost.cloudstream3.desktop.ui.screens.player.components.NativePlayerControls(
                                playerState = playerState,
                                launchData = actualLaunchData,
                                currentLinkIndex = currentLinkIndex,
                                isFullscreen = isFullscreen,
                                isAppFocused = isAppFocused,
                                hasNextEpisode = viewModel.hasNextEpisode(),
                                hasPrevEpisode = viewModel.hasPrevEpisode(),
                                showSources = showSources,
                                onShowSourcesChange = { showSources = it },
                                failedLinks = failedLinks,
                                onNextEpisode = {
                                    playerState.pause()
                                    viewModel.loadNextEpisode()
                                },
                                onPrevEpisode = {
                                    playerState.pause()
                                    viewModel.loadPrevEpisode()
                                },
                                onEpisodeSelected = {
                                    playerState.pause()
                                    failedLinks = emptySet()
                                    viewModel.loadEpisode(it)
                                },
                                onLinkSelected = { newIndex ->
                                    playerState.pause()
                                    currentLinkIndex = newIndex
                                    isLoading = true
                                },
                                onCloseClick = {
                                    if (isFullscreen) fullscreenController?.toggle?.invoke()
                                    actualLaunchData.onClosed?.invoke()
                                    onClose()
                                },
                                onFullscreenToggle = {
                                    fullscreenController?.toggle?.invoke()
                                },
                            )

                            // --- Initial Stream Loading Overlay ---
                            if (isLoading && !isLoadingNextEpisode) {
                                val proxyStatus by com.lagradost.player.impl.proxy.LocalStreamProxyState.loadingStatus.collectAsState()
                                val isProbing by playerState.isProbing.collectAsState()

                                val statusToDisplay = when {
                                    proxyStatus != null -> proxyStatus
                                    isProbing -> "Probing connection natively..."
                                    else -> "Connecting to server..."
                                }

                                com.lagradost.cloudstream3.desktop.ui.screens.player.components.StreamLoadingOverlay(
                                    title = actualLaunchData.title ?: "Loading...",
                                    linkName = actualLaunchData.links.getOrNull(currentLinkIndex)?.name ?: "",
                                    loadingStatus = statusToDisplay,
                                    backdropUrl = actualLaunchData.loadResponse?.backgroundPosterUrl
                                        ?: actualLaunchData.loadResponse?.posterUrl,
                                    onCancel = { isLoading = false },
                                )
                            }

                            // --- Next Episode Loading Overlay ---
                            if (isLoadingNextEpisode && targetEpisodeData != null) {
                                com.lagradost.cloudstream3.desktop.ui.screens.player.components.PlayerLoadingOverlay(
                                    title = actualLaunchData.title ?: "Loading...",
                                    episodeText = targetEpisodeData?.let { "Season ${it.season} Episode ${it.episode}" } ?: "",
                                    linksFound = nextEpisodeLinks.size,
                                    backdropUrl = actualLaunchData.loadResponse?.backgroundPosterUrl
                                        ?: actualLaunchData.loadResponse?.posterUrl,
                                    onPlayNow = { viewModel.playLoadedEpisode() },
                                    onCancel = { viewModel.cancelLoading() },
                                )
                            }

                            // --- Next Episode Countdown Overlay ---
                            if (countdownToNextEpisode != null) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(Color.Black.copy(alpha = 0.5f)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                            "Next episode starting in $countdownToNextEpisode...",
                                            color = Color.White,
                                            style = MaterialTheme.typography.titleLarge,
                                        )
                                        Spacer(modifier = Modifier.height(16.dp))
                                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                            Button(
                                                onClick = {
                                                    countdownToNextEpisode = null
                                                    viewModel.loadNextEpisode()
                                                },
                                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                            ) {
                                                Text("Play Now")
                                            }
                                            Button(
                                                onClick = {
                                                    countdownToNextEpisode = null
                                                    isFinished = true
                                                },
                                                colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray),
                                            ) {
                                                Text("Cancel")
                                            }
                                        }
                                    }
                                }
                            }
                        } // end Popup Box
                    } // end Popup
                } // end outer Box
            } // end if (!error && !finished)

            // --- Error State ---
            if (errorMessage != null) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = "Playback Failed",
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = errorMessage ?: "Unknown error",
                        color = Color.LightGray,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(onClick = onClose) {
                        Text("Go Back")
                    }
                }
            }

            // --- Finished State ---
            if (isFinished) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.8f)),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = "Video Ended",
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "All episodes watched.",
                        color = Color.LightGray,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(onClick = {
                        if (isFullscreen) fullscreenController?.toggle?.invoke()
                        actualLaunchData.onClosed?.invoke()
                        onClose()
                    }) {
                        Text("Close Player")
                    }
                }
            }
        } // end BoxWithConstraints
    } // end Column
} // end EmbeddedVideoPlayer
