package com.lagradost.cloudstream3.desktop.player.vlcj2

import com.lagradost.common.logging.AppLogger
import java.io.File

/**
 * Discovers VLC native libraries bundled in app resources.
 *
 * Structure expected (created by ir.mahozad.vlc-setup plugin):
 *   appResources/windows/vlc/libvlc.dll
 *   appResources/windows/vlc/libvlccore.dll
 *   appResources/windows/vlc/plugins/...
 *
 * Must be called once before creating MediaPlayerFactory.
 */
object BundledVlcDiscovery {

    private const val TAG = "BundledVlcDiscovery"

    /** Tracks whether discovery has been attempted. */
    private var discovered = false

    /**
     * Attempts to find bundled VLC and sets system properties so JNA
     * and libvlc can find their native libraries.
     *
     * @return true if VLC was found, false otherwise
     */
    fun discover(): Boolean {
        if (discovered) return true

        val vlcDir = resolveVlcDirectory() ?: run {
            AppLogger.w("$TAG — VLC directory not found in app resources")
            return false
        }

        AppLogger.i("$TAG — Found bundled VLC at: ${vlcDir.absolutePath}")

        // Set JNA path so libvlc.dll is loadable
        val jnaPath = System.getProperty("jna.library.path")
        val mergedPath = if (jnaPath != null) "$jnaPath${File.pathSeparator}${vlcDir.absolutePath}"
            else vlcDir.absolutePath
        System.setProperty("jna.library.path", mergedPath)

        // Set VLC plugin path
        val pluginsDir = File(vlcDir, "plugins")
        if (pluginsDir.isDirectory) {
            System.setProperty("VLC_PLUGIN_PATH", pluginsDir.absolutePath)
            AppLogger.i("$TAG — VLC_PLUGIN_PATH = ${pluginsDir.absolutePath}")
        }

        discovered = true
        return true
    }

    private fun resolveVlcDirectory(): File? {
        val resourceDir = System.getProperty("compose.application.resources.dir")
        val candidates = mutableListOf<File>()

        // Bundled app structure
        candidates.add(File("appResources/vlc"))
        candidates.add(File("appResources/windows/vlc"))
        candidates.add(File("desktop-app/appResources/vlc"))
        candidates.add(File("desktop-app/appResources/windows/vlc"))
        if (resourceDir != null) {
            candidates.add(File(resourceDir, "vlc"))
            candidates.add(File(resourceDir, "windows/vlc"))
        }

        for (dir in candidates) {
            if (dir.isDirectory) {
                val libPath = File(dir, "libvlc.dll")
                if (libPath.isFile) {
                    return dir
                }
            }
        }

        return null
    }
}
