package com.lagradost.cloudstream3.desktop.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun SettingsAppearance() {
    val themeAccent by AppearanceConfig.themeAccent.collectAsState()
    val amoledMode by AppearanceConfig.amoledMode.collectAsState()
    val isLightMode by AppearanceConfig.isLightMode.collectAsState()
    val gridScale by AppearanceConfig.gridScale.collectAsState()

    val accentColors = listOf(
        "Purple" to Color(0xFF7C6BFF),
        "Blue" to Color(0xFF3B82F6),
        "Green" to Color(0xFF10B981),
        "Red" to Color(0xFFEF4444),
        "Orange" to Color(0xFFF59E0B),
    )

    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        // Theme Color Picker
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Theme Color", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    accentColors.forEach { (name, color) ->
                        val isSelected = themeAccent == name
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(color)
                                .clickable { AppearanceConfig.setThemeAccent(name) },
                        ) {
                            if (isSelected) {
                                Box(
                                    modifier = Modifier
                                        .size(16.dp)
                                        .clip(CircleShape)
                                        .background(Color.White)
                                        .align(Alignment.Center),
                                )
                            }
                        }
                    }
                }
            }
        }

        // Light Theme Toggle
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text("Light Theme", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                    Text("Use a bright white interface", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = isLightMode,
                    onCheckedChange = {
                        AppearanceConfig.setLightMode(it)
                        if (it) AppearanceConfig.setAmoledMode(false)
                    },
                    colors = SwitchDefaults.colors(checkedThumbColor = MaterialTheme.colorScheme.primary, checkedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
                )
            }
        }

        // AMOLED Toggle
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text("AMOLED Mode", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                    Text("Pure black background for OLED screens", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = amoledMode,
                    onCheckedChange = {
                        AppearanceConfig.setAmoledMode(it)
                        if (it) AppearanceConfig.setLightMode(false)
                    },
                    colors = SwitchDefaults.colors(checkedThumbColor = MaterialTheme.colorScheme.primary, checkedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
                )
            }
        }

        // Layout Width
        val layoutWidth by AppearanceConfig.layoutWidth.collectAsState()
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Layout Width", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                Text("Restrict maximum content width on large monitors", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(16.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    listOf("Fluid" to "Edge-to-Edge", "Modern" to "Centered", "Compact" to "Narrow").forEach { (id, label) ->
                        FilterChip(
                            selected = layoutWidth == id,
                            onClick = { AppearanceConfig.setLayoutWidth(id) },
                            label = { Text(label) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                selectedLabelColor = Color.White,
                            ),
                        )
                    }
                }
            }
        }

        // Search Bar Mode
        val searchBarMode by AppearanceConfig.searchBarMode.collectAsState()
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Search Bar Visibility", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                Text("Control how the search bar appears on the home screen", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(16.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    listOf("Always Visible" to "Always Visible", "Auto-hide" to "Auto-hide").forEach { (id, label) ->
                        FilterChip(
                            selected = searchBarMode == id,
                            onClick = { AppearanceConfig.setSearchBarMode(id) },
                            label = { Text(label) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                selectedLabelColor = Color.White,
                            ),
                        )
                    }
                }
            }
        }
    }
}
