package com.lagradost.cloudstream3.desktop.ui.screens.player.components

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lagradost.cloudstream3.desktop.ui.screens.player.PlayerState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimelineBar(
    playerState: com.lagradost.cloudstream3.desktop.ui.screens.player.PlayerState,
    modifier: Modifier = Modifier,
    isFullscreen: Boolean = false,
    onEpisodesClick: () -> Unit = {},
    onSubtitlesClick: () -> Unit = {},
    onAudioClick: () -> Unit = {},
    onSourcesClick: () -> Unit = {},
    onSpeedClick: () -> Unit = {},
    onFullscreenClick: () -> Unit = {},
    onAspectRatioClick: () -> Unit = {},
    onNextClick: (() -> Unit)? = null,
    onPrevClick: (() -> Unit)? = null,
) {
    val positionMs by playerState.positionMs.collectAsState()
    val durationMs by playerState.durationMs.collectAsState()
    val isPaused by playerState.isPaused.collectAsState()
    val isPlaying = !isPaused
    val volume by playerState.volume.collectAsState()

    var isDragging by remember { mutableStateOf(false) }
    var dragPositionMs by remember { mutableStateOf(0L) }

    val currentMs = if (isDragging) dragPositionMs else positionMs
    val progress = if (durationMs > 0) (currentMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f

    Column(
        modifier = modifier.fillMaxWidth(),
    ) {
        // Timeline Row
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

            val bufferMs by playerState.bufferMs.collectAsState()
            val bufferProgress = if (durationMs > 0) (bufferMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f

            Box(modifier = Modifier.weight(1f).height(24.dp), contentAlignment = Alignment.CenterStart) {
                // Custom Buffer Background
                Box(modifier = Modifier.fillMaxWidth().height(4.dp).background(Color.White.copy(alpha = 0.2f), androidx.compose.foundation.shape.CircleShape))
                Box(modifier = Modifier.fillMaxWidth(bufferProgress).height(4.dp).background(Color.White.copy(alpha = 0.4f), androidx.compose.foundation.shape.CircleShape))

                androidx.compose.material.Slider(
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
                    colors = androidx.compose.material.SliderDefaults.colors(
                        thumbColor = Color(0xFF9151FF),
                        activeTrackColor = Color(0xFF9151FF),
                        inactiveTrackColor = Color.Transparent,
                    ),
                    interactionSource = remember { MutableInteractionSource() },
                )

                if (isDragging) {
                    Box(modifier = Modifier.fillMaxWidth()) {
                        Box(modifier = Modifier.fillMaxWidth(progress), contentAlignment = Alignment.TopEnd) {
                            Text(
                                text = formatTime(dragPositionMs),
                                modifier = Modifier
                                    .offset(y = (-30).dp, x = 15.dp) // shift up and slightly right to center above thumb
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

        // Controls Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            // Left Side Controls
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = {
                    playerState.togglePlayPause()
                }) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        tint = Color.White,
                        modifier = Modifier.size(32.dp),
                    )
                }

                if (onPrevClick != null) {
                    IconButton(onClick = onPrevClick) {
                        Icon(
                            imageVector = Icons.Default.SkipPrevious,
                            contentDescription = "Previous Episode",
                            tint = Color.White,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                }

                if (onNextClick != null) {
                    IconButton(onClick = onNextClick) {
                        Icon(
                            imageVector = Icons.Default.SkipNext,
                            contentDescription = "Next Episode",
                            tint = Color.White,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

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

                // Volume slider
                androidx.compose.material.Slider(
                    value = volume / 100f,
                    onValueChange = { playerState.setVolume(it * 100f) },
                    modifier = Modifier.width(100.dp).height(24.dp),
                    colors = androidx.compose.material.SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = Color.White,
                        inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                    ),
                )
            }

            // Right Side Controls
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onSourcesClick) {
                    Icon(
                        imageVector = Icons.Default.Public,
                        contentDescription = "Sources",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp),
                    )
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

                IconButton(onClick = onSpeedClick) {
                    val currentSpeed by playerState.playbackSpeed.collectAsState()
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Speed,
                            contentDescription = "Speed",
                            tint = Color.White,
                            modifier = Modifier.size(24.dp),
                        )
                        if (currentSpeed != 1.0f) {
                            Text(
                                text = "${currentSpeed}x",
                                color = Color(0xFF9151FF),
                                fontSize = 10.sp,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                modifier = Modifier.offset(y = 12.dp),
                            )
                        }
                    }
                }
                IconButton(onClick = onSubtitlesClick) {
                    val subTracks by playerState.subtitleTracks.collectAsState()
                    val activeSub = subTracks.firstOrNull { it.isSelected }
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Subtitles,
                            contentDescription = "Subtitles",
                            tint = Color.White,
                            modifier = Modifier.size(24.dp),
                        )
                        if (activeSub != null) {
                            val langShort = activeSub.name.take(2).uppercase()
                            Text(
                                text = langShort,
                                color = Color(0xFF9151FF),
                                fontSize = 10.sp,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                modifier = Modifier.offset(y = 12.dp).background(Color.Black.copy(alpha = 0.5f)),
                            )
                        }
                    }
                }
                IconButton(onClick = onAudioClick) {
                    Icon(
                        imageVector = Icons.Default.Audiotrack,
                        contentDescription = "Audio",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp),
                    )
                }
                IconButton(onClick = onAspectRatioClick) {
                    Icon(
                        imageVector = Icons.Default.AspectRatio,
                        contentDescription = "Aspect Ratio",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp),
                    )
                }
                IconButton(onClick = { playerState.showStats.value = !playerState.showStats.value }) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = "Stats",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp),
                    )
                }
                IconButton(onClick = onEpisodesClick) {
                    Icon(
                        imageVector = Icons.Default.List,
                        contentDescription = "Episodes",
                        tint = Color.White,
                        modifier = Modifier.size(28.dp),
                    )
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

private fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return String.format("%02d:%02d:%02d", hours, minutes, seconds)
}
