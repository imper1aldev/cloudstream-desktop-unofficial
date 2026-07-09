package com.lagradost.cloudstream3.desktop.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream3.desktop.player.PlayerConfig
import com.lagradost.common.storage.DesktopDataStore

@Composable
fun SettingsPlayer() {
    var autoPlay by remember { mutableStateOf(DesktopDataStore.getKey<Boolean>(PlayerConfig.PREF_AUTO_PLAY) ?: true) }
    var autoPlayTimeout by remember { mutableStateOf(DesktopDataStore.getKey<String>(PlayerConfig.PREF_AUTO_PLAY_TIMEOUT) ?: "15000") }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Video Player Options", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(16.dp))

            Text("Internal Video Player (VLCJ)", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            Text("Uses embedded libVLC for in-app playback.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(16.dp))
            Text("Auto Play Fallback", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(8.dp))

            // Auto Play Toggle
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Auto Play next link on failure", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                Switch(
                    checked = autoPlay,
                    onCheckedChange = {
                        autoPlay = it
                        DesktopDataStore.setKey(PlayerConfig.PREF_AUTO_PLAY, it)
                    },
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Auto Play Timeout
            PlayerDropdownSetting(
                label = "Playback Timeout",
                options = listOf(
                    "10000" to "10 Seconds",
                    "15000" to "15 Seconds",
                    "30000" to "30 Seconds",
                    "60000" to "60 Seconds",
                ),
                currentValue = autoPlayTimeout,
                onSelectionChanged = {
                    autoPlayTimeout = it
                    DesktopDataStore.setKey(PlayerConfig.PREF_AUTO_PLAY_TIMEOUT, it)
                },
            )

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(16.dp))
            Text("Autoplay", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(8.dp))

            // Autoplay Next Episode toggle
            var autoPlayNext by remember { mutableStateOf(
                DesktopDataStore.getKey<Boolean>(PlayerConfig.PREF_AUTO_PLAY_NEXT) ?: true
            ) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Play next episode automatically",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = autoPlayNext,
                    onCheckedChange = {
                        autoPlayNext = it
                        DesktopDataStore.setKey(PlayerConfig.PREF_AUTO_PLAY_NEXT, it)
                    },
                )
            }

        }
    }
}

@Composable
fun PlayerDropdownSetting(
    label: String,
    options: List<Pair<String, String>>,
    currentValue: String,
    onSelectionChanged: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))

        Box {
            FilledTonalButton(onClick = { expanded = true }) {
                Text(options.find { it.first == currentValue }?.second ?: currentValue)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { (value, title) ->
                    DropdownMenuItem(
                        text = { Text(title) },
                        onClick = {
                            onSelectionChanged(value)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

