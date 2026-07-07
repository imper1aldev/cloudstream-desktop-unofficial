package com.lagradost.cloudstream3.desktop.player.vlcj2

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import com.lagradost.cloudstream3.desktop.player.vlcj2.BundledVlcDiscovery
import com.lagradost.cloudstream3.desktop.ui.LocalWindowState
import com.lagradost.cloudstream3.desktop.ui.VideoLaunchData
import com.lagradost.common.logging.AppLogger
import com.lagradost.common.storage.DesktopDataStore
import com.lagradost.player.impl.PlayerLinkHandler
import kotlinx.coroutines.delay

private const val TAG = "Vlcj2PlayerScreen"

/**
 * Minimal VLCJ2 player screen for Phase 0 baseline validation.
 *
 * Mirrors [com.lagradost.cloudstream3.desktop.ui.screens.player.VlcjPlayerScreen]
 * API but drives the vlcj2-based [Vlcj2Engine] and [Vlcj2VideoSurface].
 *
 * Lifecycle:
 *   1. LaunchedEffect discover → initialize engine → set engineReady
 *   2. LaunchedEffect(engineReady) triggers playLink on first link
 *   3. LaunchedEffect poll loop tracks positionMs/durationMs, detects finish
 *   4. DisposableEffect on close → engine.release() + watch-history save
 *
 * States: loading → playing → finished (or error).
 */
@Composable
fun Vlcj2PlayerScreen(
    launchData: VideoLaunchData,
    onClose: () -> Unit,
) {
    val windowState = LocalWindowState.current
    val isFullscreen = windowState?.placement == WindowPlacement.Fullscreen

    // ── Engine lifecycle ─────────────────────────────────────────────
    val engine = remember { Vlcj2Engine() }
    var engineReady by remember { mutableStateOf(false) }
    var engineError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val found = BundledVlcDiscovery.discover()
        if (!found) {
            engineError = "VLC native libraries not found.\n" +
                "Ensure libvlc.dll is bundled in app resources."
            return@LaunchedEffect
        }
        try {
            engine.initialize()
            engineReady = true
            AppLogger.i("$TAG — engine initialized")
        } catch (e: Exception) {
            AppLogger.e("$TAG — engine init failed", e)
            engineError = "VLCJ2 engine failed: ${e.message}"
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            engine.release()
            AppLogger.i("$TAG — engine released")
        }
    }

    // ── Playback state ───────────────────────────────────────────────
    val currentLinkIndex = launchData.initialIndex
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isFinished by remember { mutableStateOf(false) }
    var lastPositionSec by remember { mutableStateOf(0L) }
    var lastDurationSec by remember { mutableStateOf(0L) }

    // Save watch history on close
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
        }
    }

    // ── Playback launcher ──────────────────────────────────────────
    var playStarted by remember { mutableStateOf(false) }

    fun playLink(index: Int) {
        val link = launchData.links.getOrNull(index) ?: run {
            errorMessage = "No link at index $index"
            return
        }
        isLoading = true
        errorMessage = null

        val validated = PlayerLinkHandler.validate(link, launchData.title).getOrElse { err ->
            errorMessage = err.message ?: "Validation failed"
            isLoading = false
            return
        }

        val headers = validated.headers.map { "${it.key}: ${it.value}" }
        engine.play(
            url = validated.url,
            title = validated.displayTitle.ifBlank { launchData.title },
            headers = headers,
            startMs = if (index == launchData.initialIndex) launchData.startPositionMs else 0L,
        )
    }

    LaunchedEffect(engineReady) {
        if (engineReady && !playStarted) {
            playStarted = true
            playLink(currentLinkIndex)
        }
    }

    // ── Position & finish polling ──────────────────────────────────
    LaunchedEffect(engineReady) {
        if (!engineReady) return@LaunchedEffect

        while (true) {
            delay(250)

            if (engine.isFinished) {
                if (isLoading) {
                    isLoading = false
                    errorMessage = "Stream failed to load or ended instantly."
                } else {
                    val finalDuration = maxOf(lastDurationSec, launchData.history.duration)
                    if (finalDuration > 0) {
                        DesktopDataStore.setLastWatched(
                            launchData.history.copy(
                                position = finalDuration,
                                duration = finalDuration,
                                updateTime = System.currentTimeMillis(),
                            )
                        )
                    }
                    isFinished = true
                }
                break
            }

            val posMs = engine.positionMs
            val durMs = engine.durationMs
            if (posMs > 0 && !isLoading) {
                lastPositionSec = posMs / 1000L
                lastDurationSec = durMs / 1000L
            }

            if (posMs > 0 && isLoading) {
                isLoading = false
            }
        }
    }

    // ── UI ─────────────────────────────────────────────────────────
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        // Top bar (hidden in fullscreen)
        if (!isFullscreen) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .background(Color.Black),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = {
                        launchData.onClosed?.invoke()
                        onClose()
                    },
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White,
                    )
                }
                Text(
                    text = launchData.title ?: "",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                )
            }
        }

        // Video / loading / error container
        Box(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            // ── Video surface ────────────────────────────────────
            if (errorMessage == null && !isFinished && engineReady) {
                Box(modifier = Modifier.fillMaxSize()) {
                    Vlcj2VideoSurface(
                        engine = engine,
                        modifier = if (isLoading) Modifier.size(1.dp) else Modifier.fillMaxSize(),
                        maintainAspectRatio = true,
                    )
                }
            }

            // ── Loading state ────────────────────────────────────
            if (isLoading && errorMessage == null && engineError == null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.primary,
                        strokeWidth = 4.dp,
                        modifier = Modifier.size(48.dp),
                    )
                }
            }

            // ── Engine init error ────────────────────────────────
            if (engineError != null) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = "VLCJ2 Engine Error",
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = engineError ?: "",
                        color = Color.LightGray,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(onClick = onClose) {
                        Text("Go Back")
                    }
                }
            }

            // ── Playback error ───────────────────────────────────
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

            // ── Finished state ───────────────────────────────────
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
                        text = "Please close the player to select the next episode.",
                        color = Color.LightGray,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(onClick = {
                        if (isFullscreen) {
                            windowState.placement = WindowPlacement.Floating
                        }
                        launchData.onClosed?.invoke()
                        onClose()
                    }) {
                        Text("Close Player")
                    }
                }
            }
        }
    }
}
