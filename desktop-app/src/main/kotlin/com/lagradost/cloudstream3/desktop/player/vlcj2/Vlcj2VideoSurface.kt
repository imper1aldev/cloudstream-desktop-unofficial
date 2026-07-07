package com.lagradost.cloudstream3.desktop.player.vlcj2

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.lagradost.common.logging.AppLogger
import kotlinx.coroutines.isActive

/**
 * Composable that renders Vlcj2Engine video frames onto a Compose Canvas.
 *
 * Polls [engine] each composition frame tick via [withFrameNanos], converts
 * the BGRA_8888 pixel buffer to an [ImageBitmap] via Skia, and draws it
 * on a [Canvas] with optional aspect-ratio preservation.
 *
 * @param engine               the Vlcj2Engine providing frame data
 * @param modifier             layout modifier
 * @param maintainAspectRatio  if true, letterboxes to video aspect ratio;
 *                             if false, fills the entire canvas
 */
@Composable
fun Vlcj2VideoSurface(
    engine: Vlcj2Engine,
    modifier: Modifier = Modifier,
    maintainAspectRatio: Boolean = true,
) {
    // ── Frame state ────────────────────────────────────────────────────
    var framePixels by remember { mutableStateOf<ByteArray?>(null) }
    var frameWidth by remember { mutableIntStateOf(0) }
    var frameHeight by remember { mutableIntStateOf(0) }

    // ── Frame polling: one poll per composition frame ──────────────────
    LaunchedEffect(engine) {
        while (isActive) {
            withFrameNanos { }
            val pixels = engine.currentFramePixels
            if (pixels != null) {
                framePixels = pixels.copyOf()
                frameWidth = engine.videoWidth
                frameHeight = engine.videoHeight
            }
        }
    }

    // ── ImageBitmap creation (recomputed only when pixel data changes) ─
    val imageBitmap = remember(framePixels, frameWidth, frameHeight) {
        val px = framePixels ?: return@remember null
        buildImageBitmap(px, frameWidth, frameHeight)
    }

    // ── Render: Box with Canvas ────────────────────────────────────────
    Box(modifier = modifier.background(Color.Black)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
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
                    // Video wider than canvas → letterbox top/bottom
                    val fitH = (cw / videoAspect).toInt()
                    IntOffset(0, (ch - fitH) / 2) to IntSize(cw, fitH)
                } else {
                    // Video taller than canvas → letterbox left/right
                    val fitW = (ch * videoAspect).toInt()
                    IntOffset((cw - fitW) / 2, 0) to IntSize(fitW, ch)
                }
            } else {
                // Fill entire canvas, ignore aspect ratio
                IntOffset.Zero to IntSize(size.width.toInt(), size.height.toInt())
            }

            drawImage(
                image = bitmap,
                dstOffset = dstOff,
                dstSize = dstSz,
            )
        }
    }
}

/**
 * Converts BGRA_8888 ByteArray → Compose ImageBitmap via Skia directly.
 *
 * RV32 on little-endian (x86/x64) stores pixels as [B, G, R, A] per pixel,
 * matching Skia's BGRA_8888 format exactly. No byte swap needed.
 *
 * Returns null if dimensions are invalid, pixels is null, or buffer is too small.
 */
private fun buildImageBitmap(pixels: ByteArray?, width: Int, height: Int): ImageBitmap? {
    if (width <= 0 || height <= 0 || pixels == null || pixels.size < width * height * 4) return null
    return try {
        val imageInfo = org.jetbrains.skia.ImageInfo(
            width, height,
            org.jetbrains.skia.ColorType.BGRA_8888,
            org.jetbrains.skia.ColorAlphaType.PREMUL,
        )
        val skiaBitmap = org.jetbrains.skia.Bitmap()
        skiaBitmap.installPixels(imageInfo, pixels, width * 4)
        skiaBitmap.asComposeImageBitmap()
    } catch (e: Exception) {
        AppLogger.w("VLCJ2_SURFACE — buildImageBitmap failed: ${e.message}")
        null
    }
}
