package com.lagradost.cloudstream3.desktop.player.vlcj

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.*
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Composable that renders VLCJ video frames onto a Compose Canvas.
 *
 * Polls [engine] each display refresh tick and converts the pixel buffer
 * to an [ImageBitmap] for drawing.
 *
 * @param engine            the VLCJ engine providing frames
 * @param modifier          layout modifier
 * @param maintainAspectRatio  if true, letterboxes to video aspect ratio
 */
@Composable
fun VlcjVideoSurface(
    engine: VlcjEngine,
    modifier: Modifier = Modifier,
    maintainAspectRatio: Boolean = true,
) {
    // State hoisted to composable level (outside Canvas)
    var framePixels by remember { mutableStateOf<ByteArray?>(null) }
    var frameWidth by remember { mutableIntStateOf(0) }
    var frameHeight by remember { mutableIntStateOf(0) }

    // Poll engine each frame tick
    LaunchedEffect(engine) {
        while (isActive) {
            withContext(Dispatchers.Main) {
                val pixels = engine.currentFramePixels
                if (pixels != null) {
                    // Defensive copy: Skia's Bitmap.installPixels() references the
                    // byte array directly. The render callback (native thread) may
                    // reuse the same array next frame, so we snapshot here.
                    framePixels = pixels.copyOf()
                    frameWidth = engine.videoWidth
                    frameHeight = engine.videoHeight
                }
            }
            withFrameMillis { }
        }
    }

    // Build ImageBitmap from pixel buffer (recomputed when pixels/dims change)
    val imageBitmap = remember(framePixels, frameWidth, frameHeight) {
        val px = framePixels ?: return@remember null
        buildImageBitmap(px, frameWidth, frameHeight)
    }

    // Render
    Canvas(
        modifier = modifier.background(Color.Black)
    ) {
        val bitmap = imageBitmap ?: return@Canvas
        val w = frameWidth
        val h = frameHeight
        if (w <= 0 || h <= 0) return@Canvas

        val (dstOff, dstSz) = if (maintainAspectRatio) {
            val cw = size.width.toInt()
            val ch = size.height.toInt()
            val videoAspect = w.toFloat() / h.toFloat()
            val canvasAspect = cw.toFloat() / ch.toFloat()

            if (videoAspect > canvasAspect) {
                val fitH = (cw / videoAspect).toInt()
                IntOffset(0, (ch - fitH) / 2) to IntSize(cw, fitH)
            } else {
                val fitW = (ch * videoAspect).toInt()
                IntOffset((cw - fitW) / 2, 0) to IntSize(fitW, ch)
            }
        } else {
            IntOffset.Zero to IntSize(size.width.toInt(), size.height.toInt())
        }

        drawImage(
            image = bitmap,
            dstOffset = dstOff,
            dstSize = dstSz,
        )
    }
}

/**
 * Converts BGRA_8888 ByteArray → Compose ImageBitmap via Skia directly.
 * RV32 on little-endian (x86/x64) stores pixels as [B, G, R, A] per pixel,
 * matching Skia's BGRA_8888 format exactly. No byte swap or PNG roundtrip needed.
 */
private fun buildImageBitmap(pixels: ByteArray?, width: Int, height: Int): ImageBitmap? {
    if (width <= 0 || height <= 0 || pixels == null || pixels.size < width * height * 4) return null

    return try {
        val imageInfo = org.jetbrains.skia.ImageInfo(
            width, height,
            org.jetbrains.skia.ColorType.BGRA_8888,
            org.jetbrains.skia.ColorAlphaType.PREMUL
        )
        val skiaBitmap = org.jetbrains.skia.Bitmap()
        skiaBitmap.installPixels(imageInfo, pixels, width * 4)
        skiaBitmap.asComposeImageBitmap()
    } catch (_: Exception) { null }
}

/**
 * State holder for playback controls exposed to UI layers.
 */
class VlcjPlayerState(
    val engine: VlcjEngine,
) {
    val isPlaying: Boolean get() = engine.isPlaying
    val positionMs: Long get() = engine.positionMs
    val durationMs: Long get() = engine.durationMs
    val volume: Int get() = engine.volume
    val isFinished: Boolean get() = engine.isFinished

    fun play(url: String, title: String? = null, headers: List<String>? = null, startMs: Long = 0L) {
        engine.play(url, title, headers, startMs)
    }

    fun pause() = engine.pause()
    fun resume() = engine.resume()
    fun togglePause() = engine.togglePause()
    fun stop() = engine.stop()
    fun seek(posMs: Long) = engine.seek(posMs)
    fun setVolume(vol: Int) = engine.setVolume(vol)
    fun release() = engine.release()
}
