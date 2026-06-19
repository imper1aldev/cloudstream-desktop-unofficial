package com.lagradost.cloudstream3.desktop.ui

import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.lagradost.cloudstream3.desktop.ui.navigation.NavController
import com.lagradost.cloudstream3.desktop.ui.navigation.Screen
import com.lagradost.cloudstream3.desktop.ui.screens.ComposeDetailsScreen
import com.lagradost.cloudstream3.desktop.ui.screens.ComposeExtensionScreen
import com.lagradost.cloudstream3.desktop.ui.screens.ComposeHomeScreen
import com.lagradost.cloudstream3.desktop.ui.screens.ComposeLibraryScreen
import com.lagradost.common.storage.WatchHistory

data class VideoLaunchData(
    val links: List<com.lagradost.cloudstream3.utils.ExtractorLink> = emptyList(),
    val initialIndex: Int,
    val title: String?,
    val subtitles: List<com.lagradost.cloudstream3.SubtitleFile>,
    val startPositionMs: Long,
    val history: WatchHistory,
    val loadResponse: com.lagradost.cloudstream3.LoadResponse? = null,
    val onError: ((String) -> Unit)? = null,
    val onClosed: (() -> Unit)? = null,
)

val LocalVideoPlayer = androidx.compose.runtime.staticCompositionLocalOf<(VideoLaunchData?) -> Unit> { { } }
val LocalWindowState = androidx.compose.runtime.staticCompositionLocalOf<androidx.compose.ui.window.WindowState?> { null }
val LocalComposeWindow = androidx.compose.runtime.staticCompositionLocalOf<java.awt.Window?> { null }

@Composable
fun CloudstreamApp() {
    val navController = remember { NavController() }
    var showErrorsDialog by remember { mutableStateOf(false) }
    var currentVideo by remember { mutableStateOf<VideoLaunchData?>(null) }
    val screen = navController.currentScreen

    val isLightMode by com.lagradost.cloudstream3.desktop.ui.theme.AppearanceConfig.isLightMode.collectAsState()
    val themeAccent by com.lagradost.cloudstream3.desktop.ui.theme.AppearanceConfig.themeAccent.collectAsState()
    val amoledMode by com.lagradost.cloudstream3.desktop.ui.theme.AppearanceConfig.amoledMode.collectAsState()
    val primaryColor = com.lagradost.cloudstream3.desktop.ui.theme.accentColorFromName(themeAccent)
    val desktopColors = com.lagradost.cloudstream3.desktop.ui.theme.buildDesktopColors(primaryColor, isLightMode, amoledMode)

    androidx.compose.runtime.CompositionLocalProvider(
        LocalVideoPlayer provides { currentVideo = it },
        com.lagradost.cloudstream3.desktop.ui.components.LocalDesktopTheme provides desktopColors,
    ) {
        val appColorScheme = com.lagradost.cloudstream3.desktop.ui.theme.buildColorScheme(primaryColor, desktopColors, isLightMode)

        androidx.compose.material3.MaterialTheme(colorScheme = appColorScheme) {
            androidx.compose.material3.Surface(
                modifier = androidx.compose.ui.Modifier.fillMaxSize(),
                color = androidx.compose.material3.MaterialTheme.colorScheme.background,
            ) {
                androidx.compose.foundation.layout.Box(modifier = androidx.compose.ui.Modifier.fillMaxSize()) {
                    val saveableStateHolder = androidx.compose.runtime.saveable.rememberSaveableStateHolder()

                    androidx.compose.animation.AnimatedContent(
                        targetState = screen,
                        modifier = androidx.compose.ui.Modifier.fillMaxSize(),
                        contentAlignment = androidx.compose.ui.Alignment.TopStart,
                        transitionSpec = {
                            val isPush = targetState is Screen.Details || targetState is Screen.CategoryGrid
                            val isPop = initialState is Screen.Details || initialState is Screen.CategoryGrid

                            if (isPush) {
                                (
                                    androidx.compose.animation.slideInHorizontally(
                                        initialOffsetX = { it },
                                        animationSpec = androidx.compose.animation.core.tween(300),
                                    ) + androidx.compose.animation.fadeIn(animationSpec = androidx.compose.animation.core.tween(300)) togetherWith
                                        androidx.compose.animation.slideOutHorizontally(
                                            targetOffsetX = { -it / 3 },
                                            animationSpec = androidx.compose.animation.core.tween(300),
                                        ) + androidx.compose.animation.fadeOut(animationSpec = androidx.compose.animation.core.tween(300))
                                    )
                                    .using(androidx.compose.animation.SizeTransform(clip = false) { _, _ -> androidx.compose.animation.core.snap() })
                                    .apply { targetContentZIndex = 1f }
                            } else if (isPop) {
                                (
                                    androidx.compose.animation.slideInHorizontally(
                                        initialOffsetX = { -it / 3 },
                                        animationSpec = androidx.compose.animation.core.tween(300),
                                    ) + androidx.compose.animation.fadeIn(animationSpec = androidx.compose.animation.core.tween(300)) togetherWith
                                        androidx.compose.animation.slideOutHorizontally(
                                            targetOffsetX = { it },
                                            animationSpec = androidx.compose.animation.core.tween(300),
                                        ) + androidx.compose.animation.fadeOut(animationSpec = androidx.compose.animation.core.tween(300))
                                    )
                                    .using(androidx.compose.animation.SizeTransform(clip = false) { _, _ -> androidx.compose.animation.core.snap() })
                                    .apply { targetContentZIndex = -1f }
                            } else {
                                (
                                    androidx.compose.animation.fadeIn(animationSpec = androidx.compose.animation.core.tween(300)) togetherWith
                                        androidx.compose.animation.fadeOut(animationSpec = androidx.compose.animation.core.tween(300))
                                    )
                                    .using(androidx.compose.animation.SizeTransform(clip = false) { _, _ -> androidx.compose.animation.core.snap() })
                            }
                        },
                        label = "screen_transition",
                    ) { targetScreen ->
                        saveableStateHolder.SaveableStateProvider(targetScreen) {
                            when (targetScreen) {
                                is Screen.Details -> ComposeDetailsScreen(navController, targetScreen.provider, targetScreen.url, targetScreen.preloadedName, targetScreen.preloadedPoster, targetScreen.preloadedBg)

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
                    }

                    // The Embedded Video Player Overlay with smooth transitions!
                    var lastVideo by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<com.lagradost.cloudstream3.desktop.ui.VideoLaunchData?>(null) }
                    if (currentVideo != null) {
                        lastVideo = currentVideo
                    }

                    androidx.compose.animation.AnimatedVisibility(
                        visible = currentVideo != null,
                        enter = androidx.compose.animation.slideInVertically(
                            initialOffsetY = { it }, // Slide up from bottom
                            animationSpec = androidx.compose.animation.core.tween(400, easing = androidx.compose.animation.core.FastOutSlowInEasing)
                        ) + androidx.compose.animation.fadeIn(animationSpec = androidx.compose.animation.core.tween(400)),
                        exit = androidx.compose.animation.slideOutVertically(
                            targetOffsetY = { it }, // Slide down
                            animationSpec = androidx.compose.animation.core.tween(400, easing = androidx.compose.animation.core.FastOutSlowInEasing)
                        ) + androidx.compose.animation.fadeOut(animationSpec = androidx.compose.animation.core.tween(400)),
                        modifier = androidx.compose.ui.Modifier.fillMaxSize(),
                    ) {
                        lastVideo?.let { launchData ->
                            com.lagradost.cloudstream3.desktop.ui.screens.player.EmbeddedVideoPlayer(
                                launchData = launchData,
                                onClose = { currentVideo = null },
                            )
                        }
                    }
                }
            }
        }
    }
}
