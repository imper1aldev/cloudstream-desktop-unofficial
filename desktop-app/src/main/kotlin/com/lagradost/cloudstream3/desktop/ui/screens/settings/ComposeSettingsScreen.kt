package com.lagradost.cloudstream3.desktop.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream3.desktop.ui.navigation.NavController

enum class SettingsTab(val title: String) {
    PLAYER("Player"),
    APPEARANCE("Appearance"),
    NETWORK("Network"),
    ADVANCED("Advanced"),
    ABOUT("About"),
}

@Composable
fun ComposeSettingsScreen(navController: NavController) {
    var selectedTab by remember { mutableStateOf(SettingsTab.PLAYER) }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .widthIn(max = 800.dp)
                .fillMaxHeight()
                .padding(24.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            Text("Settings", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(24.dp))

            ScrollableTabRow(
                selectedTabIndex = selectedTab.ordinal,
                containerColor = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.primary,
                edgePadding = 0.dp,
                divider = {},
                indicator = { tabPositions ->
                    if (selectedTab.ordinal < tabPositions.size) {
                        TabRowDefaults.SecondaryIndicator(
                            Modifier.tabIndicatorOffset(tabPositions[selectedTab.ordinal]),
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                },
            ) {
                SettingsTab.values().forEach { tab ->
                    Tab(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        text = {
                            Text(
                                tab.title,
                                color = if (selectedTab == tab) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = if (selectedTab == tab) FontWeight.Bold else FontWeight.Normal,
                            )
                        },
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Box(modifier = Modifier.fillMaxSize()) {
                androidx.compose.animation.AnimatedContent(
                    targetState = selectedTab,
                    transitionSpec = {
                        val duration = 250
                        if (targetState.ordinal > initialState.ordinal) {
                            androidx.compose.animation.slideInHorizontally(
                                animationSpec = androidx.compose.animation.core.tween(duration),
                                initialOffsetX = { it / 4 }
                            ) + androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(duration)) togetherWith
                            androidx.compose.animation.slideOutHorizontally(
                                animationSpec = androidx.compose.animation.core.tween(duration),
                                targetOffsetX = { -it / 4 }
                            ) + androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(duration))
                        } else {
                            androidx.compose.animation.slideInHorizontally(
                                animationSpec = androidx.compose.animation.core.tween(duration),
                                initialOffsetX = { -it / 4 }
                            ) + androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(duration)) togetherWith
                            androidx.compose.animation.slideOutHorizontally(
                                animationSpec = androidx.compose.animation.core.tween(duration),
                                targetOffsetX = { it / 4 }
                            ) + androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(duration))
                        }
                    },
                    label = "settings_tab_transition"
                ) { tab ->
                    when (tab) {
                        SettingsTab.PLAYER -> SettingsPlayer()
                        SettingsTab.APPEARANCE -> SettingsAppearance()
                        SettingsTab.NETWORK -> SettingsNetwork()
                        SettingsTab.ADVANCED -> SettingsAdvanced()
                        SettingsTab.ABOUT -> SettingsAbout()
                    }
                }
            }
        }
    }
}

