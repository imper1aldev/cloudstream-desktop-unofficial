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
    val autoPlay = DesktopDataStore.getKey<Boolean>(com.lagradost.cloudstream3.desktop.player.PlayerConfig.PREF_AUTO_PLAY) ?: true

    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var failedLinks by remember { mutableStateOf(setOf<Int>()) }
    var showSources by remember { mutableStateOf(false) }
    var isFinished by remember { mutableStateOf(false) }
    var lastPositionSec by remember { mutableStateOf(0L) }
    var lastDurationSec by remember { mutableStateOf(0L) }
    var lastSavedPositionSec by remember { mutableStateOf(0L) }
    val windowState = LocalWindowState.current
    val isFullscreen = windowState?.placement == WindowPlacement.Fullscreen
    val initialPlacement = remember { windowState?.placement ?: WindowPlacement.Floating }

    // Reset playback state whenever launchData changes (new episode loaded)
    LaunchedEffect(actualLaunchData) {
        isLoading = true
        errorMessage = null
        lastPositionSec = 0L
        lastDurationSec = 0L
        lastSavedPositionSec = 0L
        com.lagradost.player.impl.proxy.LocalStreamProxyState.loadingStatus.value = null
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
        // --- Video Engine / Loading / Error Container ---
        BoxWithConstraints(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            val playerMaxWidth = maxWidth
            val playerMaxHeight = maxHeight

            if (errorMessage == null && !isFinished) {
                // We no longer attempt to hide the AWT Canvas while loading (via size 0 or offset)
                // because hiding heavyweight Windows HWNDs in Compose causes permanent clipping glitches (black screens).
                // Instead, it will just display a safe, clean black background until the first video frame renders!
                val playerState = remember { com.lagradost.cloudstream3.desktop.ui.screens.player.PlayerState() }

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

                Box(
                    modifier = Modifier.fillMaxSize(),
                ) {
                    if (actualLaunchData.links.isNotEmpty()) {
                        var saveJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
                        ComposeMpvPlayer(
                            link = actualLaunchData.links.getOrNull(currentLinkIndex) ?: return@Box,
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

                                // Find next non-failed link index to auto-failover to
                                val nextIndex = (0 until actualLaunchData.links.size)
                                    .firstOrNull { it > currentLinkIndex && it !in newFailed }

                                when {
                                    nextIndex != null -> {
                                        // Silently advance to the next working link
                                        currentLinkIndex = nextIndex
                                        isLoading = true
                                        errorMessage = null
                                    }
                                    newFailed.size >= actualLaunchData.links.size -> {
                                        // All links exhausted
                                        errorMessage = "All sources failed. Please try again later."
                                        showSources = true
                                        isLoading = false
                                    }
                                    else -> {
                                        // No more links ahead, but there may be untried ones before current
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
                                if (windowState != null) {
                                    windowState.placement = if (isFullscreen) WindowPlacement.Floating else WindowPlacement.Fullscreen
                                }
                            },
                            playerState = playerState,
                        )
                    }

                    // Track if the Compose window is focused using Compose's native WindowInfo.
                    // We debounce the focus loss by 250ms. When you click a button on the floating Popup,
                    // Windows might momentarily flash the main window's focus state to false. If we don't debounce,
                    // the Popup instantly vanishes and reappears in a fast loop!
                    val isWindowFocused = androidx.compose.ui.platform.LocalWindowInfo.current.isWindowFocused
                    var isAppFocused by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(true) }

                    androidx.compose.runtime.LaunchedEffect(isWindowFocused) {
                        if (isWindowFocused) {
                            isAppFocused = true
                        } else {
                            kotlinx.coroutines.delay(250) // Wait 250ms to ensure the focus loss is real (e.g. alt-tab)
                            isAppFocused = false
                        }
                    }

                    // Track if the window is currently being dragged. Heavyweight Popup windows lag behind
                    // the main window during fast movement, creating a tearing/struggling effect.
                    // We hide the Popup while moving and show it 100ms after movement stops.
                    var isWindowMoving by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
                    androidx.compose.runtime.LaunchedEffect(windowState?.position) {
                        // Only hide the UI during movement if we are NOT in fullscreen mode.
                        // This prevents destroying the Popup during the transition to (0,0) fullscreen.
                        if (windowState?.placement != androidx.compose.ui.window.WindowPlacement.Fullscreen) {
                            isWindowMoving = true
                            kotlinx.coroutines.delay(100) // 100ms debounce
                            isWindowMoving = false
                        }
                    }

                    // Destroy the Popup completely while dragging to prevent the invisible JWindow from
                    // colliding with the mouse and cancelling the user's drag operation!
                    if (isAppFocused && !isWindowMoving) {
                        androidx.compose.ui.window.Popup(
                            alignment = Alignment.Center,
                            properties = androidx.compose.ui.window.PopupProperties(focusable = false),
                        ) {
                            // AWT Hack: Forcefully strip focusability from the Popup OS Window.
                            // Even with PopupProperties(focusable = false), Compose sometimes allows the Popup
                            // to steal keyboard focus when its buttons are clicked, which breaks video shortcuts (spacebar)
                            // and causes the main window to deactivate. This native AWT override completely bans it.
                            androidx.compose.runtime.LaunchedEffect(Unit) {
                                kotlinx.coroutines.delay(100) // Wait for the OS to actually map the Popup window
                                java.awt.Window.getWindows().forEach { w ->
                                    // Compose Popups are typically JDialog or JWindow.
                                    // AWT Fullscreen uses java.awt.Frame.
                                    // By targeting only JWindow/JDialog, we fix the focus bug without breaking fullscreen!
                                    if ((w is javax.swing.JWindow || w is javax.swing.JDialog) && w !is androidx.compose.ui.awt.ComposeWindow) {
                                        w.focusableWindowState = false
                                    }
                                }
                            }

                            Box(
                                modifier = Modifier.size(width = playerMaxWidth, height = playerMaxHeight).clipToBounds().focusProperties { canFocus = false },
                            ) {
                                com.lagradost.cloudstream3.desktop.ui.screens.player.components.NativePlayerControls(
                                    playerState = playerState,
                                    launchData = actualLaunchData,
                                    currentLinkIndex = currentLinkIndex,
                                    isFullscreen = isFullscreen,
                                    hasNextEpisode = viewModel.hasNextEpisode(),
                                    hasPrevEpisode = viewModel.hasPrevEpisode(),
                                    showSources = showSources,
                                    onShowSourcesChange = { showSources = it },
                                    failedLinks = failedLinks,
                                    onNextEpisode = {
                                        playerState?.pause()
                                        viewModel.loadNextEpisode()
                                    },
                                    onPrevEpisode = {
                                        playerState?.pause()
                                        viewModel.loadPrevEpisode()
                                    },
                                    onEpisodeSelected = {
                                        playerState?.pause()
                                        failedLinks = emptySet()
                                        viewModel.loadEpisode(it)
                                    },
                                    onLinkSelected = { newIndex ->
                                        playerState?.pause()
                                        currentLinkIndex = newIndex
                                        isLoading = true
                                    },
                                    onCloseClick = {
                                        if (windowState != null && initialPlacement != WindowPlacement.Fullscreen) {
                                            windowState.placement = initialPlacement
                                        }
                                        actualLaunchData.onClosed?.invoke()
                                        onClose()
                                    },
                                    onFullscreenToggle = {
                                        windowState?.placement = if (isFullscreen) WindowPlacement.Floating else WindowPlacement.Fullscreen
                                    },
                                )

                                // --- Initial Stream Loading State ---
                                if (isLoading && !isLoadingNextEpisode) {
                                    val proxyStatus by com.lagradost.player.impl.proxy.LocalStreamProxyState.loadingStatus.collectAsState()
                                    com.lagradost.cloudstream3.desktop.ui.screens.player.components.StreamLoadingOverlay(
                                        title = actualLaunchData.title ?: "Loading...",
                                        linkName = actualLaunchData.links.getOrNull(currentLinkIndex)?.name ?: "",
                                        loadingStatus = proxyStatus,
                                        onCancel = { isLoading = false }
                                    )
                                }

                                // --- Next Episode Loading State ---
                                if (isLoadingNextEpisode && targetEpisodeData != null) {
                                    com.lagradost.cloudstream3.desktop.ui.screens.player.components.PlayerLoadingOverlay(
                                        title = actualLaunchData.title ?: "Loading...",
                                        episodeText = targetEpisodeData?.let { "Season ${it.season} Episode ${it.episode}" } ?: "",
                                        linksFound = nextEpisodeLinks.size,
                                        onPlayNow = { viewModel.playLoadedEpisode() },
                                        onCancel = { viewModel.cancelLoading() },
                                    )
                                }

                                // --- Countdown Overlay ---
                                if (countdownToNextEpisode != null) {
                                    Box(
                                        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)),
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
                            }
                        }
                    }
                }
            }

            // Loading states have been moved inside the Popup block to render over the AWT Canvas

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
                        if (windowState != null && initialPlacement != WindowPlacement.Fullscreen) {
                            windowState.placement = initialPlacement
                        }
                        actualLaunchData.onClosed?.invoke()
                        onClose()
                    }) {
                        Text("Close Player")
                    }
                }
            }
        }
    }
}
