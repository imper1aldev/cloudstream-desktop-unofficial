package com.lagradost.cloudstream3.desktop.ui.screens.player.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import com.lagradost.cloudstream3.desktop.ui.screens.player.PlayerState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimelineBar(
    playerState: PlayerState,
    modifier: Modifier = Modifier,
    isFullscreen: Boolean = false,
    onEpisodesClick: () -> Unit = {},
    onSettingsClick: () -> Unit = {},
    onSourcesClick: () -> Unit = {},
    onFullscreenClick: () -> Unit = {},
    onAspectRatioClick: () -> Unit = {},
    onSkipPrevious: (() -> Unit)? = null,
    onSkipNext: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
    ) {
        // Timeline Row
        TimelineScrubber(playerState)

        // Controls Row
        Box(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
        ) {
            // Left Side Controls (Volume)
            Row(
                modifier = Modifier.align(Alignment.CenterStart),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val volume by playerState.volume.collectAsState()
                IconButton(onClick = {
                    if (volume > 0f) playerState.setVolume(0f) else playerState.setVolume(100f)
                }) {
                    Icon(
                        imageVector = if (volume == 0f) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                        contentDescription = "Volume",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp),
                    )
                }

                // Modern sleek volume bar
                VolumeScrubber(playerState)
            }

            // Center Controls (Playback)
            Row(
                modifier = Modifier.align(Alignment.Center),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Skip Previous
                IconButton(
                    onClick = { onSkipPrevious?.invoke() },
                    enabled = onSkipPrevious != null
                ) {
                    Icon(
                        imageVector = Icons.Default.SkipPrevious,
                        contentDescription = "Previous",
                        tint = if (onSkipPrevious != null) Color.White else Color.White.copy(alpha = 0.3f),
                        modifier = Modifier.size(28.dp)
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                // Skip Backward 10s
                IconButton(onClick = { playerState.seekBy(-10000L) }) {
                    Icon(
                        imageVector = Icons.Default.Replay10,
                        contentDescription = "Rewind 10s",
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                val isPaused by playerState.isPaused.collectAsState()
                // Play/Pause
                IconButton(
                    onClick = { playerState.togglePlayPause() },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                        contentDescription = if (isPaused) "Play" else "Pause",
                        tint = Color.White,
                        modifier = Modifier.size(40.dp)
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                // Skip Forward 10s
                IconButton(onClick = { playerState.seekBy(10000L) }) {
                    Icon(
                        imageVector = Icons.Default.Forward10,
                        contentDescription = "Forward 10s",
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                // Skip Next
                IconButton(
                    onClick = { onSkipNext?.invoke() },
                    enabled = onSkipNext != null
                ) {
                    Icon(
                        imageVector = Icons.Default.SkipNext,
                        contentDescription = "Next",
                        tint = if (onSkipNext != null) Color.White else Color.White.copy(alpha = 0.3f),
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            // Right Side Controls
            Row(
                modifier = Modifier.align(Alignment.CenterEnd),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onSourcesClick) {
                    Icon(Icons.Default.Public, contentDescription = "Sources", tint = Color.White, modifier = Modifier.size(24.dp))
                }

                IconButton(onClick = {
                    val enabled = playerState.isInterpolationEnabled.value
                    playerState.setInterpolation(!enabled)
                }) {
                    val isEnabled by playerState.isInterpolationEnabled.collectAsState()
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .background(
                                if (isEnabled) Color(0xFF9151FF).copy(alpha = 0.2f) else Color.Transparent,
                                androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "SVP",
                            color = if (isEnabled) Color(0xFF9151FF) else Color.White,
                            fontSize = 10.sp,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        )
                    }
                }
                
                // Merged Settings Icon (Audio, Subtitles, Speed)
                IconButton(onClick = onSettingsClick) {
                    Icon(Icons.Default.Settings, contentDescription = "Settings", tint = Color.White, modifier = Modifier.size(24.dp))
                }

                IconButton(onClick = onAspectRatioClick) {
                    Icon(Icons.Default.AspectRatio, contentDescription = "Aspect Ratio", tint = Color.White, modifier = Modifier.size(24.dp))
                }
                IconButton(onClick = { playerState.showStats.value = !playerState.showStats.value }) {
                    Icon(Icons.Default.Info, contentDescription = "Stats", tint = Color.White, modifier = Modifier.size(24.dp))
                }
                IconButton(onClick = onEpisodesClick) {
                    Icon(Icons.Default.List, contentDescription = "Episodes", tint = Color.White, modifier = Modifier.size(28.dp))
                }
                IconButton(onClick = onFullscreenClick) {
                    Icon(
                        imageVector = if (isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                        contentDescription = "Fullscreen",
                        tint = Color.White,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimelineScrubber(playerState: PlayerState) {
    val positionMs by playerState.positionMs.collectAsState()
    val durationMs by playerState.durationMs.collectAsState()

    var isDragging by remember { mutableStateOf(false) }
    var dragPositionMs by remember { mutableStateOf(0L) }

    val currentMs = if (isDragging) dragPositionMs else positionMs
    val progress = if (durationMs > 0) (currentMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f
    val bufferMs by playerState.bufferMs.collectAsState()
    val bufferProgress = if (durationMs > 0) (bufferMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f

    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    val trackHeight by animateDpAsState(if (isHovered || isDragging) 6.dp else 4.dp)
    val thumbSize by animateDpAsState(if (isHovered || isDragging) 14.dp else 0.dp)

    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = formatTime(currentMs),
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(end = 12.dp),
        )

        Box(modifier = Modifier.weight(1f).height(24.dp), contentAlignment = Alignment.CenterStart) {
            // Sleek Custom M3 Slider
            Slider(
                value = progress,
                onValueChange = {
                    isDragging = true
                    dragPositionMs = (it * durationMs).toLong()
                },
                onValueChangeFinished = {
                    isDragging = false
                    playerState.seekTo(dragPositionMs)
                },
                modifier = Modifier.fillMaxWidth(),
                interactionSource = interactionSource,
                thumb = {
                    Box(
                        modifier = Modifier
                            .size(thumbSize)
                            .background(Color.White, CircleShape)
                    )
                },
                track = { sliderState ->
                    Box(modifier = Modifier.fillMaxWidth().height(trackHeight).background(Color.White.copy(alpha = 0.2f), CircleShape), contentAlignment = Alignment.CenterStart) {
                        Box(modifier = Modifier.fillMaxWidth(bufferProgress).height(trackHeight).background(Color.White.copy(alpha = 0.4f), CircleShape))
                        Box(modifier = Modifier.fillMaxWidth(sliderState.value).height(trackHeight).background(Color(0xFF9151FF), CircleShape))
                    }
                }
            )

            if (isDragging) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    Box(modifier = Modifier.fillMaxWidth(progress), contentAlignment = Alignment.TopEnd) {
                        Text(
                            text = formatTime(dragPositionMs),
                            modifier = Modifier
                                .offset(y = (-30).dp, x = 15.dp)
                                .background(Color.Black.copy(alpha = 0.8f), androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                            color = Color.White,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

        Text(
            text = formatTime(durationMs),
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

@Composable
private fun VolumeScrubber(playerState: PlayerState) {
    val volume by playerState.volume.collectAsState()
    val volInteractionSource = remember { MutableInteractionSource() }
    val isVolHovered by volInteractionSource.collectIsHoveredAsState()
    val volumeHeight by animateDpAsState(if (isVolHovered) 6.dp else 3.dp)

    Box(
        modifier = Modifier
            .width(100.dp)
            .height(24.dp)
            .hoverable(volInteractionSource)
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val pct = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                    playerState.setVolume(pct * 100f)
                }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    val pct = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                    playerState.setVolume(pct * 100f)
                }
            },
        contentAlignment = Alignment.CenterStart
    ) {
        // Background track
        Box(modifier = Modifier.fillMaxWidth().height(volumeHeight).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.3f)))
        // Foreground track
        Box(modifier = Modifier.fillMaxWidth(fraction = (volume / 100f).coerceIn(0f, 1f)).height(volumeHeight).clip(RoundedCornerShape(2.dp)).background(Color.White))
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return String.format("%02d:%02d:%02d", hours, minutes, seconds)
}
