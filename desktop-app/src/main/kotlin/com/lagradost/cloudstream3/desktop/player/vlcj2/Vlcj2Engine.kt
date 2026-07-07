package com.lagradost.cloudstream3.desktop.player.vlcj2

import com.lagradost.common.logging.AppLogger
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
 * Minimal VLCJ engine for Phase 0 baseline validation.
 *
 * Covers validation phases 0.1–0.9 from the migration plan:
 *   [0.1] VLC Native Discovery (caller responsibility via BundledVlcDiscovery)
 *   [0.2] MediaPlayerFactory creation
 *   [0.3] EmbeddedMediaPlayer basic lifecycle
 *   [0.4] Network stream open (audio only)
 *   [0.5] Audio playback confirmation (events)
 *   [0.6] Basic events verification
 *   [0.7] CallbackVideoSurface + format negotiation
 *   [0.8] RenderCallback display() invocation
 *   [0.9] Frame data extraction
 *
 * Design decisions (per plan D2–D10):
 *   - BufferFormatCallback: explicit object (NOT SAM lambda) — D3
 *   - RenderCallback: explicit object (NOT SAM lambda) — D2
 *   - lockBuffers=true — D6
 *   - Minimal VLC args only: --intf=dummy --no-osd --no-video-title-show --verbose=2 — D4
 *   - No --codec=avcodec, no --avcodec-hw=none, no --network-caching=2000 — D5
 *   - Defensive copy (copyOf()) before storing frame — D8
 *   - rewind() before ByteBuffer.get() — D9
 *   - Package: vlcj2 — D10
 *
 * Thread safety:
 *   - RenderCallback.display() runs on native libvlc thread.
 *   - framePixels: AtomicReference for lock-free reads from Compose thread.
 *   - positionMs/durationMs: AtomicLong.
 *   - isPlaying/isFinished: AtomicBoolean.
 *   - videoWidth/videoHeight: @Volatile.
 */
class Vlcj2Engine {

    companion object {
        private const val TAG = "VLCJ2"
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


    // ── VLC args ─────────────────────────────────────────────────────
    /**
     * Minimal VLC args per plan D4.
     * NO --codec=avcodec, NO --avcodec-hw=none, NO --network-caching=2000.
     * These are added ONE BY ONE only when proven necessary.
     */
    private val vlcArgs: List<String> = listOf(
        "--intf=dummy",
        "--no-osd",
        "--no-video-title-show",
        "--verbose=2",
    )

    // ── Public state queries ─────────────────────────────────────────

    val isInitialized: Boolean get() = _mediaPlayer != null

    val isPlaying: Boolean get() = _isPlaying.get()

    val positionMs: Long get() = _positionMs.get()

    val durationMs: Long get() = _durationMs.get()

    val volume: Int get() = _volume.get()

    val isFinished: Boolean get() = _isFinished.get()

    val currentFramePixels: ByteArray? get() = _framePixels.get()


    // ── BufferFormatCallback — explicit object (NOT SAM lambda) ──────

    /**
     * [BufferFormatCallback] as an EXPLICIT object implementing the interface.
     * Plan D3: explicit object for clarity over SAM lambda.
     *
     * Methods:
     *   - getBufferFormat(sourceWidth, sourceHeight) → BufferFormat
     *   - allocatedBuffers(buffers)
     *
     * vlcj 4.8.2 has exactly these 2 methods. No newFormatSize() exists in this version.
     */
    private val bufFmtCb: BufferFormatCallback = object : BufferFormatCallback {

        override fun getBufferFormat(sourceWidth: Int, sourceHeight: Int): BufferFormat {
            this@Vlcj2Engine.videoWidth = sourceWidth
            this@Vlcj2Engine.videoHeight = sourceHeight
            _framePixels.set(null)
            val fmt = RV32BufferFormat(sourceWidth, sourceHeight)
            AppLogger.i("$TAG — [FMT] getBufferFormat($sourceWidth x $sourceHeight)")
            return fmt
        }

        override fun allocatedBuffers(buffers: Array<ByteBuffer>) {
            AppLogger.i("$TAG — [FMT] allocatedBuffers(${buffers.size} buffers)")
        }
    }

    // ── RenderCallback — explicit object (NOT SAM lambda) ────────────

    /**
     * [RenderCallback] as an EXPLICIT object implementing the interface.
     * Plan D2: explicit object for clarity over SAM lambda.
     *
     * vlcj 4.8.2 has exactly 1 method: display(MediaPlayer, ByteBuffer[], BufferFormat).
     * NO lock()/unlock() methods exist in this version — they were added in later commits.
     */
    private val renderCb: RenderCallback = object : RenderCallback {

        override fun display(mediaPlayer: MediaPlayer, nativeBuffers: Array<ByteBuffer>, bufferFormat: BufferFormat) {
            val src = nativeBuffers[0]
            val w = this@Vlcj2Engine.videoWidth
            val h = this@Vlcj2Engine.videoHeight
            if (w <= 0 || h <= 0) return
            val totalBytes = w * h * 4
            if (src.capacity() < totalBytes) return
            src.rewind()
            val pixels = ByteArray(totalBytes)
            src.get(pixels, 0, totalBytes)
            _framePixels.set(pixels.copyOf())
        }
    }

    // ── Event handler — explicit object ──────────────────────────────

    private val eventHandler: MediaPlayerEventAdapter = object : MediaPlayerEventAdapter() {

        override fun playing(mediaPlayer: MediaPlayer) {
            _isPlaying.set(true)
            _isFinished.set(false)
            AppLogger.i("$TAG — [EVENT] playing()")
        }

        override fun paused(mediaPlayer: MediaPlayer) {
            _isPlaying.set(false)
            AppLogger.i("$TAG — [EVENT] paused()")
        }

        override fun stopped(mediaPlayer: MediaPlayer) {
            _isPlaying.set(false)
            AppLogger.i("$TAG — [EVENT] stopped()")
        }

        override fun finished(mediaPlayer: MediaPlayer) {
            _isPlaying.set(false)
            _isFinished.set(true)
            AppLogger.i("$TAG — [EVENT] finished()")
        }

        override fun error(mediaPlayer: MediaPlayer) {
            AppLogger.e("$TAG — [EVENT] error()")
        }

        override fun positionChanged(mediaPlayer: MediaPlayer, newPosition: Float) {
            val dur = _durationMs.get()
            if (dur > 0) {
                _positionMs.set((newPosition * dur).toLong())
            }
        }

        override fun lengthChanged(mediaPlayer: MediaPlayer, newLength: Long) {
            if (newLength > 0) {
                _durationMs.set(newLength)
                AppLogger.i("$TAG — [EVENT] lengthChanged($newLength ms)")
            }
        }
    }

    // ── Initialisation ───────────────────────────────────────────────

    /**
     * Initialises the VLCJ engine with a minimal [CallbackVideoSurface].
     *
     * Must be called after [com.lagradost.cloudstream3.desktop.player.vlcj.BundledVlcDiscovery.discover()].
     *
     * @param extraArgs Additional VLC arguments (currently unused in the minimal config;
     *                  reserved for future phased additions per plan D4).
     */
    fun initialize(extraArgs: List<String> = emptyList()) {
        if (_factory != null) {
            AppLogger.w("$TAG — already initialised, skipping")
            return
        }

        // ── Phase 0.1: VLC Native Discovery ──────────────────────────
        // Caller responsibility: BundledVlcDiscovery.discover() must be
        // called before this method.
        AppLogger.i("$TAG — Phase 0.1: VLC Native Discovery assumed complete (caller responsibility)")

        // ── Phase 0.2: MediaPlayerFactory creation ───────────────────
        val allArgs = if (extraArgs.isEmpty()) vlcArgs else vlcArgs + extraArgs
        AppLogger.i("$TAG — Phase 0.2: Creating MediaPlayerFactory with args: ${allArgs.joinToString(" ")}")
        val factory = try {
            MediaPlayerFactory(allArgs)
        } catch (e: Exception) {
            AppLogger.e("$TAG — Phase 0.2 FAILED: MediaPlayerFactory exception", e)
            throw RuntimeException("VLCJ2 init failed at Phase 0.2: ${e.message}", e)
        }
        AppLogger.i("$TAG — Phase 0.2: MediaPlayerFactory created OK")

        // ── Phase 0.3: EmbeddedMediaPlayer lifecycle ─────────────────
        AppLogger.i("$TAG — Phase 0.3: Creating EmbeddedMediaPlayer...")
        val mediaPlayer = factory.mediaPlayers().newEmbeddedMediaPlayer()
        AppLogger.i("$TAG — Phase 0.3: EmbeddedMediaPlayer created OK")

        // ── Phase 0.7: CallbackVideoSurface + format negotiation ─────
        AppLogger.i("$TAG — Phase 0.7: Setting up CallbackVideoSurface (lockBuffers=true)...")
        val adapter = VideoSurfaceAdapters.getVideoSurfaceAdapter()
        val callbackVideoSurface = CallbackVideoSurface(bufFmtCb, renderCb, true, adapter)
        mediaPlayer.videoSurface().set(callbackVideoSurface)
        AppLogger.i("$TAG — Phase 0.7: CallbackVideoSurface attached OK")

        // ── Phase 0.6: Event listeners ───────────────────────────────
        AppLogger.i("$TAG — Phase 0.6: Attaching MediaPlayerEventAdapter...")
        mediaPlayer.events().addMediaPlayerEventListener(eventHandler)
        AppLogger.i("$TAG — Phase 0.6: Event listeners attached OK")

        _factory = factory
        _mediaPlayer = mediaPlayer

        AppLogger.i("$TAG — initialize() complete. isInitialized=true")
    }

    // ── Playback control ─────────────────────────────────────────────

    /**
     * Opens a media URL for playback.
     *
     * NO extra VLC flags beyond start-time and headers.
     * No --network-caching, no --codec, no --avcodec-hw.
     *
     * @param url     Media URL (HTTP, HLS, etc.)
     * @param title   Optional media title
     * @param headers Optional HTTP headers (applied as :http-set options)
     * @param startMs Start offset in milliseconds
     */
    fun play(
        url: String,
        title: String? = null,
        headers: List<String>? = null,
        startMs: Long = 0L,
    ) {
        val player = _mediaPlayer ?: run {
            AppLogger.e("$TAG — play() called before initialize()")
            return
        }

        _isFinished.set(false)
        _isPlaying.set(false)
        _framePixels.set(null)
        AppLogger.i("$TAG — Phase 0.4: Opening stream: $url")

        // Build media options — ONLY start-time, title, and headers
        val options = mutableListOf<String>()
        if (startMs > 0) {
            options.add(":start-time=${startMs / 1000f}")
            AppLogger.i("$TAG — Phase 0.4:   start-time=${startMs / 1000f}s")
        }
        if (title != null) {
            options.add(":meta-title=$title")
            AppLogger.i("$TAG — Phase 0.4:   meta-title=$title")
        }
        headers?.forEach { h ->
            options.add(":http-set=$h")
            AppLogger.i("$TAG — Phase 0.4:   http-set (header)")
        }

        AppLogger.i("$TAG — Phase 0.4: Calling media().play() with ${options.size} options")
        player.media().play(url, *options.toTypedArray())
        AppLogger.i("$TAG — Phase 0.4: media().play() called OK")
    }

    fun pause() {
        val p = _mediaPlayer ?: return
        p.controls().pause()
        AppLogger.i("$TAG — [CTRL] pause()")
    }

    fun resume() {
        val p = _mediaPlayer ?: return
        p.controls().play()
        AppLogger.i("$TAG — [CTRL] resume()")
    }

    fun togglePause() {
        val p = _mediaPlayer ?: return
        if (_isPlaying.get()) {
            p.controls().pause()
            AppLogger.i("$TAG — [CTRL] togglePause() → pause")
        } else {
            p.controls().play()
            AppLogger.i("$TAG — [CTRL] togglePause() → play")
        }
    }

    fun stop() {
        val p = _mediaPlayer ?: return
        p.controls().stop()
        _isPlaying.set(false)
        _isFinished.set(true)
        _framePixels.set(null)
        AppLogger.i("$TAG — [CTRL] stop()")
    }

    /**
     * Seeks to the given position in milliseconds.
     * Converts to a 0.0–1.0 position ratio for vlcj.
     */
    fun seek(positionMs: Long) {
        val dur = _durationMs.get()
        if (dur > 0) {
            val ratio = (positionMs.toFloat() / dur.toFloat()).coerceIn(0f, 1f)
            _mediaPlayer?.controls()?.setPosition(ratio)
            AppLogger.i("$TAG — [CTRL] seek($positionMs ms, ratio=$ratio)")
        } else {
            AppLogger.w("$TAG — [CTRL] seek($positionMs ms) skipped: duration unknown")
        }
    }

    /**
     * Sets volume (0–100).
     */
    fun setVolume(vol: Int) {
        val clamped = vol.coerceIn(0, 100)
        _volume.set(clamped)
        _mediaPlayer?.audio()?.setVolume(clamped)
        AppLogger.i("$TAG — [CTRL] setVolume($clamped)")
    }

    // ── Cleanup ──────────────────────────────────────────────────────

    /**
     * Releases all VLC resources. Safe to call multiple times.
     */
    fun release() {
        AppLogger.i("$TAG — [CLEANUP] release() starting.")

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

        AppLogger.i("$TAG — [CLEANUP] release() complete")
    }
}

