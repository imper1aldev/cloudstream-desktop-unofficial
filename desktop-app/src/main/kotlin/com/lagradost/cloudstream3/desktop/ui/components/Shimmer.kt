package com.lagradost.cloudstream3.desktop.ui.components

import androidx.compose.animation.animateColor
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import com.lagradost.cloudstream3.desktop.ui.components.LocalDesktopTheme

/**
 * Applies a pulsing shimmer effect to the background of a composable.
 * Used for loading placeholders.
 */
fun Modifier.shimmerBackground(): Modifier = composed {
    val transition = rememberInfiniteTransition(label = "ShimmerTransition")
    
    val baseColor = LocalDesktopTheme.current.SurfaceCard.copy(alpha = 0.3f)
    val highlightColor = LocalDesktopTheme.current.SurfaceCard.copy(alpha = 0.7f)
    
    val color by transition.animateColor(
        initialValue = baseColor,
        targetValue = highlightColor,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "ShimmerColor"
    )

    this.then(background(color))
}
