package com.lagradost.cloudstream3.desktop.ui.screens.player.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lagradost.cloudstream3.desktop.ui.components.DesktopUi

@Composable
fun StreamLoadingOverlay(
    title: String,
    linkName: String,
    loadingStatus: String? = null,
    onCancel: (() -> Unit)? = null,
) {
    // Animated pulsing dots
    val infiniteTransition = rememberInfiniteTransition(label = "dots")
    val dot1Scale by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse, initialStartOffset = StartOffset(0)),
        label = "d1",
    )
    val dot2Scale by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse, initialStartOffset = StartOffset(200)),
        label = "d2",
    )
    val dot3Scale by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse, initialStartOffset = StartOffset(400)),
        label = "d3",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.75f)),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.widthIn(min = 320.dp, max = 420.dp),
            shape = RoundedCornerShape(24.dp),
            color = Color(0xE6121212),
        ) {
            Box {
                if (onCancel != null) {
                    IconButton(
                        onClick = onCancel,
                        modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = DesktopUi.TextMuted)
                    }
                }
                
                Column(
                    modifier = Modifier.padding(36.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                // Animated pulsing dots row
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(modifier = Modifier.size(12.dp).scale(dot1Scale).clip(CircleShape).background(DesktopUi.Accent))
                    Box(modifier = Modifier.size(12.dp).scale(dot2Scale).clip(CircleShape).background(DesktopUi.Accent))
                    Box(modifier = Modifier.size(12.dp).scale(dot3Scale).clip(CircleShape).background(DesktopUi.Accent))
                }

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = "Loading Stream",
                    color = DesktopUi.TextMuted,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 1.sp,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                )
                if (linkName.isNotBlank()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = linkName,
                        color = DesktopUi.Accent,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                if (loadingStatus != null) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = loadingStatus,
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Normal,
                        textAlign = TextAlign.Center,
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))
                Text(
                    text = "Please wait while we buffer the stream…",
                    color = DesktopUi.TextMuted,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                )
            }
            }
        }
    }
}

@Composable
fun PlayerLoadingOverlay(
    title: String,
    episodeText: String,
    linksFound: Int,
    onPlayNow: () -> Unit,
    onCancel: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.85f)),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.widthIn(min = 350.dp, max = 450.dp),
            shape = RoundedCornerShape(24.dp),
            color = Color(0xE6121212),
        ) {
            Column(
                modifier = Modifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(48.dp),
                    color = DesktopUi.Accent,
                    strokeWidth = 4.dp,
                )
                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = if (title.contains("Loading")) "Loading Streams" else "Loading Episode",
                    color = DesktopUi.TextMuted,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                if (episodeText.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = episodeText,
                        color = DesktopUi.TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = DesktopUi.SurfaceElevated,
                ) {
                    Text(
                        text = "$linksFound Stream${if (linksFound == 1) "" else "s"} Found",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        color = DesktopUi.Accent,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                    )
                }

                Spacer(modifier = Modifier.height(32.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    FilledTonalButton(
                        onClick = onCancel,
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = Color(0xFF2C2C2C),
                            contentColor = Color.White,
                        ),
                    ) {
                        Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Cancel")
                    }

                    Button(
                        onClick = onPlayNow,
                        enabled = linksFound > 0,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = DesktopUi.Accent,
                            contentColor = Color.White,
                        ),
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Play Now")
                    }
                }
            }
        }
    }
}
