package com.lagradost.cloudstream3.desktop.player.vlcj2

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.common.logging.AppLogger
import kotlinx.coroutines.delay

private const val TAG = "Vlcj2PlayerHud"

@Composable
fun Vlcj2PlayerHud(
    state: PlayerUiState,
    links: List<ExtractorLink>,
    currentLinkIndex: Int,
    subtitleTracks: List<SubtitleTrackInfo>,
    isEpisode: Boolean,
    autoPlayEnabled: Boolean,
    toastMessage: String?,
    onPlayPause: () -> Unit,
    onSeek: (Float) -> Unit,
    onVolumeChange: (Int) -> Unit,
    onServerSelect: (Int) -> Unit,
    onSubtitleSelect: (Int) -> Unit,
    onHwModeChange: (String) -> Unit,
    onSkipIntro: () -> Unit,
    onAutoplayNext: () -> Unit,
    onCancelAutoplay: () -> Unit,
    onClose: () -> Unit,
    content: @Composable () -> Unit,
) {
    var isHudVisible by remember { mutableStateOf(true) }
    var lastInteractionTime by remember { mutableStateOf(0L) }

    // Temporary local HW mode state (will come from parent in T13)
    var currentHwMode by remember { mutableStateOf("default") }

    // Snapshot-aware isPlaying flag so LaunchedEffect reads latest value
    var isPlaying by remember { mutableStateOf(state is PlayerUiState.Playing) }
    isPlaying = state is PlayerUiState.Playing

    // ── Auto-hide timer ────────────────────────────────────────────
    LaunchedEffect(Unit) {
        while (true) {
            delay(3000)
            if (isHudVisible && isPlaying &&
                System.currentTimeMillis() - lastInteractionTime > 3000
            ) {
                isHudVisible = false
            }
        }
    }

    // ── Cursor hide via AWT transparent cursor ─────────────────────
    val transparentCursor = try {
        java.awt.Toolkit.getDefaultToolkit().createCustomCursor(
            java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB),
            java.awt.Point(0, 0),
            "hidden",
        )
    } catch (e: Exception) {
        AppLogger.e("$TAG — [CURSOR] createCustomCursor(1x1) FAILED", e)
        null
    }
    LaunchedEffect(isHudVisible) {
        val threadName = Thread.currentThread().name
        val windows = java.awt.Window.getWindows()
        val activeWindow = windows.firstOrNull { it.isVisible && it.isActive }
        AppLogger.i("$TAG — [CURSOR] isHudVisible=$isHudVisible thread=$threadName windows=${windows.size} activeWindow=${activeWindow?.javaClass?.simpleName}")
        val targetCursor = if (isHudVisible) {
            java.awt.Cursor.getDefaultCursor()
        } else {
            transparentCursor ?: java.awt.Cursor.getDefaultCursor()
        }
        // Find the active AWT Window and set its cursor
        java.awt.Window.getWindows().firstOrNull { it.isVisible && it.isActive }?.cursor = targetCursor
        AppLogger.i("$TAG — [CURSOR] set to ${targetCursor::class.simpleName}")
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        AppLogger.i("$TAG — [CURSOR] pointerEvent type=${event.type} thread=${Thread.currentThread().name}")
                        lastInteractionTime = System.currentTimeMillis()
                        if (!isHudVisible) isHudVisible = true
                    }
                }
            },
    ) {
        // ── Layer 0: Content / state screens ──────────────────────
        when (state) {
            is PlayerUiState.Idle, is PlayerUiState.Initializing -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.primary,
                            strokeWidth = 4.dp,
                            modifier = Modifier.size(48.dp),
                        )
                        if (state is PlayerUiState.Initializing && state.message.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = state.message,
                                color = Color.LightGray,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }

            is PlayerUiState.Playing -> {
                content()
            }

            is PlayerUiState.Error -> {
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
                        text = state.message,
                        color = Color.LightGray,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(onClick = onClose) {
                        Text("Go Back")
                    }
                }
            }

            is PlayerUiState.Finished -> {
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

                    if (autoPlayEnabled) {
                        var countdown by remember { mutableIntStateOf(5) }

                        LaunchedEffect(Unit) {
                            while (countdown > 0) {
                                delay(1000)
                                countdown--
                            }
                            onAutoplayNext()
                        }

                        Text(
                            text = "Next episode starting in $countdown...",
                            color = Color.LightGray,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        OutlinedButton(onClick = onCancelAutoplay) {
                            Text("Cancel")
                        }
                    } else {
                        Text(
                            text = "Please close the player to select the next episode.",
                            color = Color.LightGray,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))
                    Button(onClick = onClose) {
                        Text("Close Player")
                    }
                }
            }

            is PlayerUiState.Reinitializing -> {
                Box(modifier = Modifier.fillMaxSize()) {
                    content()
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = state.reason,
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
            }
        }

        // ── Layer 1: HUD overlay (fade in/out) ────────────────────
        AnimatedVisibility(
            visible = isHudVisible && state is PlayerUiState.Playing,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(500)),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // TopBar: back button + title + server selector placeholder
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Black.copy(alpha = 0.7f),
                                    Color.Transparent,
                                ),
                            ),
                        )
                        .height(56.dp)
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onClose) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White,
                        )
                    }

                    Text(
                        text = links.getOrNull(currentLinkIndex)?.name ?: "Now Playing",
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )

                    ServerDropdown(
                        links = links,
                        currentIndex = currentLinkIndex,
                        onSelect = onServerSelect,
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                // Bottom controls (only show for Playing state)
                if (state is PlayerUiState.Playing) {
                    PlayerBottomBar(
                        state = state,
                        onPlayPause = onPlayPause,
                        onSeek = onSeek,
                        onVolumeChange = onVolumeChange,
                        subtitleTracks = subtitleTracks,
                        onSubtitleSelect = onSubtitleSelect,
                        currentHwMode = currentHwMode,
                        onHwModeChange = onHwModeChange,
                    )
                }
            }
        }

        // ── Layer 2: Skip Intro button (first 132s for episodes) ────
        Box(modifier = Modifier.align(Alignment.BottomEnd)) {
            SkipIntroButton(
                visible = isEpisode && isHudVisible && state is PlayerUiState.Playing &&
                         if (state.durationMs > 0L) state.positionMs < (state.durationMs * 0.75f).toLong()
                         else state.positionMs < 132_000L,
                onSkip = onSkipIntro,
            )
        }

        // ── Layer 3: Toast message overlay ─────────────────────────
        AnimatedVisibility(
            visible = toastMessage != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 16.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color.Black.copy(alpha = 0.8f),
            ) {
                Text(
                    text = toastMessage ?: "",
                    color = Color.White,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
    }
}

@Composable
private fun SkipIntroButton(
    visible: Boolean,
    onSkip: () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
        modifier = Modifier.padding(end = 24.dp, bottom = 80.dp),
    ) {
        Button(
            onClick = onSkip,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.White.copy(alpha = 0.2f),
                contentColor = Color.White,
            ),
        ) {
            Icon(
                imageVector = Icons.Filled.SkipNext,
                contentDescription = null,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Skip Intro")
        }
    }
}

@Composable
private fun ServerDropdown(
    links: List<ExtractorLink>,
    currentIndex: Int,
    onSelect: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        TextButton(onClick = { expanded = true }) {
            Text(
                text = links.getOrNull(currentIndex)?.name ?: "Server",
                color = Color.White.copy(alpha = 0.7f),
                maxLines = 1,
            )
            Icon(
                imageVector = Icons.Filled.ArrowDropDown,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.7f),
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            links.forEachIndexed { index, link ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = "${link.name} - ${link.quality}",
                            fontWeight = if (index == currentIndex) FontWeight.Bold else FontWeight.Normal,
                        )
                    },
                    onClick = {
                        onSelect(index)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun PlayerBottomBar(
    state: PlayerUiState.Playing,
    onPlayPause: () -> Unit,
    onSeek: (Float) -> Unit,
    onVolumeChange: (Int) -> Unit,
    subtitleTracks: List<SubtitleTrackInfo>,
    onSubtitleSelect: (Int) -> Unit,
    currentHwMode: String,
    onHwModeChange: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color.Transparent,
                        Color.Black.copy(alpha = 0.7f),
                    ),
                ),
            )
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Play/Pause button
        IconButton(onClick = onPlayPause) {
            Icon(
                imageVector = if (state.isPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                contentDescription = if (state.isPaused) "Play" else "Pause",
                tint = Color.White,
            )
        }

        // Time display
        val currentTime = formatTime(state.positionMs)
        val totalTime = formatTime(state.durationMs)
        Text(
            text = "$currentTime / $totalTime",
            color = Color.White,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 8.dp),
        )

        // Seek slider
        Slider(
            value = if (state.durationMs > 0)
                (state.positionMs.toFloat() / state.durationMs.toFloat()).coerceIn(0f, 1f)
            else 0f,
            onValueChange = onSeek,
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color.White,
                inactiveTrackColor = Color.White.copy(alpha = 0.3f),
            ),
        )

        // CC button for subtitles
        SubtitleButton(
            subtitleTracks = subtitleTracks,
            currentTrackId = -1,
            onSelect = onSubtitleSelect,
        )

        // Volume button
        IconButton(onClick = { onVolumeChange(if (state.volume > 0) 0 else 80) }) {
            Icon(
                imageVector = when {
                        state.volume == 0 -> Icons.AutoMirrored.Filled.VolumeOff
                        state.volume < 50 -> Icons.AutoMirrored.Filled.VolumeDown
                        else -> Icons.AutoMirrored.Filled.VolumeUp
                },
                contentDescription = "Volume",
                tint = Color.White,
            )
        }

        // Volume slider
        Slider(
            value = state.volume.toFloat(),
            onValueChange = { onVolumeChange(it.toInt()) },
            valueRange = 0f..100f,
            modifier = Modifier.width(100.dp),
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color.White,
                inactiveTrackColor = Color.White.copy(alpha = 0.3f),
            ),
        )

        // HW acceleration mode selector
        HwModeButton(
            currentMode = currentHwMode,
            onSelect = onHwModeChange,
        )
    }
}

@Composable
private fun HwModeButton(
    currentMode: String,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                imageVector = Icons.Filled.Settings,
                contentDescription = "Hardware Acceleration",
                tint = Color.White,
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            listOf(
                "software" to "Software decoding",
                "default" to "Default",
                "agresivo" to "Hardware decoding",
            ).forEach { (mode, label) ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = label,
                            fontWeight = if (mode == currentMode) FontWeight.Bold else FontWeight.Normal,
                        )
                    },
                    onClick = { onSelect(mode); expanded = false },
                )
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSec = ms / 1000
    val hours = totalSec / 3600
    val minutes = (totalSec % 3600) / 60
    val seconds = totalSec % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

@Composable
private fun SubtitleButton(
    subtitleTracks: List<SubtitleTrackInfo>,
    currentTrackId: Int,
    onSelect: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                imageVector = Icons.Filled.ClosedCaption,
                contentDescription = "Subtitles",
                tint = if (currentTrackId >= 0) Color.White else Color.Gray,
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text = { Text("Off") },
                onClick = { onSelect(-1); expanded = false },
            )

            if (subtitleTracks.isNotEmpty()) {
                HorizontalDivider()
                subtitleTracks.forEach { track ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = track.name,
                                fontWeight = if (track.id == currentTrackId) FontWeight.Bold else FontWeight.Normal,
                            )
                        },
                        onClick = { onSelect(track.id); expanded = false },
                    )
                }
            }
        }
    }
}
