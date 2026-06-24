@file:OptIn(com.lagradost.cloudstream3.Prerelease::class, com.lagradost.cloudstream3.UnsafeSSL::class)

package com.lagradost.cloudstream3.desktop

import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import coil3.compose.setSingletonImageLoaderFactory
import coil3.request.crossfade
import com.lagradost.cloudstream3.desktop.init.initNetwork
import com.lagradost.cloudstream3.desktop.init.initPlugins
import com.lagradost.cloudstream3.desktop.init.initProviders
import com.lagradost.cloudstream3.desktop.init.initProxy
import com.lagradost.cloudstream3.desktop.init.initSecurity
import com.lagradost.cloudstream3.desktop.init.launchAutoUpdater
import com.lagradost.cloudstream3.desktop.ui.CloudstreamApp
import com.lagradost.cloudstream3.desktop.ui.FullscreenController
import com.lagradost.cloudstream3.desktop.ui.LocalFullscreenController
import com.lagradost.common.logging.AppLogger
import com.lagradost.common.platform.PlatformPaths
import okio.Path.Companion.toOkioPath
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * Single unified entry point for CloudStream Desktop Client.
 */
fun main() {
    System.setProperty("compose.layers.type", "WINDOW")
    System.setProperty("sun.awt.noerasebackground", "true")
    System.setProperty("sun.java2d.d3d", "false")
    System.setProperty("sun.java2d.noddraw", "true")

    val black = java.awt.Color.BLACK
    javax.swing.UIManager.put("Window.background", black)
    javax.swing.UIManager.put("Canvas.background", black)

    AppLogger.i("Launching CloudStream Desktop Client...")
    AppLogger.i("Platform: ${PlatformPaths.currentOS}")
    AppLogger.i("App data directory: ${PlatformPaths.appDataDir.absolutePath}")

    initProxy()
    initSecurity()
    initNetwork()
    initProviders()
    initPlugins()
    com.lagradost.cloudstream3.APIHolder.initAll()
    launchAutoUpdater()

    application {
        setSingletonImageLoaderFactory { context ->
            coil3.ImageLoader.Builder(context)
                .memoryCache {
                    coil3.memory.MemoryCache.Builder()
                        .maxSizePercent(context, 0.25)
                        .build()
                }
                .diskCache {
                    coil3.disk.DiskCache.Builder()
                        .directory(File(PlatformPaths.appDataDir, "image_cache").also { it.mkdirs() }.toOkioPath())
                        .maxSizeBytes(512L * 1024 * 1024)
                        .build()
                }
                .components {
                    add(
                        coil3.network.okhttp.OkHttpNetworkFetcherFactory(
                            callFactory = { request ->
                                com.lagradost.cloudstream3.app.baseClient.newCall(request)
                            },
                        ),
                    )
                }
                .crossfade(true)
                .build()
        }

        val screenSize = java.awt.Toolkit.getDefaultToolkit().screenSize
        val windowWidth = (screenSize.width * 0.7).toInt().coerceAtLeast(1000).dp
        val windowHeight = (screenSize.height * 0.7).toInt().coerceAtLeast(700).dp
        val state = androidx.compose.ui.window.rememberWindowState(
            width = windowWidth,
            height = windowHeight,
            position = androidx.compose.ui.window.WindowPosition.Aligned(androidx.compose.ui.Alignment.Center),
        )

        val isFullscreenState = androidx.compose.runtime.mutableStateOf(false)
        val windowRef = AtomicReference<java.awt.Window?>(null)

        fun toggleFullscreen() {
            val w = windowRef.get() as? javax.swing.JFrame ?: return
            if (isFullscreenState.value) {
                exitWindowsFullscreen(w)
                isFullscreenState.value = false
            } else {
                enterWindowsFullscreen(w)
                isFullscreenState.value = true
            }
        }

        Window(
            onCloseRequest = ::exitApplication,
            title = "CloudStream - Unofficial Desktop Client (Pre-Alpha)",
            state = state,
            icon = androidx.compose.ui.res.painterResource("logo_ui.png"),
            onKeyEvent = { keyEvent ->
                if (keyEvent.key == Key.F11 && keyEvent.type == KeyEventType.KeyDown) {
                    toggleFullscreen()
                    true
                } else if (keyEvent.key == Key.Escape && keyEvent.type == KeyEventType.KeyDown && isFullscreenState.value) {
                    toggleFullscreen()
                    true
                } else {
                    false
                }
            }
        ) {
            windowRef.set(window)
            window.minimumSize = java.awt.Dimension(1000, 700)

            androidx.compose.runtime.LaunchedEffect(Unit) {
                setWindowsDarkMode(window)
            }

            // Exit fullscreen cleanly when window closes
            androidx.compose.runtime.DisposableEffect(Unit) {
                onDispose {
                    val w = window as? javax.swing.JFrame
                    if (w != null && isFullscreenState.value) {
                        exitWindowsFullscreen(w)
                    }
                }
            }

            val fullscreenController = FullscreenController(
                isFullscreen = isFullscreenState,
                toggle = ::toggleFullscreen,
            )

            androidx.compose.runtime.CompositionLocalProvider(
                com.lagradost.cloudstream3.desktop.ui.LocalWindowState provides state,
                LocalFullscreenController provides fullscreenController,
            ) {
                CloudstreamApp()
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Windows Borderless Fullscreen via Win32 API
//
// Why not GraphicsDevice.setFullScreenWindow()?
//   On Windows 10/11 with DWM, AWT's exclusive fullscreen does NOT strip the
//   OS title bar — DWM renders it in a separate layer above the app. The only
//   reliable cross-app way to go truly fullscreen is to strip the WS_CAPTION /
//   WS_THICKFRAME window styles at the Win32 level via SetWindowLongPtr, then
//   position the window to cover the entire monitor. This is identical to what
//   VLC, MPV, and most native Windows apps do for "borderless fullscreen".
// ──────────────────────────────────────────────────────────────────────────────

private interface User32Ext : com.sun.jna.Library {
    companion object {
        val INSTANCE: User32Ext = com.sun.jna.Native.load("user32", User32Ext::class.java)

        // GetWindowLong / SetWindowLong index for window style
        const val GWL_STYLE = -16

        // Style bits that constitute the title bar and resize border
        const val WS_CAPTION = 0x00C00000     // Title bar (includes WS_BORDER)
        const val WS_THICKFRAME = 0x00040000  // Sizing border
        const val WS_MINIMIZEBOX = 0x00020000
        const val WS_MAXIMIZEBOX = 0x00010000
        const val WS_SYSMENU = 0x00080000

        // SetWindowPos flags
        const val SWP_FRAMECHANGED = 0x0020   // Forces WM_NCCALCSIZE, applies new style
        const val SWP_NOACTIVATE = 0x0010
        const val SWP_SHOWWINDOW = 0x0040
        const val HWND_TOP = 0                // Z-order: place at top
    }

    fun GetWindowLongA(hwnd: com.sun.jna.Pointer, nIndex: Int): Int
    fun SetWindowLongA(hwnd: com.sun.jna.Pointer, nIndex: Int, dwNewLong: Int): Int
    fun SetWindowPos(
        hwnd: com.sun.jna.Pointer,
        hwndInsertAfter: Int,
        x: Int, y: Int, cx: Int, cy: Int,
        uFlags: Int,
    ): Boolean
    fun GetSystemMetrics(nIndex: Int): Int
    fun MonitorFromWindow(hwnd: com.sun.jna.Pointer, dwFlags: Int): com.sun.jna.Pointer?
}

private interface ShcoreLib : com.sun.jna.Library {
    companion object {
        val INSTANCE: ShcoreLib = com.sun.jna.Native.load("shcore", ShcoreLib::class.java)
    }
    fun GetDpiForMonitor(hmonitor: com.sun.jna.Pointer, dpiType: Int, dpiX: com.sun.jna.ptr.IntByReference, dpiY: com.sun.jna.ptr.IntByReference): Int
}

// Saved windowed-mode state so we can restore it on fullscreen exit
private data class WindowedState(
    val style: Int,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)
private var savedWindowedState: WindowedState? = null

private fun enterWindowsFullscreen(frame: javax.swing.JFrame) {
    if (!System.getProperty("os.name", "").lowercase().contains("win")) {
        // Non-Windows fallback: use AWT exclusive fullscreen
        val gd = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice
        gd.fullScreenWindow = frame
        return
    }
    try {
        val hwnd = com.sun.jna.Pointer(com.sun.jna.Native.getComponentID(frame))
        val u32 = User32Ext.INSTANCE

        // Save current style and bounds so we can restore later
        val currentStyle = u32.GetWindowLongA(hwnd, User32Ext.GWL_STYLE)
        val bounds = frame.bounds
        savedWindowedState = WindowedState(
            style = currentStyle,
            x = bounds.x,
            y = bounds.y,
            width = bounds.width,
            height = bounds.height,
        )

        // Strip title bar and sizing border from the window style
        val DECORATION_MASK = User32Ext.WS_CAPTION or
                User32Ext.WS_THICKFRAME or
                User32Ext.WS_MINIMIZEBOX or
                User32Ext.WS_MAXIMIZEBOX or
                User32Ext.WS_SYSMENU
        val newStyle = currentStyle and DECORATION_MASK.inv()
        u32.SetWindowLongA(hwnd, User32Ext.GWL_STYLE, newStyle)

        // Find the monitor this window is on and cover it entirely
        val screen = frame.graphicsConfiguration.bounds
        u32.SetWindowPos(
            hwnd,
            User32Ext.HWND_TOP,
            screen.x, screen.y,
            screen.width, screen.height,
            User32Ext.SWP_FRAMECHANGED or User32Ext.SWP_NOACTIVATE or User32Ext.SWP_SHOWWINDOW,
        )

        AppLogger.i("Entered borderless fullscreen: ${screen.width}x${screen.height} at (${screen.x},${screen.y})")
    } catch (e: Exception) {
        AppLogger.e("enterWindowsFullscreen failed: ${e.message}")
        e.printStackTrace()
        // Fallback to AWT exclusive fullscreen
        runCatching {
            java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.fullScreenWindow = frame
        }
    }
}

private fun exitWindowsFullscreen(frame: javax.swing.JFrame) {
    if (!System.getProperty("os.name", "").lowercase().contains("win")) {
        java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.fullScreenWindow = null
        return
    }
    try {
        val hwnd = com.sun.jna.Pointer(com.sun.jna.Native.getComponentID(frame))
        val u32 = User32Ext.INSTANCE
        val saved = savedWindowedState

        if (saved != null) {
            // Restore the original window style (title bar + borders)
            u32.SetWindowLongA(hwnd, User32Ext.GWL_STYLE, saved.style)
            u32.SetWindowPos(
                hwnd,
                User32Ext.HWND_TOP,
                saved.x, saved.y,
                saved.width, saved.height,
                User32Ext.SWP_FRAMECHANGED or User32Ext.SWP_NOACTIVATE or User32Ext.SWP_SHOWWINDOW,
            )
            savedWindowedState = null
            AppLogger.i("Exited borderless fullscreen, restored to ${saved.width}x${saved.height}")
        } else {
            // No saved state — just restore style and re-center
            val defaultStyle = (0x00CF0000).toInt() // WS_OVERLAPPEDWINDOW
            u32.SetWindowLongA(hwnd, User32Ext.GWL_STYLE, defaultStyle)
            val screen = frame.graphicsConfiguration.bounds
            val w = (screen.width * 0.7).toInt()
            val h = (screen.height * 0.7).toInt()
            u32.SetWindowPos(
                hwnd,
                User32Ext.HWND_TOP,
                screen.x + (screen.width - w) / 2,
                screen.y + (screen.height - h) / 2,
                w, h,
                User32Ext.SWP_FRAMECHANGED or User32Ext.SWP_NOACTIVATE or User32Ext.SWP_SHOWWINDOW,
            )
        }
    } catch (e: Exception) {
        AppLogger.e("exitWindowsFullscreen failed: ${e.message}")
        e.printStackTrace()
        runCatching {
            java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.fullScreenWindow = null
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// DWM Dark mode title bar
// ──────────────────────────────────────────────────────────────────────────────

private interface Dwmapi : com.sun.jna.Library {
    companion object {
        val INSTANCE = com.sun.jna.Native.load("dwmapi", Dwmapi::class.java) as Dwmapi
    }
    fun DwmSetWindowAttribute(hwnd: com.sun.jna.Pointer, dwAttribute: Int, pvAttribute: com.sun.jna.ptr.IntByReference, cbAttribute: Int): Int
}

private fun setWindowsDarkMode(window: java.awt.Window) {
    if (!System.getProperty("os.name").lowercase().contains("win")) return
    try {
        val hwnd = com.sun.jna.Pointer(com.sun.jna.Native.getComponentID(window))
        val trueValue = com.sun.jna.ptr.IntByReference(1)
        val result = Dwmapi.INSTANCE.DwmSetWindowAttribute(hwnd, 20, trueValue, 4)
        if (result != 0) {
            Dwmapi.INSTANCE.DwmSetWindowAttribute(hwnd, 19, trueValue, 4)
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
}
