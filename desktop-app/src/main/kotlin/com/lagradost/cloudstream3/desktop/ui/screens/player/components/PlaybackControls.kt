package com.lagradost.cloudstream3.desktop.ui.screens.player.components

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
import com.lagradost.cloudstream3.desktop.ui.screens.player.PlayerState

@Composable
fun PlaybackControls(
    playerState: PlayerState,
    modifier: Modifier = Modifier,
    onSkipPrevious: (() -> Unit)? = null,
    onSkipNext: (() -> Unit)? = null,
) {
    val isPaused by playerState.isPaused.collectAsState()
    val isBuffering by playerState.isBuffering.collectAsState()

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onSkipPrevious != null) {
            IconButton(
                onClick = onSkipPrevious,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(Icons.Default.SkipPrevious, contentDescription = "Previous Episode", tint = Color.White)
            }
            Spacer(modifier = Modifier.width(16.dp))
        }

        // Skip backward 10s
        IconButton(
            onClick = { playerState.seekBy(-10000L) },
            modifier = Modifier.size(48.dp),
        ) {
            Icon(Icons.Default.Replay10, contentDescription = "Skip back 10s", tint = Color.White)
        }

        Spacer(modifier = Modifier.width(32.dp))

        // Play / Pause
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
            modifier = Modifier.size(64.dp),
            onClick = { playerState.togglePlayPause() },
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (isBuffering) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(32.dp))
                } else {
                    Icon(
                        imageVector = if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                        contentDescription = if (isPaused) "Play" else "Pause",
                        tint = Color.White,
                        modifier = Modifier.size(32.dp),
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(32.dp))

        // Skip forward 10s
        IconButton(
            onClick = { playerState.seekBy(10000L) },
            modifier = Modifier.size(48.dp),
        ) {
            Icon(Icons.Default.Forward10, contentDescription = "Skip forward 10s", tint = Color.White)
        }

        if (onSkipNext != null) {
            Spacer(modifier = Modifier.width(16.dp))
            IconButton(
                onClick = onSkipNext,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(Icons.Default.SkipNext, contentDescription = "Next Episode", tint = Color.White)
            }
        }
    }
}
