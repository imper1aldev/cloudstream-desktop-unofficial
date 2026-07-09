package com.lagradost.cloudstream3.desktop.player.vlcj2

import com.lagradost.common.logging.AppLogger
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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

data class SubtitleTrackInfo(val id: Int, val name: String)

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
    private val _muted = AtomicBoolean(false)
    private val _previousVolume = AtomicInteger(100)
    private val _isPlaying = AtomicBoolean(false)
    private val _isFinished = AtomicBoolean(false)

    private var _displayCounter = AtomicInteger(0)

    @Volatile
    var isReinitializing: Boolean = false
        private set

    /**
     * Thread-safe error callback. Invoked on [Dispatchers.Main] when a VLC playback error occurs.
     * Set from any thread; the callback function will be dispatched to the Main thread.
     */
    var onError: ((message: String) -> Unit)? = null

    // ── Last-playback state (for reinitialize) ────────────────────────
    private var _lastUrl: String = ""
    private var _lastTitle: String? = null
    private var _lastHeaders: List<String>? = null
    private var _lastStartMs: Long = 0L
    private var _isHlsStream: Boolean = false

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
        "--avcodec-hw=none"
    )

    // ── Public state queries ─────────────────────────────────────────

    val isInitialized: Boolean get() = _mediaPlayer != null

    val isPlaying: Boolean get() = _isPlaying.get()

    val positionMs: Long get() = _positionMs.get()

    val durationMs: Long get() = _durationMs.get()

    val volume: Int get() = _volume.get()

    val isMuted: Boolean get() = try {
        _mediaPlayer?.audio()?.isMute() ?: _muted.get()
    } catch (e: Exception) {
        _muted.get()
    }

    val isFinished: Boolean get() = _isFinished.get()

    val currentFramePixels: ByteArray? get() = _framePixels.get()

    val isHlsStream: Boolean get() = _isHlsStream

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
            val count = _displayCounter.incrementAndGet()
            if (count % 60 == 1) {
                AppLogger.i("$TAG — [RENDER] display() alive count=$count isPlaying=${_isPlaying.get()}")
            }
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
            onError?.let { cb ->
                CoroutineScope(Dispatchers.Main).launch { cb("VLC playback error") }
            }
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

        override fun buffering(mediaPlayer: MediaPlayer, newCache: Float) {
            AppLogger.i("$TAG — [EVENT] buffering($newCache)")
        }

        override fun seekableChanged(mediaPlayer: MediaPlayer, newSeekable: Int) {
            AppLogger.i("$TAG — [EVENT] seekableChanged(${if (newSeekable != 0) "seekable" else "not-seekable"})")
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
        isHls: Boolean = false,
    ) {
        val player = _mediaPlayer ?: run {
            AppLogger.e("$TAG — play() called before initialize()")
            return
        }

        _isFinished.set(false)
        _isPlaying.set(false)
        _framePixels.set(null)

        // Save last-playback state for reinitialize()
        _lastUrl = url
        _lastTitle = title
        _lastHeaders = headers
        _lastStartMs = startMs
        _isHlsStream = isHls

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
        AppLogger.i("$TAG — [SEEK] positionMs=$positionMs dur=$dur thread=${Thread.currentThread().name}")
        if (dur > 0) {
            val ratio = (positionMs.toFloat() / dur.toFloat()).coerceIn(0f, 1f)
            try {
                _mediaPlayer?.controls()?.setPosition(ratio)
                AppLogger.i("$TAG — [SEEK] OK ratio=$ratio")
            } catch (e: Exception) {
                AppLogger.e("$TAG — [SEEK] setPosition CRASHED", e)
            }
        } else {
            AppLogger.w("$TAG — [CTRL] seek($positionMs ms) skipped: duration unknown")
        }
    }

    /**
     * Seeks by stopping and restarting the current stream with :start-time.
     * Required for HLS where controls().setPosition() crashes due to
     * a race in libVLC's vout display lifecycle during segment transitions.
     *
     * Uses the same mechanism as server switch / resume, which works reliably.
     */
    fun seekByReopen(positionMs: Long) {
        val savedUrl = _lastUrl
        val savedTitle = _lastTitle
        val savedHeaders = _lastHeaders

        if (savedUrl.isBlank()) {
            AppLogger.w("$TAG — seekByReopen: no last URL, skipping")
            return
        }

        AppLogger.i("$TAG — [CTRL] seekByReopen($positionMs ms)")
        stopForSwitch()  // sin marcar _isFinished
        play(savedUrl, savedTitle, savedHeaders, positionMs, isHls = true)
    }

    /**
     * Sets volume (0–100).
     */
    fun setVolume(vol: Int) {
        val clamped = vol.coerceIn(0, 100)
        _volume.set(clamped)
        if (clamped > 0 && _muted.get()) {
            setMute(false)
        }
        try {
            _mediaPlayer?.audio()?.setVolume(clamped)
        } catch (e: Exception) {
            AppLogger.w("$TAG — [CTRL] setVolume failed", e)
        }
        AppLogger.i("$TAG — [CTRL] setVolume($clamped)")
    }

    fun setMute(mute: Boolean) {
        try {
            _mediaPlayer?.audio()?.setMute(mute)
        } catch (e: Exception) {
            AppLogger.w("$TAG — [CTRL] setMute failed", e)
        }
        _muted.set(mute)
        AppLogger.i("$TAG — [CTRL] setMute($mute)")
    }

    fun toggleMute() {
        setMute(!isMuted)
    }

    // ── Subpicture / Subtitle API (VLCJ 4.8.2 SubpictureApi) ────────

    /**
     * Returns the list of available subtitle tracks via VLCJ SubpictureApi.
     * Uses [uk.co.caprica.vlcj.player.base.SubpictureApi.trackDescriptions].
     */
    fun subtitleTracks(): List<SubtitleTrackInfo> {
        return _mediaPlayer?.subpictures()?.trackDescriptions()
            ?.map { SubtitleTrackInfo(it.id(), it.description()) }
            ?: emptyList()
    }

    /**
     * Sets the active subtitle track by ID.
     * Pass -1 to disable subtitles.
     */
    fun setSubtitleTrack(trackId: Int) {
        _mediaPlayer?.subpictures()?.setTrack(trackId)
    }

    /**
     * Loads an external subtitle file (e.g. .srt, .vtt).
     * Returns true if the file was accepted by VLC.
     */
    fun setSubtitleFile(path: String): Boolean {
        return _mediaPlayer?.subpictures()?.setSubTitleFile(path) ?: false
    }

    // ── HW mode reinitialize ──────────────────────────────────────────

    /**
     * Reinitialises the engine with a new hardware acceleration mode.
     * Saves current position, releases, re-initializes with flags, and resumes playback.
     *
     * @param hwMode One of "software", "agresivo", or "default" (no flags).
     * @return true if reinitialization succeeded, false on failure.
     */
    fun reinitialize(hwMode: String): Boolean {
        if (isReinitializing) return false
        isReinitializing = true
        return try {
            val savedPosition = _positionMs.get().coerceAtLeast(1)
                .let { if (it > 0) it else _lastStartMs }
            release()
            val flags = when (hwMode) {
                "software" -> listOf("--avcodec-hw=none")
                "agresivo" -> listOf("--avcodec-hw=any", "--avcodec-dxva2")
                else -> emptyList() // "default" → sin flags
            }
            initialize(extraArgs = flags)
            play(_lastUrl, _lastTitle, _lastHeaders, savedPosition)
            true
        } catch (e: Exception) {
            AppLogger.e("$TAG — reinitialize failed", e)
            try {
                // Guard: only re-initialize if release() actually destroyed the factory.
                // If _factory is non-null, release() failed mid-way; reuse existing.
                if (_factory == null) {
                    AppLogger.i("$TAG — reinitialize recovery: attempting default init")
                    initialize(extraArgs = emptyList())
                } else {
                    AppLogger.i("$TAG — reinitialize recovery: factory still present, reusing")
                }
                play(_lastUrl, _lastTitle, _lastHeaders, _lastStartMs)
                true
            } catch (e2: Exception) {
                AppLogger.e("$TAG — reinitialize recovery also failed", e2)
                onError?.let { cb ->
                    CoroutineScope(Dispatchers.Main).launch {
                        cb("No fue posible aplicar este modo de aceleración. Se restauró el modo predeterminado.")
                    }
                }
                false
            }
        } finally {
            isReinitializing = false
        }
    }

    // ── Stop for hot-switch ───────────────────────────────────────────

    /**
     * Stops playback without setting [isFinished] to true.
     * Used by hot-switch (server change) to preserve the "actively playing" state.
     */
    fun stopForSwitch() {
        _mediaPlayer?.controls()?.stop()
        _isPlaying.set(false)
        _framePixels.set(null)
        AppLogger.i("$TAG — [CTRL] stopForSwitch()")
    }

    // ── Cleanup ──────────────────────────────────────────────────────

    /**
     * Releases all VLC resources. Safe to call multiple times.
     */
    fun release() {
        AppLogger.i("$TAG — [CLEANUP] release() starting. thread=${Thread.currentThread().name}")

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

