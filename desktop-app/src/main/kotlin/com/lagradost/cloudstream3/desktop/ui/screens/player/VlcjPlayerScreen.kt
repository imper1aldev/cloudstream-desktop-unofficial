package com.lagradost.cloudstream3.desktop.ui.screens.player

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
import com.lagradost.cloudstream3.desktop.player.vlcj.BundledVlcDiscovery
import com.lagradost.cloudstream3.desktop.player.vlcj.VlcjEngine
import com.lagradost.cloudstream3.desktop.player.vlcj.VlcjPlayerState
import com.lagradost.cloudstream3.desktop.player.vlcj.VlcjVideoSurface
import com.lagradost.cloudstream3.desktop.ui.LocalWindowState
import com.lagradost.cloudstream3.desktop.ui.VideoLaunchData
import com.lagradost.common.logging.AppLogger
import com.lagradost.common.storage.DesktopDataStore
import com.lagradost.player.impl.PlayerLinkHandler
import kotlinx.coroutines.delay

private const val TAG = "VlcjPlayerScreen"

/**
 * Full-screen video player using VLCJ (libvlc) via direct rendering.
 * A drop-in replacement for [com.lagradost.cloudstream3.desktop.ui.screens.player.EmbeddedVideoPlayer].
 */
@Composable
fun VlcjPlayerScreen(
    launchData: VideoLaunchData,
    onClose: () -> Unit,
) {
    val windowState = LocalWindowState.current
    val isFullscreen = windowState?.placement == WindowPlacement.Fullscreen

    // ── Engine lifecycle ─────────────────────────────────────────
    val engine = remember { VlcjEngine() }

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
            engine.initialise()
            engineReady = true
            AppLogger.i("$TAG — VLCJ engine initialised")
        } catch (e: Exception) {
            AppLogger.e("$TAG — engine init failed", e)
            engineError = "VLCJ engine failed: ${e.message}"
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            engine.release()
            AppLogger.i("$TAG — engine released")
        }
    }

    // ── Playback state ─────────────────────────────────────────
    var currentLinkIndex by remember { mutableStateOf(launchData.initialIndex) }
    val autoPlay = DesktopDataStore
        .getKey<Boolean>(com.lagradost.cloudstream3.desktop.player.PlayerConfig.PREF_AUTO_PLAY) ?: true

    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isFinished by remember { mutableStateOf(false) }
    var lastPositionSec by remember { mutableStateOf(0L) }
    var lastDurationSec by remember { mutableStateOf(0L) }
    var lastSavedPositionSec by remember { mutableStateOf(0L) }

    // Save watch history on close
    DisposableEffect(Unit) {
        onDispose {
            if (lastDurationSec > 0 && lastPositionSec > 0) {
                val updatedHistory = launchData.history.copy(
                    position = lastPositionSec,
                    duration = lastDurationSec,
                    updateTime = System.currentTimeMillis(),
                )
                DesktopDataStore.setLastWatched(updatedHistory)
            }
        }
    }

    // ── Playback guard ───────────────────────────────────────────
    // Prevents double play() when LaunchedEffect re-fires.
    var playStarted by remember { mutableStateOf(false) }

    // ── Playback launcher ──────────────────────────────────────
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

        // Build headers list for VLCJ
        val headers = validated.headers.map { "${it.key}: ${it.value}" }

        engine.play(
            url = validated.url,
            title = validated.displayTitle.ifBlank { launchData.title },
            headers = headers,
            startMs = if (index == launchData.initialIndex) launchData.startPositionMs else 0L,
        )
    }

    // Start playback once engine is ready
    LaunchedEffect(engineReady) {
        if (engineReady && !playStarted) {
            playStarted = true
            playLink(currentLinkIndex)
        }
    }

    // ── Position & finish polling ──────────────────────────────
    LaunchedEffect(engineReady) {
        if (!engineReady) return@LaunchedEffect

        while (true) {
            delay(250)

            if (engine.isFinished) {
                if (isLoading) {
                    // Finished before first frame — treat as error
                    if (autoPlay && currentLinkIndex + 1 < launchData.links.size) {
                        currentLinkIndex++
                        playLink(currentLinkIndex)
                    } else {
                        isLoading = false
                        if (launchData.onError != null) {
                            launchData.onError.invoke("Stream failed to load")
                            onClose()
                        } else {
                            errorMessage = "Stream failed to load or ended instantly."
                        }
                    }
                } else {
                    // Normal finish
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
                val posSec = posMs / 1000L
                val durSec = durMs / 1000L
                lastPositionSec = posSec
                lastDurationSec = durSec

                if (kotlin.math.abs(posSec - lastSavedPositionSec) >= 5) {
                    lastSavedPositionSec = posSec
                    DesktopDataStore.setLastWatched(
                        launchData.history.copy(
                            position = posSec,
                            duration = durSec,
                            updateTime = System.currentTimeMillis(),
                        )
                    )
                }
            }

            // Mark ready when first position reported
            if (posMs > 0 && isLoading) {
                isLoading = false
            }
        }
    }

    // ── UI ─────────────────────────────────────────────────────
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
            // ── Video surface ──────────────────────────────────
            if (errorMessage == null && !isFinished && engineReady) {
                Box(modifier = Modifier.fillMaxSize()) {
                    key(currentLinkIndex) {
                        VlcjVideoSurface(
                            engine = engine,
                            modifier = if (isLoading) Modifier.size(1.dp) else Modifier.fillMaxSize(),
                            maintainAspectRatio = true,
                        )
                    }
                }
            }

            // ── Loading state ──────────────────────────────────
            if (isLoading && errorMessage == null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.primary,
                            strokeWidth = 4.dp,
                            modifier = Modifier.size(48.dp),
                        )
                        if (autoPlay && launchData.links.size > 1) {
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "Trying link ${currentLinkIndex + 1} of ${launchData.links.size}...",
                                color = MaterialTheme.colorScheme.onSurface,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }

            // ── Engine init error state ────────────────────────
            if (engineError != null) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = "VLCJ Engine Error",
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

            // ── Playback error state ───────────────────────────
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

            // ── Finished state ─────────────────────────────────
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
                            windowState?.placement = WindowPlacement.Floating
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
