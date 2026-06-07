package com.lagradost.cloudstream3.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.hoverable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.desktop.ui.theme.AppearanceConfig
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.common.storage.DesktopBookmark
import com.lagradost.common.storage.DesktopDataStore
import com.lagradost.common.storage.WatchHistory
import com.lagradost.player.impl.PlayerLinkHandler

@Composable
fun PosterCard(
    item: SearchResponse,
    provider: MainAPI?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    val imgUrl = provider?.fixUrlNull(item.posterUrl) ?: item.posterUrl
    val gridScale by AppearanceConfig.gridScale.collectAsState()
    val width = when (gridScale) {
        "Compact" -> 150.dp
        "Large" -> 220.dp
        else -> 190.dp
    }

    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    Surface(
        modifier = modifier
            .width(width)
            .posterHoverEffect()
            .clip(shape)
            .hoverable(interactionSource)
            .clickable(onClick = onClick),
        shape = shape,
        color = DesktopUi.SurfaceCard,
        tonalElevation = 2.dp,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f),
        ) {
            if (imgUrl != null) {
                // Blurred background fill — same image, blurred and desaturated
                // so the letterbox area matches the poster colours, not black
                AsyncImage(
                    model = imgUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .blur(24.dp),
                )
                // Dark overlay to tone down the blur
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.55f)),
                )
                // Actual poster — Fit so the full image is visible, no cropping
                AsyncImage(
                    model = imgUrl,
                    contentDescription = item.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                // No image placeholder
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(DesktopUi.SurfaceElevated),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        item.name.take(2).uppercase(),
                        color = DesktopUi.Accent,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            val bookmarkId = if (provider != null) "${provider.name}_${item.url.hashCode()}" else ""
            var isBookmarked by remember(bookmarkId) { mutableStateOf(if (bookmarkId.isNotEmpty()) DesktopDataStore.isBookmarked(bookmarkId) else false) }

            val bookmarkAlpha by androidx.compose.animation.core.animateFloatAsState(
                targetValue = if (isHovered || isBookmarked) 1f else 0f,
                label = "bookmarkAlpha"
            )

            if (bookmarkAlpha > 0f) {
                IconButton(
                    onClick = {
                        if (provider == null) return@IconButton
                        if (isBookmarked) {
                            DesktopDataStore.removeBookmark(bookmarkId)
                        } else {
                            DesktopDataStore.addBookmark(
                                DesktopBookmark(
                                    id = bookmarkId,
                                    name = item.name,
                                    url = item.url,
                                    apiName = provider.name,
                                    posterUrl = item.posterUrl,
                                )
                            )
                        }
                        isBookmarked = !isBookmarked
                    },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .graphicsLayer { alpha = bookmarkAlpha }
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.6f))
                ) {
                    Icon(
                        imageVector = if (isBookmarked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = "Bookmark",
                        tint = if (isBookmarked) Color.Red else Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // Gradient at the bottom with the title
            AnimatedVisibility(
                visible = isHovered,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.fadeOut()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                colorStops = arrayOf(
                                    0f to Color.Transparent,
                                    0.35f to Color.Black.copy(alpha = 0.7f),
                                    1f to Color.Black.copy(alpha = 0.92f),
                                ),
                            ),
                        )
                        .padding(horizontal = 10.dp, vertical = 10.dp),
                ) {
                    Column {
                        // Type badge
                        val typeLabel = item.quality?.name?.uppercase()
                            ?: if (item is com.lagradost.cloudstream3.AnimeSearchResponse && !item.dubStatus.isNullOrEmpty()) {
                                item.dubStatus!!.joinToString(" | ") {
                                    it.name.uppercase().replace("DUBBED", "DUB").replace("SUBBED", "SUB")
                                }
                            } else {
                                null
                            }
                        if (typeLabel != null) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color.White.copy(alpha = 0.25f))
                                    .border(0.5.dp, Color.White.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                                    .padding(horizontal = 5.dp, vertical = 2.dp),
                            ) {
                                Text(
                                    text = typeLabel,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    letterSpacing = 0.5.sp,
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                        }
                        Text(
                            text = item.name,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                            lineHeight = 16.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun WatchHistoryCard(
    history: WatchHistory,
    provider: MainAPI?,
    modifier: Modifier = Modifier,
    onRemove: () -> Unit,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    val gridScale by AppearanceConfig.gridScale.collectAsState()
    val width = when (gridScale) {
        "Compact" -> 120.dp
        "Large" -> 180.dp
        else -> 150.dp
    }

    Surface(
        modifier = modifier
            .width(width)
            .posterHoverEffect()
            .clip(shape)
            .clickable(onClick = onClick),
        shape = shape,
        color = DesktopUi.SurfaceCard,
        tonalElevation = 2.dp,
    ) {
        Column {
            val imgUrl = provider?.fixUrlNull(history.posterUrl) ?: history.posterUrl
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f),
            ) {
                if (imgUrl != null) {
                    AsyncImage(
                        model = imgUrl,
                        contentDescription = history.showName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(DesktopUi.SurfaceElevated),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("No Image", color = DesktopUi.TextMuted)
                    }
                }

                if (history.duration > 0) {
                    val progress = if (PlayerLinkHandler.isCompleted(history.position, history.duration)) {
                        1f
                    } else {
                        (history.position.toFloat() / history.duration.toFloat()).coerceIn(0f, 1f)
                    }
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(4.dp),
                        color = DesktopUi.Accent,
                        trackColor = Color.Black.copy(alpha = 0.5f),
                    )
                }

                if (history.season != null && history.episode != null) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(6.dp)
                            .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 3.dp),
                    ) {
                        Text(
                            "S${history.season} E${history.episode}",
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }

                IconButton(
                    onClick = onRemove,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(32.dp)
                        .background(Color.Black.copy(alpha = 0.85f), CircleShape),
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Remove from history",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            PosterTitleLabel(title = history.showName)
        }
    }
}
