package com.lagradost.cloudstream3.desktop.player.vlcj

import com.lagradost.cloudstream3.desktop.player.PlayerConfig
import com.lagradost.common.logging.AppLogger
import com.lagradost.common.storage.DesktopDataStore
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer
import uk.co.caprica.vlcj.player.embedded.videosurface.CallbackVideoSurface
import uk.co.caprica.vlcj.player.embedded.videosurface.VideoSurfaceAdapters
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.format.RV32BufferFormat

/**
 * Core VLCJ engine wrapping MediaPlayerFactory + EmbeddedMediaPlayer
 * with direct rendering via CallbackVideoSurface.
 *
 * Thread safety:
 *   - VLC frame callbacks come from a native libvlc thread.
 *   - [currentFramePixels] uses AtomicReference for lock-free reads.
 */
class VlcjEngine {

    companion object {
        private const val TAG = "VlcjEngine"
    }

    // ── State ────────────────────────────────────────────────────────
    private var _factory: MediaPlayerFactory? = null
    private var _mediaPlayer: EmbeddedMediaPlayer? = null

    private val _framePixels = AtomicReference<ByteArray?>(null)

    @Volatile
    var videoWidth: Int = 0
        private set

    @Volatile
    var videoHeight: Int = 0
        private set

    private val _positionMs = AtomicLong(0L)
    private val _durationMs = AtomicLong(0L)
    private val _volume = AtomicInteger(100)
    private val _isPlaying = AtomicBoolean(false)
    private val _isFinished = AtomicBoolean(false)

    // ── Public state queries ─────────────────────────────────────────

    val isInitialised: Boolean get() = _mediaPlayer != null

    val isPlaying: Boolean get() = _isPlaying.get()

    val positionMs: Long get() = _positionMs.get()

    val durationMs: Long get() = _durationMs.get()

    val volume: Int get() = _volume.get()

    val isFinished: Boolean get() = _isFinished.get()

    val currentFramePixels: ByteArray? get() = _framePixels.get()

    // ── Initialisation ───────────────────────────────────────────────

    /**
     * Initialises the VLCJ engine with a [CallbackVideoSurface] for
     * direct rendering. Must be called after [BundledVlcDiscovery.discover()].
     */
    fun initialise(extraArgs: List<String> = emptyList()) {
        if (_factory != null) {
            AppLogger.w("$TAG — already initialised")
            return
        }

        val hwMode = DesktopDataStore.getKey<String>(PlayerConfig.PREF_HW_MODE) ?: "auto"
        val vlcArgs = buildVlcArgs(hwMode, extraArgs)

        AppLogger.i("$TAG — initialising: ${vlcArgs.joinToString(" ")}")

        val factory = try {
            MediaPlayerFactory(vlcArgs)
        } catch (e: Exception) {
            AppLogger.e("$TAG — MediaPlayerFactory failed", e)
            throw RuntimeException("VLCJ init failed: ${e.message}", e)
        }

        val mediaPlayer = factory.mediaPlayers().newEmbeddedMediaPlayer()

        // ── Direct rendering callback ───────────────────────────
        val adapter = VideoSurfaceAdapters.getVideoSurfaceAdapter()
        val bufFmtCb = object : BufferFormatCallback {
            override fun getBufferFormat(sourceWidth: Int, sourceHeight: Int): BufferFormat {
                this@VlcjEngine.videoWidth = sourceWidth
                this@VlcjEngine.videoHeight = sourceHeight
                _framePixels.set(null)
                AppLogger.i("$TAG — buffer format: ${sourceWidth}x$sourceHeight")
                return RV32BufferFormat(sourceWidth, sourceHeight)
            }
            override fun allocatedBuffers(buffers: Array<ByteBuffer>) {}
        }
        val renderCb = RenderCallback { _: MediaPlayer, nativeBuffers: Array<ByteBuffer>, _: BufferFormat ->
            val src = nativeBuffers[0]
            val w = this@VlcjEngine.videoWidth
            val h = this@VlcjEngine.videoHeight
            if (w <= 0 || h <= 0) return@RenderCallback

            val totalBytes = w * h * 4
            if (src.remaining() < totalBytes) return@RenderCallback

            var pixels = _framePixels.get()
            if (pixels == null || pixels.size != totalBytes) {
                pixels = ByteArray(totalBytes)
            }

            // RV32 on little-endian (x86/x64) stores pixels as [B, G, R, A] per byte,
            // which matches Skia's BGRA_8888 format exactly. No byte swap needed.
            src.rewind()
            src.get(pixels, 0, totalBytes)
            _framePixels.set(pixels)
        }
        mediaPlayer.videoSurface().set(
            CallbackVideoSurface(bufFmtCb, renderCb, true, adapter)
        )

        // ── Event listeners ──────────────────────────────────────
        mediaPlayer.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {

            override fun playing(mediaPlayer: MediaPlayer) {
                _isPlaying.set(true)
                _isFinished.set(false)
                AppLogger.i("$TAG — playing")
            }

            override fun paused(mediaPlayer: MediaPlayer) {
                _isPlaying.set(false)
                AppLogger.i("$TAG — paused")
            }

            override fun stopped(mediaPlayer: MediaPlayer) {
                _isPlaying.set(false)
                AppLogger.i("$TAG — stopped")
            }

            override fun finished(mediaPlayer: MediaPlayer) {
                _isPlaying.set(false)
                _isFinished.set(true)
                AppLogger.i("$TAG — finished")
            }

            override fun error(mediaPlayer: MediaPlayer) {
                // --verbose=2 in VLC args outputs native errors to stderr
                AppLogger.e("$TAG — playback error (see VLC verbose output above)")
            }

            override fun positionChanged(mediaPlayer: MediaPlayer, newPosition: Float) {
                val dur = _durationMs.get()
                if (dur > 0) {
                    _positionMs.set((newPosition * dur).toLong())
                }
            }

            override fun lengthChanged(mediaPlayer: MediaPlayer, newLength: Long) {
                if (newLength > 0) _durationMs.set(newLength)
            }
        })

        _factory = factory
        _mediaPlayer = mediaPlayer
    }

    // ── Playback control ─────────────────────────────────────────────

    fun play(
        url: String,
        title: String? = null,
        headers: List<String>? = null,
        startMs: Long = 0L,
    ) {
        val player = _mediaPlayer ?: run {
            AppLogger.e("$TAG — play() before initialise()")
            return
        }

        _isFinished.set(false)
        _framePixels.set(null)

        val options = mutableListOf(
            ":no-metadata-network-access",
            ":network-caching=2000",
            ":avcodec-threads=1",
        )
        if (startMs > 0) options.add(":start-time=${startMs / 1000f}")
        if (title != null) options.add(":meta-title=$title")
        headers?.forEach { h -> options.add(":http-set=$h") }

        AppLogger.i("$TAG — playing: $url")
        player.media().play(url, *options.toTypedArray())
    }

    fun pause() {
        _mediaPlayer?.controls()?.pause()
    }

    fun resume() {
        _mediaPlayer?.controls()?.play()
    }

    fun togglePause() {
        val p = _mediaPlayer ?: return
        if (_isPlaying.get()) p.controls().pause() else p.controls().play()
    }

    fun stop() {
        _mediaPlayer?.controls()?.stop()
        _isPlaying.set(false)
        _isFinished.set(true)
    }

    fun seek(positionMs: Long) {
        val dur = _durationMs.get()
        if (dur > 0) {
            val ratio = positionMs.toFloat() / dur.toFloat()
            _mediaPlayer?.controls()?.setPosition(ratio.coerceIn(0f, 1f))
        }
    }

    fun setVolume(vol: Int) {
        val clamped = vol.coerceIn(0, 100)
        _volume.set(clamped)
        _mediaPlayer?.audio()?.setVolume(clamped)
    }

    // ── Cleanup ──────────────────────────────────────────────────────

    fun release() {
        _mediaPlayer?.release()
        _mediaPlayer = null
        _factory?.release()
        _factory = null

        _isPlaying.set(false)
        _isFinished.set(false)
        _framePixels.set(null)
        videoWidth = 0
        videoHeight = 0
        _positionMs.set(0L)
        _durationMs.set(0L)

        AppLogger.i("$TAG — released")
    }

    // ── Internals ────────────────────────────────────────────────────

    private fun buildVlcArgs(hwMode: String, extra: List<String>): List<String> {
        val base = mutableListOf(
            "--intf=dummy",
            "--no-osd",
            "--no-stats",
            "--verbose=2",
            "--no-plugins-cache",
            "--reset-plugins-cache",
            "--no-video-title-show",
            "--no-sub-autodetect-file",
            "--drop-late-frames",
            "--skip-frames",
            "--no-metadata-network-access",
            "--network-caching=2000",
            // Force avcodec decoder to skip HW decoder probing (avoids get_buffer() failed)
            "--codec=avcodec",
            "--http-user-agent=Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
            // Disable HW decoding explicitly. CallbackVideoSurface sets
            // --dec-dev=none via libvlc_video_set_callbacks, but VLC 3.0.21
            // still probes d3d11va/dxva2 before falling back to software,
            // causing extra init cycles. --avcodec-hw=none prevents that.
            "--avcodec-hw=none",
        )
        base.addAll(extra)
        return base
    }
}
