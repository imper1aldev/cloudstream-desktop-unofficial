package com.lagradost.cloudstream3.desktop.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.desktop.ui.screens.ComposeDetailsScreen
import com.lagradost.cloudstream3.desktop.ui.screens.ComposeExtensionScreen
import com.lagradost.cloudstream3.desktop.ui.screens.ComposeHomeScreen
import com.lagradost.cloudstream3.desktop.ui.screens.ComposeLibraryScreen
import com.lagradost.cloudstream3.desktop.ui.screens.ComposeLinksScreen
import com.lagradost.common.storage.WatchHistory

sealed class Screen {
    object Home : Screen()
    object Extensions : Screen()
    object Library : Screen()
    object Settings : Screen()
    data class Details(val provider: MainAPI, val url: String, val preloadedName: String? = null, val preloadedPoster: String? = null, val preloadedBg: String? = null) : Screen()
    data class Links(val provider: MainAPI, val dataUrl: String, val history: WatchHistory) : Screen()
    data class CategoryGrid(val provider: MainAPI, val title: String, val items: List<com.lagradost.cloudstream3.SearchResponse>) : Screen()
}

data class VideoLaunchData(
    val links: List<com.lagradost.cloudstream3.utils.ExtractorLink>,
    val initialIndex: Int,
    val title: String?,
    val subtitles: List<com.lagradost.cloudstream3.SubtitleFile>,
    val startPositionMs: Long,
    val history: WatchHistory,
    val onError: ((String) -> Unit)? = null,
    val onClosed: (() -> Unit)? = null,
)

val LocalVideoPlayer = androidx.compose.runtime.staticCompositionLocalOf<(VideoLaunchData?) -> Unit> { { } }
val LocalWindowState = androidx.compose.runtime.staticCompositionLocalOf<androidx.compose.ui.window.WindowState?> { null }

class NavController {
    var currentScreen: Screen by mutableStateOf(Screen.Home)

    fun navigate(screen: Screen) {
        currentScreen = screen
    }

    fun goBack() {
        currentScreen = when (currentScreen) {
            is Screen.Details, is Screen.Links -> Screen.Home
            else -> Screen.Home
        }
    }
}

@Composable
fun CloudstreamApp() {
    val navController = remember { NavController() }
    var showErrorsDialog by remember { mutableStateOf(false) }
    var currentVideo by remember { mutableStateOf<VideoLaunchData?>(null) }
    val screen = navController.currentScreen

    val isLightMode by com.lagradost.cloudstream3.desktop.ui.screens.settings.AppearanceConfig.isLightMode.collectAsState()
    val themeAccent by com.lagradost.cloudstream3.desktop.ui.screens.settings.AppearanceConfig.themeAccent.collectAsState()
    val amoledMode by com.lagradost.cloudstream3.desktop.ui.screens.settings.AppearanceConfig.amoledMode.collectAsState()
    val primaryColor = when (themeAccent) {
        "Blue" -> androidx.compose.ui.graphics.Color(0xFF3B82F6)
        "Green" -> androidx.compose.ui.graphics.Color(0xFF10B981)
        "Red" -> androidx.compose.ui.graphics.Color(0xFFEF4444)
        "Orange" -> androidx.compose.ui.graphics.Color(0xFFF59E0B)
        else -> androidx.compose.ui.graphics.Color(0xFF7C6BFF) // Purple
    }

    val desktopColors = if (isLightMode) {
        com.lagradost.cloudstream3.desktop.ui.components.lightDesktopColors(primaryColor)
    } else {
        com.lagradost.cloudstream3.desktop.ui.components.darkDesktopColors(primaryColor, amoledMode)
    }

    androidx.compose.runtime.CompositionLocalProvider(
        LocalVideoPlayer provides { currentVideo = it },
        com.lagradost.cloudstream3.desktop.ui.components.LocalDesktopTheme provides desktopColors,
    ) {
        androidx.compose.material3.Surface(
            modifier = androidx.compose.ui.Modifier.fillMaxSize(),
            color = if (isLightMode) androidx.compose.ui.graphics.Color(0xFFF3F4F6) else androidx.compose.ui.graphics.Color(0xFF0D0D14), // Fix flash on transition
        ) {
            androidx.compose.foundation.layout.Box(modifier = androidx.compose.ui.Modifier.fillMaxSize()) {
                androidx.compose.animation.Crossfade(
                    targetState = screen,
                    animationSpec = androidx.compose.animation.core.tween(300),
                ) { targetScreen ->
                    when (targetScreen) {
                        is Screen.Details -> ComposeDetailsScreen(navController, targetScreen.provider, targetScreen.url, targetScreen.preloadedName, targetScreen.preloadedPoster, targetScreen.preloadedBg)
                        is Screen.Links -> ComposeLinksScreen(navController, targetScreen.provider, targetScreen.dataUrl, targetScreen.history)
                        is Screen.Home -> DesktopAppShell(
                            navController = navController,
                            title = "Home",
                            onErrorLogs = { showErrorsDialog = true },
                        ) {
                            ComposeHomeScreen(
                                navController = navController,
                                showErrorsDialog = showErrorsDialog,
                                onDismissErrors = { showErrorsDialog = false },
                            )
                        }
                        is Screen.Extensions -> DesktopAppShell(
                            navController = navController,
                            title = "Extensions",
                            onErrorLogs = { showErrorsDialog = true },
                        ) {
                            ComposeExtensionScreen(navController)
                        }
                        is Screen.Library -> DesktopAppShell(
                            navController = navController,
                            title = "Library",
                            onErrorLogs = { showErrorsDialog = true },
                        ) {
                            ComposeLibraryScreen(navController)
                        }
                        is Screen.Settings -> DesktopAppShell(
                            navController = navController,
                            title = "Settings",
                            onErrorLogs = { showErrorsDialog = true },
                        ) {
                            com.lagradost.cloudstream3.desktop.ui.screens.settings.ComposeSettingsScreen(navController)
                        }
                        is Screen.CategoryGrid -> DesktopAppShell(
                            navController = navController,
                            title = targetScreen.title,
                            showBack = true,
                            onErrorLogs = { showErrorsDialog = true },
                        ) {
                            com.lagradost.cloudstream3.desktop.ui.screens.ComposeCategoryGridScreen(navController, targetScreen.provider, targetScreen.title, targetScreen.items)
                        }
                    }
                }

                // The Embedded Video Player Overlay
                if (currentVideo != null) {
                    com.lagradost.cloudstream3.desktop.ui.screens.player.EmbeddedVideoPlayer(
                        launchData = currentVideo!!,
                        onClose = { currentVideo = null },
                    )
                }
            }
        }
    }
}
