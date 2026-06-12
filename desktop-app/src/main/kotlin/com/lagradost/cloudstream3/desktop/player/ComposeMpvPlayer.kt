package com.lagradost.cloudstream3.desktop.player

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import com.lagradost.cloudstream3.desktop.ui.screens.player.PlayerState
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.player.impl.PlayerLinkHandler
import kotlinx.coroutines.delay
import java.awt.Canvas
import java.awt.Color
import java.awt.event.*
import java.io.File

@Composable
fun ComposeMpvPlayer(
    link: ExtractorLink,
    title: String?,
    subtitles: List<com.lagradost.cloudstream3.SubtitleFile>,
    startPositionMs: Long,
    onPlaybackReady: () -> Unit,
    onPlaybackError: (String) -> Unit,
    onFinished: () -> Unit,
    onPositionChange: (Long, Long) -> Unit,
    onCloseRequest: () -> Unit,
    onFullscreenToggle: (() -> Unit)? = null,
    playerState: com.lagradost.cloudstream3.desktop.ui.screens.player.PlayerState? = null,
    modifier: Modifier = Modifier.fillMaxSize(),
) {
    var mpvHandle by remember { mutableStateOf<com.sun.jna.Pointer?>(null) }
    var hasEverPlayed by remember { mutableStateOf(false) }
    var loadStartTime by remember { mutableStateOf(System.currentTimeMillis()) }

    val currentOnPlaybackReady by rememberUpdatedState(onPlaybackReady)
    val currentOnPlaybackError by rememberUpdatedState(onPlaybackError)
    val currentOnFinished by rememberUpdatedState(onFinished)
    val currentOnPositionChange by rememberUpdatedState(onPositionChange)
    val currentOnCloseRequest by rememberUpdatedState(onCloseRequest)
    val currentOnFullscreenToggle by rememberUpdatedState(onFullscreenToggle)

    LaunchedEffect(mpvHandle) {
        val h = mpvHandle
        if (h != null) {
            var loops = 0
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                while (true) {
                    // Check if playback has started and track position
                    val posStr = MpvLibrary.INSTANCE.mpv_get_property_string(h, "time-pos")
                    val pos = posStr?.toDoubleOrNull()
                    val coreIdle = MpvLibrary.INSTANCE.mpv_get_property_string(h, "core-idle")

                    val durStr = MpvLibrary.INSTANCE.mpv_get_property_string(h, "duration")
                    val dur = durStr?.toDoubleOrNull()

                    // Use core-idle as a reliable indicator that video/audio is actively rendering
                    if (coreIdle == "no" || (pos != null && pos > 0.0)) {
                        if (!hasEverPlayed) {
                            hasEverPlayed = true
                            playerState?.isBuffering?.value = false
                            currentOnPlaybackReady()
                        }
                        if (dur != null && dur > 0.0 && pos != null) {
                            val posMs = (pos * 1000).toLong()
                            val durMs = (dur * 1000).toLong()
                            playerState?.positionMs?.value = posMs
                            playerState?.durationMs?.value = durMs
                            currentOnPositionChange(posMs, durMs)
                        }
                    }

                    // Check pause state
                    val pauseStr = MpvLibrary.INSTANCE.mpv_get_property_string(h, "pause")
                    playerState?.isPaused?.value = pauseStr == "yes"

                    // Check buffer state
                    val bufferStr = MpvLibrary.INSTANCE.mpv_get_property_string(h, "time-remaining")
                    val bufferSec = bufferStr?.toDoubleOrNull() ?: 0.0
                    val currentPos = pos ?: 0.0
                    val demuxerCacheStr = MpvLibrary.INSTANCE.mpv_get_property_string(h, "demuxer-cache-duration")
                    val demuxerCacheSec = demuxerCacheStr?.toDoubleOrNull() ?: 0.0
                    if (demuxerCacheSec > 0.0) {
                        playerState?.bufferMs?.value = ((currentPos + demuxerCacheSec) * 1000).toLong()
                    } else {
                        playerState?.bufferMs?.value = ((currentPos) * 1000).toLong()
                    }

                    // Check volume
                    val volStr = MpvLibrary.INSTANCE.mpv_get_property_string(h, "volume")
                    val vol = volStr?.toFloatOrNull()
                    if (vol != null) {
                        playerState?.volume?.value = vol
                    }

                    // Check speed
                    val speedStr = MpvLibrary.INSTANCE.mpv_get_property_string(h, "speed")
                    val speed = speedStr?.toFloatOrNull()
                    if (speed != null) {
                        playerState?.playbackSpeed?.value = speed
                    }

                    // Poll tracks less frequently
                    if (loops % 10 == 0) {
                        val trackCountStr = MpvLibrary.INSTANCE.mpv_get_property_string(h, "track-list/count")
                        val trackCount = trackCountStr?.toIntOrNull() ?: 0

                        val audioTracks = mutableListOf<PlayerState.VideoTrack>()
                        val subTracks = mutableListOf<PlayerState.VideoTrack>()

                        for (i in 0 until trackCount) {
                            val id = MpvLibrary.INSTANCE.mpv_get_property_string(h, "track-list/$i/id")?.toIntOrNull() ?: continue
                            val type = MpvLibrary.INSTANCE.mpv_get_property_string(h, "track-list/$i/type") ?: continue
                            val lang = MpvLibrary.INSTANCE.mpv_get_property_string(h, "track-list/$i/lang")
                            val title = MpvLibrary.INSTANCE.mpv_get_property_string(h, "track-list/$i/title")
                            val selected = MpvLibrary.INSTANCE.mpv_get_property_string(h, "track-list/$i/selected") == "yes"

                            val name = buildString {
                                if (!lang.isNullOrBlank()) append(lang.uppercase())
                                if (!title.isNullOrBlank()) {
                                    if (isNotEmpty()) append(" - ")
                                    append(title)
                                }
                                if (isEmpty()) append(if (type == "audio") "Audio $id" else "Subtitle $id")
                            }
                            if (type == "audio") {
                                audioTracks.add(PlayerState.VideoTrack(id, name, selected))
                            } else if (type == "sub") {
                                subTracks.add(PlayerState.VideoTrack(id, name, selected))
                            }
                        }
                        playerState?.audioTracks?.value = audioTracks
                        playerState?.subtitleTracks?.value = subTracks
                        // Poll Video Stats
                        if (playerState != null && playerState.showStats.value) {
                            playerState.videoCodec.value = MpvLibrary.INSTANCE.mpv_get_property_string(h, "video-codec") ?: "Unknown"
                            playerState.audioCodec.value = MpvLibrary.INSTANCE.mpv_get_property_string(h, "audio-codec") ?: "Unknown"
                            playerState.hwdecCurrent.value = MpvLibrary.INSTANCE.mpv_get_property_string(h, "hwdec-current") ?: "Unknown"
                            playerState.droppedFrames.value = MpvLibrary.INSTANCE.mpv_get_property_string(h, "vo-drop-frame-count")?.toLongOrNull() ?: 0L
                            playerState.fps.value = MpvLibrary.INSTANCE.mpv_get_property_string(h, "container-fps")?.toDoubleOrNull() ?: 0.0
                            val w = MpvLibrary.INSTANCE.mpv_get_property_string(h, "width") ?: "0"
                            val hw = MpvLibrary.INSTANCE.mpv_get_property_string(h, "height") ?: "0"
                            playerState.resolution.value = "${w}x$hw"
                            playerState.videoBitrate.value = MpvLibrary.INSTANCE.mpv_get_property_string(h, "video-bitrate")?.toLongOrNull() ?: 0L
                            playerState.audioBitrate.value = MpvLibrary.INSTANCE.mpv_get_property_string(h, "audio-bitrate")?.toLongOrNull() ?: 0L
                        }
                    }
                    loops++

                    // Check for completion
                    val eofStr = MpvLibrary.INSTANCE.mpv_get_property_string(h, "eof-reached")
                    if (eofStr == "yes") {
                        // For live/non-seekable streams, EOF just means the current HTTP chunk ended.
                        // Don't treat it as "finished" — the stream may resume.
                        val seekableStr = MpvLibrary.INSTANCE.mpv_get_property_string(h, "seekable")
                        val isSeekable = seekableStr == "yes"
                        if (isSeekable) {
                            // Normal VOD stream — genuinely finished
                            if (hasEverPlayed) {
                                currentOnFinished()
                            } else {
                                currentOnPlaybackError("Stream failed to load or instantly ended.")
                            }
                            break
                        } else if (!hasEverPlayed) {
                            // Non-seekable stream that never played — it's truly dead
                            currentOnPlaybackError("Stream failed to load or instantly ended.")
                            break
                        }
                        // else: non-seekable stream that HAS played before — likely a live stream
                        // that hit a temporary EOF. Don't break, let MPV's internal reconnect handle it.
                    }

                    // Check timeout. Allow user to strictly control how long they wait before skipping.
                    val timeoutStr = com.lagradost.common.storage.DesktopDataStore.getKey<String>(PlayerConfig.PREF_AUTO_PLAY_TIMEOUT)
                    val userTimeoutMs = timeoutStr?.toLongOrNull() ?: 20000L

                    // For complex HLS streams with many tracks, probing all of them in safe batches takes 10-15s.
                    // Enforce a minimum of 30s timeout here to ensure FFmpeg isn't killed prematurely.
                    val timeoutMs = maxOf(userTimeoutMs, 90000L)

                    if (!hasEverPlayed && System.currentTimeMillis() - loadStartTime > timeoutMs) {
                        com.lagradost.common.logging.AppLogger.e("MPV timeout reached while buffering ($timeoutMs ms)")
                        currentOnPlaybackError("Connection timed out. The stream might be dead or too slow.")
                        break
                    }

                    delay(200)
                }
            }
        }
    }

    LaunchedEffect(link, mpvHandle) {
        val handle = mpvHandle ?: return@LaunchedEffect

        val validated = PlayerLinkHandler.validate(link, title).getOrElse {
            currentOnPlaybackError(it.message ?: "Validation failed")
            return@LaunchedEffect
        }

        val lib = MpvLibrary.INSTANCE
        when (validated.streamKind) {
            PlayerLinkHandler.StreamKind.HLS -> {
                lib.mpv_set_property_string(handle, "hls-bitrate", "max")
                // Apply strict HLS probing limits and disable persistent HTTP to prevent connection flooding on the proxy
                // http_persistent=0 forces a new TCP connection per HLS segment.
                // This is required because our proxy streams responses without Content-Length,
                // which means FFmpeg can't delimit responses on a reused keep-alive connection.
                lib.mpv_set_property_string(
                    handle,
                    "demuxer-lavf-o",
                    "reconnect=1,reconnect_streamed=1,reconnect_delay_max=4,extension_picky=0,http_persistent=0",
                )
            }
            PlayerLinkHandler.StreamKind.DASH -> {
                lib.mpv_set_property_string(handle, "demuxer-lavf-o", "reconnect=1,reconnect_streamed=1,reconnect_delay_max=4")
            }
            PlayerLinkHandler.StreamKind.PROGRESSIVE -> {
                // Raw MPEG-TS / progressive HTTP live streams need reconnect too.
                // Without this, FFmpeg treats the end of an HTTP chunk as permanent EOF.
                lib.mpv_set_property_string(
                    handle,
                    "demuxer-lavf-o",
                    "reconnect=1,reconnect_streamed=1,reconnect_delay_max=4",
                )
                // Stream-level reconnect is critical for live MPEG-TS over HTTP.
                // demuxer-lavf-o alone may not propagate to the stream protocol layer.
                lib.mpv_set_property_string(
                    handle,
                    "stream-lavf-o",
                    "reconnect=1,reconnect_streamed=1,reconnect_delay_max=4",
                )
            }
        }

        // Disable auto-probing of subtitles and pre-select English audio
        // This is critical for complex HLS streams with 20+ tracks to prevent the initial demuxer probe
        // from timing out by aggressively downloading multiple streams concurrently.
        lib.mpv_set_property_string(handle, "alang", "eng,en")
        lib.mpv_set_property_string(handle, "sub-auto", "no")
        lib.mpv_set_property_string(handle, "sid", "no")
        lib.mpv_set_property_string(handle, "aid", "auto")

        // Increase buffer sizes to accommodate slow proxies or CDN connections
        // For HLS streams, we give MPV much more buffer time (500MB max) to allow
        // aggressive background fetching. This makes seeking forward instant.
        val isHls = validated.streamKind == PlayerLinkHandler.StreamKind.HLS
        val maxBytes = if (isHls) "500000000" else "150000000" // 500MB for HLS, 150MB for others
        lib.mpv_set_property_string(handle, "demuxer-max-bytes", maxBytes)
        lib.mpv_set_property_string(handle, "demuxer-max-back-bytes", "150000000") // 150MB
        lib.mpv_set_property_string(handle, "cache", "yes")
        // Allow MPV to buffer up to 1 hour ahead, limited only by maxBytes
        if (isHls) {
            lib.mpv_set_property_string(handle, "cache-secs", "3600")
            // Wait up to 3 seconds before pausing for buffering
            lib.mpv_set_property_string(handle, "cache-pause-wait", "3")
        }

        val startSec = startPositionMs / 1000L
        if (startSec > 0) {
            lib.mpv_set_property_string(handle, "start", startSec.toString())
        }

        if (validated.displayTitle.isNotBlank()) {
            lib.mpv_set_property_string(handle, "force-media-title", validated.displayTitle)
            lib.mpv_set_property_string(handle, "title", validated.displayTitle)
        }

        // Apply headers dynamically via property
        val headersStr = validated.headers.entries.joinToString(",") { "${it.key}: ${it.value.replace(",", "\\,")}" }
        if (headersStr.isNotBlank()) {
            lib.mpv_set_property_string(handle, "http-header-fields", headersStr)
        }

        val urlTarget = if (validated.useUrlFile) {
            PlayerLinkHandler.writeUrlListFile("cloudstream_mpv_url_", validated.displayTitle, validated.url).absolutePath
        } else {
            validated.url
        }

        val safeUrl = urlTarget.replace("\\", "/")
        com.lagradost.common.logging.AppLogger.i("Loading embedded MPV URL: $safeUrl")

        // Prevent "Immediate exit requested" double-load bug by safely stopping any existing internal playback first
        lib.mpv_command_string(handle, "stop")

        // Wait for MPV to clear the time-pos (so we don't accidentally fire onPlaybackReady for the old video)
        var waitAttempts = 0
        while (waitAttempts < 10) {
            val posStr = lib.mpv_get_property_string(handle, "time-pos")
            if (posStr == null || posStr.toDoubleOrNull() == null) break
            kotlinx.coroutines.delay(50)
            waitAttempts++
        }

        hasEverPlayed = false // Now it's safe to reset the playback flag for the new link
        loadStartTime = System.currentTimeMillis() // Reset the buffering timeout clock

        lib.mpv_command_string(handle, "loadfile \"$safeUrl\"")

        // Ensure the player is unpaused when loading a new link,
        // since the UI might have paused it while buffering
        lib.mpv_set_property_string(handle, "pause", "no")
        playerState?.isPaused?.value = false

        // Subtitles handling
        val sessionId = validated.proxySessionId
        val finalSubtitles = if (sessionId != null) {
            subtitles.map { it.copy(url = com.lagradost.player.impl.proxy.LocalStreamProxy.buildProxyUrl(sessionId, it.url)) }
        } else {
            subtitles
        }

        kotlin.concurrent.thread(isDaemon = true) {
            var attempts = 0
            while (attempts < 150) {
                val posStr = lib.mpv_get_property_string(handle, "time-pos")
                if ((posStr?.toDoubleOrNull() ?: 0.0) > 0.0) break
                Thread.sleep(200)
                attempts++
            }

            val defaultSub = finalSubtitles.firstOrNull()
            if (defaultSub != null) {
                val escapedSub = defaultSub.url.replace("\\", "\\\\").replace("\"", "\\\"")
                val escapedTitle = defaultSub.lang.replace("\\", "\\\\").replace("\"", "\\\"")
                lib.mpv_command_string(handle, "sub-add \"$escapedSub\" select \"$escapedTitle\"")
            }
        }
    }

    val videoCanvas = remember {
        object : Canvas() {

            override fun addNotify() {
                super.addNotify()

                if (mpvHandle != null) return // Prevent multiple initializations (multi-audio bug)

                // Find MPV directory and tell JNA where to find the DLL
                val isWindows = System.getProperty("os.name").lowercase().contains("win")
                val mpvExe = resolveMpvExecutable(isWindows)
                if (mpvExe == null) {
                    currentOnPlaybackError("MPV executable not found.")
                    return
                }
                val mpvDir = mpvExe.parentFile
                System.setProperty("jna.library.path", mpvDir.absolutePath)

                val lib = MpvLibrary.INSTANCE
                val handle = lib.mpv_create() ?: run {
                    currentOnPlaybackError("Failed to initialize MPV Engine.")
                    return
                }
                mpvHandle = handle
                playerState?.attachMpv(handle)

                lib.mpv_set_option_string(handle, "osc", "no")
                lib.mpv_set_option_string(handle, "osd-level", "0")
                lib.mpv_set_option_string(handle, "osd-bar", "no")
                lib.mpv_set_option_string(handle, "vo", "gpu")

                // Apply User Settings & Logging
                PlayerConfig.applyMpvSettings(handle, lib)

                val wid = com.sun.jna.Native.getComponentID(this)
                lib.mpv_set_option_string(handle, "wid", wid.toString())

                lib.mpv_set_option_string(handle, "input-default-bindings", "no")
                lib.mpv_set_option_string(handle, "input-vo-keyboard", "no")
                lib.mpv_set_option_string(handle, "save-position-on-quit", "no")
                lib.mpv_set_option_string(handle, "resume-playback", "no")
                lib.mpv_set_option_string(handle, "keep-open", "yes")
                lib.mpv_set_option_string(handle, "tls-verify", "no")
                lib.mpv_set_option_string(handle, "ytdl", "no")
                lib.mpv_set_option_string(handle, "idle", "yes")

                com.lagradost.common.logging.AppLogger.i("Initializing embedded MPV Engine")
                lib.mpv_initialize(handle)

                // Setup Mouse and Keyboard interactions
                val canvas = this
                canvas.addMouseMotionListener(object : MouseMotionAdapter() {
                    override fun mouseMoved(e: MouseEvent) {
                        mpvHandle?.let { h -> MpvLibrary.INSTANCE.mpv_command_string(h, "mouse ${e.x} ${e.y}") }
                    }
                    override fun mouseDragged(e: MouseEvent) {
                        mpvHandle?.let { h -> MpvLibrary.INSTANCE.mpv_command_string(h, "mouse ${e.x} ${e.y}") }
                    }
                })

                canvas.addMouseListener(object : MouseAdapter() {
                    override fun mousePressed(e: MouseEvent) {
                        mpvHandle?.let { h ->
                            canvas.requestFocusInWindow()
                            val btn = when (e.button) {
                                MouseEvent.BUTTON1 -> "MBTN_LEFT"
                                MouseEvent.BUTTON2 -> "MBTN_MID"
                                MouseEvent.BUTTON3 -> "MBTN_RIGHT"
                                else -> return
                            }
                            MpvLibrary.INSTANCE.mpv_command_string(h, "keydown $btn")
                        }
                    }
                    override fun mouseReleased(e: MouseEvent) {
                        mpvHandle?.let { h ->
                            val btn = when (e.button) {
                                MouseEvent.BUTTON1 -> "MBTN_LEFT"
                                MouseEvent.BUTTON2 -> "MBTN_MID"
                                MouseEvent.BUTTON3 -> "MBTN_RIGHT"
                                else -> return
                            }
                            MpvLibrary.INSTANCE.mpv_command_string(h, "keyup $btn")
                        }
                    }
                })

                canvas.addMouseWheelListener { e ->
                    mpvHandle?.let { h ->
                        val key = if (e.wheelRotation < 0) "WHEEL_UP" else "WHEEL_DOWN"
                        MpvLibrary.INSTANCE.mpv_command_string(h, "keypress $key")
                    }
                }

                var keyDispatcher: java.awt.KeyEventDispatcher? = null

                keyDispatcher = java.awt.KeyEventDispatcher { e ->
                    if (e.id == KeyEvent.KEY_PRESSED) {
                        mpvHandle?.let { h ->
                            val mpvKey = awtKeyToMpv(e)
                            if (mpvKey?.contains("QUIT_OVERRIDE") == true) {
                                currentOnCloseRequest()
                            } else if (mpvKey != null) {
                                when (mpvKey.lowercase()) {
                                    "space" -> MpvLibrary.INSTANCE.mpv_command_string(h, "cycle pause")
                                    "left" -> MpvLibrary.INSTANCE.mpv_command_string(h, "seek -10")
                                    "right" -> MpvLibrary.INSTANCE.mpv_command_string(h, "seek 10")
                                    "up" -> MpvLibrary.INSTANCE.mpv_command_string(h, "add volume 5")
                                    "down" -> MpvLibrary.INSTANCE.mpv_command_string(h, "add volume -5")
                                    "m" -> MpvLibrary.INSTANCE.mpv_command_string(h, "cycle mute")
                                    "f", "f11" -> currentOnFullscreenToggle?.invoke()
                                    else -> {
                                        // MpvLibrary.INSTANCE.mpv_command_string(h, "keydown $mpvKey")
                                    }
                                }
                            }
                        }
                    }
                    false
                }
                java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(keyDispatcher)
            }

            override fun removeNotify() {
                // Find and remove the dispatcher to prevent memory leaks
                val focusManager = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager()
                // If we saved it:
                try {
                    val field = this.javaClass.getDeclaredField("keyDispatcher")
                    field.isAccessible = true
                    val dispatcher = field.get(this) as? java.awt.KeyEventDispatcher
                    if (dispatcher != null) {
                        focusManager.removeKeyEventDispatcher(dispatcher)
                    }
                } catch (e: Exception) {
                    // Fallback if reflection fails (it shouldn't, but just in case)
                }

                mpvHandle?.let { MpvLibrary.INSTANCE.mpv_terminate_destroy(it) }
                mpvHandle = null
                playerState?.detachMpv()
                super.removeNotify()
            }
        }.apply {
            background = Color.BLACK
            isFocusable = true
        }
    }

    SwingPanel(
        background = androidx.compose.ui.graphics.Color.Black,
        factory = { videoCanvas },
        modifier = modifier,
    )
}

private fun resolveMpvExecutable(isWindows: Boolean): File? {
    val names = if (isWindows) listOf("libmpv-2.dll") else listOf("libmpv.so", "libmpv.dylib")

    val resDir = System.getProperty("compose.application.resources.dir")

    val candidates = listOfNotNull(
        resDir?.let { File(it, "mpv") },
        File("mpv"),
        File("2_cloudstream_desktop/mpv"),
        File("desktop-app/mpv"),
        File("desktop-app/appResources/mpv"),
    )
    for (base in candidates) {
        for (name in names) {
            val f = File(base, name)
            if (f.isFile) return f.absoluteFile
        }
    }
    return null
}

private fun awtKeyToMpv(e: KeyEvent): String? {
    if (e.isShiftDown) {
        when (e.keyCode) {
            KeyEvent.VK_3 -> return "#"
            KeyEvent.VK_1 -> return "!"
            KeyEvent.VK_2 -> return "@"
            KeyEvent.VK_4 -> return "$"
            KeyEvent.VK_5 -> return "%"
            KeyEvent.VK_6 -> return "^"
            KeyEvent.VK_7 -> return "&"
            KeyEvent.VK_8 -> return "*"
            KeyEvent.VK_9 -> return "("
            KeyEvent.VK_0 -> return ")"
            KeyEvent.VK_OPEN_BRACKET -> return "{"
            KeyEvent.VK_CLOSE_BRACKET -> return "}"
            KeyEvent.VK_COMMA -> return "<"
            KeyEvent.VK_PERIOD -> return ">"
            KeyEvent.VK_MINUS -> return "_"
            KeyEvent.VK_EQUALS -> return "+"
            KeyEvent.VK_Q -> return "QUIT_OVERRIDE"
            in KeyEvent.VK_A..KeyEvent.VK_Z -> {
                val letter = KeyEvent.getKeyText(e.keyCode).uppercase()
                val ctrl = if (e.isControlDown) "Ctrl+" else ""
                val alt = if (e.isAltDown) "Alt+" else ""
                return "$ctrl$alt$letter"
            }
        }
    }

    val baseKey = when (e.keyCode) {
        KeyEvent.VK_SPACE -> "SPACE"
        KeyEvent.VK_LEFT -> "LEFT"
        KeyEvent.VK_RIGHT -> "RIGHT"
        KeyEvent.VK_UP -> "UP"
        KeyEvent.VK_DOWN -> "DOWN"
        KeyEvent.VK_ENTER -> "ENTER"
        KeyEvent.VK_ESCAPE -> "ESC"
        KeyEvent.VK_BACK_SPACE -> "BS"
        KeyEvent.VK_DELETE -> "DEL"
        KeyEvent.VK_TAB -> "TAB"
        KeyEvent.VK_PAGE_UP -> "PGUP"
        KeyEvent.VK_PAGE_DOWN -> "PGDWN"
        KeyEvent.VK_HOME -> "HOME"
        KeyEvent.VK_END -> "END"

        KeyEvent.VK_Q -> "QUIT_OVERRIDE"

        in KeyEvent.VK_A..KeyEvent.VK_Z -> KeyEvent.getKeyText(e.keyCode).lowercase()
        in KeyEvent.VK_0..KeyEvent.VK_9 -> KeyEvent.getKeyText(e.keyCode)

        KeyEvent.VK_COMMA -> ","
        KeyEvent.VK_PERIOD -> "."
        KeyEvent.VK_SLASH, KeyEvent.VK_DIVIDE -> "/"
        KeyEvent.VK_MULTIPLY -> "*"
        KeyEvent.VK_MINUS, KeyEvent.VK_SUBTRACT -> "-"
        KeyEvent.VK_PLUS, KeyEvent.VK_ADD, KeyEvent.VK_EQUALS -> "+"
        KeyEvent.VK_OPEN_BRACKET -> "["
        KeyEvent.VK_CLOSE_BRACKET -> "]"
        KeyEvent.VK_BACK_SLASH -> "\\"
        KeyEvent.VK_SEMICOLON -> ";"
        KeyEvent.VK_QUOTE -> "'"

        else -> return null
    }

    val alt = if (e.isAltDown) "Alt+" else ""
    val ctrl = if (e.isControlDown) "Ctrl+" else ""
    val shift = if (e.isShiftDown && e.keyCode !in KeyEvent.VK_A..KeyEvent.VK_Z && baseKey.length > 1) "Shift+" else ""

    return "$ctrl$alt$shift$baseKey"
}
