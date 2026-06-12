package com.lagradost.cloudstream3.desktop.ui.screens.home

import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil3.compose.AsyncImage
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.desktop.ui.components.DesktopUi
import com.lagradost.cloudstream3.desktop.ui.components.LocalDesktopTheme
import com.lagradost.cloudstream3.desktop.ui.screens.details.GlobalDetailsCache
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.common.logging.AppLogger
import com.lagradost.common.storage.DesktopBookmark
import com.lagradost.common.storage.DesktopDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue

data class HeroMeta(
    val title: String?,
    val backdropUrl: String?,
    val tags: List<String>,
    val plot: String?,
    val score: String?,
    val year: Int?,
)

object HeroCache {
    val cache = java.util.concurrent.ConcurrentHashMap<String, HeroMeta>()
}

fun cleanHeroTitle(raw: String): String {
    return raw.replace(Regex("""\s*\(\d{4}\).*"""), "")
        .replace(Regex("""\s*(?i)(dual audio|720p|1080p|480p|2160p|webrip|web-dl|hdtv|bluray).*"""), "")
        .replace(Regex("""\s*[\[\{].*"""), "")
        .split(Regex("[\\(\\[\\{\\|]")).firstOrNull()?.trim() ?: raw.trim()
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun HomeHeroCarousel(items: List<SearchResponse>, provider: MainAPI?, onItemClick: (SearchResponse, String?) -> Unit) {
    if (items.isEmpty()) return

    val displayItems = items.take(10)
    val maxPages = displayItems.size * 1000
    val initialPage = maxPages / 2
    val pagerState = rememberPagerState(initialPage = initialPage, pageCount = { maxPages })
    val metaMap = remember { mutableStateMapOf<String, HeroMeta>() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(pagerState) {
        while (true) {
            delay(5000)
            if (!pagerState.isScrollInProgress) {
                try {
                    val next = if (pagerState.isScrollInProgress) pagerState.targetPage + 1 else pagerState.currentPage + 1
                    pagerState.animateScrollToPage(
                        page = next,
                        animationSpec = tween(durationMillis = 1200),
                    )
                } catch (e: kotlinx.coroutines.CancellationException) {
                    if (!isActive) throw e
                }
            }
        }
    }

    LaunchedEffect(pagerState.currentPage) {
        val pagesToFetch = listOf(
            pagerState.currentPage % displayItems.size,
            (pagerState.currentPage + 1) % displayItems.size,
        ).distinct()

        for (pageIdx in pagesToFetch) {
            val item = displayItems[pageIdx]
            val cacheKey = "${provider?.name}_${item.url}"
            if (metaMap.containsKey(item.url)) continue
            val existing = HeroCache.cache[cacheKey]
            if (existing != null) {
                metaMap[item.url] = existing
                continue
            }
            scope.launch(Dispatchers.IO) {
                try {
                    // FAST PATH: Instant TMDB lookup using a dummy LoadResponse
                    val dummyTitle = cleanHeroTitle(item.name)
                    
                    if (provider != null) {
                        val dummy = provider.newMovieLoadResponse(
                            name = dummyTitle,
                            url = item.url,
                            type = com.lagradost.cloudstream3.TvType.Movie,
                            dataUrl = item.url,
                        ) {
                            this.posterUrl = item.posterUrl
                        }
                        
                        // Pass a fake URL so enrich() doesn't overwrite the real cache with our empty dummy!
                        GlobalDetailsCache.enrich(dummy, "dummy_${item.url}") { /* screenshots not needed here */ }
                        
                        // Pull enriched values into HeroMeta instantly!
                        val backdropUrl = dummy.backgroundPosterUrl?.takeIf { it.isNotBlank() }
                        val title = dummy.name.takeIf { it.isNotBlank() && it != dummyTitle } ?: cleanHeroTitle(item.name)
                        val tags = dummy.tags?.take(4) ?: emptyList()
                        val plot = dummy.plot?.take(200)
                        val score = dummy.score?.toStringNull(0.5, 10)
                        val year = dummy.year

                        val meta = HeroMeta(title, backdropUrl, tags, plot, score, year)
                        HeroCache.cache[cacheKey] = meta
                        metaMap[item.url] = meta
                    } else {
                        // Fallback if no provider
                        val meta = HeroMeta(dummyTitle, null, emptyList(), null, null, null)
                        HeroCache.cache[cacheKey] = meta
                        metaMap[item.url] = meta
                    }

                    // SLOW PATH: Pre-cache the real details page for "instant click"
                    // Delay slightly to prioritize UI/Image loading
                    delay(1500)
                    if (provider != null && !GlobalDetailsCache.cache.containsKey(item.url)) {
                        try {
                            GlobalDetailsCache.fetchRaw(provider, item.url)
                        } catch (e: Exception) {
                            // Ignore scrape failures in background pre-caching
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    AppLogger.e("HomeScreen", "Failed to enrich hero metadata for ${item.name}", e)
                }
            }
        }
    }

    // The actual surface color the content rows sit on (SurfaceCard via MaterialTheme.colorScheme.surface)
    val surfaceColor = LocalDesktopTheme.current.SurfaceCard
    val heroFade = surfaceColor

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(550.dp) // Fixed height instead of aspect ratio so it doesn't get ridiculously tall on ultra-wides
            .padding(top = 16.dp, bottom = 0.dp)
            .graphicsLayer { clip = false },
    ) {
        // External bottom bleed removed as it's unnecessary with distinct rounded coverflow cards

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 220.dp), // Massive padding for significant peeking effect
            pageSpacing = 32.dp,
        ) { page ->
            val realPage = page % displayItems.size
            val item = displayItems[realPage]
            val posterUrl = provider?.fixUrlNull(item.posterUrl)
            val meta = metaMap[item.url]

            val backdropAlpha by androidx.compose.animation.core.animateFloatAsState(
                targetValue = if (meta?.backdropUrl != null) 1f else 0f,
                animationSpec = androidx.compose.animation.core.tween(1000),
                label = "backdropFade",
            )

            // Calculate scale and alpha for coverflow effect
            val rawOffset = (pagerState.currentPage - page) + pagerState.currentPageOffsetFraction
            val pageOffset = rawOffset.absoluteValue

            val cardScale = 1f - (pageOffset * 0.15f).coerceIn(0f, 0.15f)
            val cardAlpha = 1f - (pageOffset * 0.3f).coerceIn(0f, 0.3f)

            val density = androidx.compose.ui.platform.LocalDensity.current.density

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(1f - pageOffset) // Center card gets the highest zIndex so it stacks on top
                    .graphicsLayer {
                        scaleX = cardScale
                        scaleY = cardScale
                        alpha = cardAlpha

                        // Coverflow stacking logic:
                        // Pull the side cards strongly towards the center so they slide underneath
                        translationX = rawOffset * 150f * density
                    }
                    .clip(RoundedCornerShape(32.dp)), // Large rounded corners like the screenshot
            ) {
                // Blurred portrait poster — immediate placeholder
                if (posterUrl != null) {
                    AsyncImage(
                        model = posterUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().alpha(0.20f),
                    )
                }

                // Widescreen backdrop — full image visible, no crop
                if (meta?.backdropUrl != null) {
                    AsyncImage(
                        model = meta.backdropUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop, // Crop to fill the rounded card beautifully
                        modifier = Modifier.fillMaxSize().alpha(backdropAlpha),
                    )
                }

                // Vertical gradient — very subtle fade at the very bottom
                Box(
                    modifier = Modifier.fillMaxSize().background(
                        Brush.verticalGradient(
                            0.0f to Color.Transparent,
                            0.80f to Color.Transparent,
                            1.0f to heroFade.copy(alpha = 0.6f),
                        ),
                    ),
                )
                // Horizontal vignette — minimal subtle darkening for text legibility
                Box(
                    modifier = Modifier.fillMaxSize().background(
                        Brush.horizontalGradient(
                            0.0f to heroFade.copy(alpha = 0.4f),
                            0.20f to heroFade.copy(alpha = 0.15f),
                            0.40f to Color.Transparent,
                        ),
                    ),
                )

                // Foreground content (Metadata text directly on backdrop)
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(start = 56.dp, end = 56.dp, bottom = 48.dp, top = 24.dp), // Adjusted padding for rounded card
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.Start,
                ) {
                    if (posterUrl != null) {
                        AsyncImage(
                            model = posterUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .width(160.dp)
                                .aspectRatio(2f / 3f)
                                .clip(RoundedCornerShape(12.dp))
                                .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
                                .shadow(16.dp, RoundedCornerShape(12.dp)),
                        )
                        Spacer(modifier = Modifier.width(32.dp))
                    }

                    Column(
                        modifier = Modifier.weight(1f), // Take up remaining space comfortably
                    ) {
                        // Content-type badge
                        item.type?.let { tvType ->
                            val typeLabel = when (tvType) {
                                com.lagradost.cloudstream3.TvType.Movie -> "MOVIE"
                                com.lagradost.cloudstream3.TvType.TvSeries -> "SERIES"
                                com.lagradost.cloudstream3.TvType.Anime -> "ANIME"
                                com.lagradost.cloudstream3.TvType.AnimeMovie -> "ANIME FILM"
                                com.lagradost.cloudstream3.TvType.Live -> "LIVE"
                                else -> tvType.name.uppercase()
                            }
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color.White.copy(alpha = 0.15f))
                                    .border(0.5.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(6.dp))
                                    .padding(horizontal = 10.dp, vertical = 4.dp),
                            ) {
                                Text(
                                    typeLabel,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.5.sp,
                                )
                            }
                            Spacer(Modifier.height(12.dp))
                        }

                        // Title
                        val displayTitle = meta?.title ?: cleanHeroTitle(item.name)
                        if (displayTitle.isNotBlank()) {
                            Text(
                                text = displayTitle,
                                style = MaterialTheme.typography.displayMedium,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                lineHeight = 48.sp,
                            )
                        }

                        // Rating & Year
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (meta?.score != null) {
                                Icon(
                                    Icons.Default.Star,
                                    contentDescription = "Rating",
                                    tint = Color(0xFFFFD700), // Gold
                                    modifier = Modifier.size(22.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = meta.score,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                                Spacer(Modifier.width(16.dp))
                            }
                            if (meta?.year != null) {
                                Text(
                                    text = meta.year.toString(),
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                        }

                        // Genre pills
                        if (!meta?.tags.isNullOrEmpty()) {
                            Spacer(Modifier.height(12.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                meta!!.tags.forEach { tag ->
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(20.dp))
                                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f))
                                            .padding(horizontal = 12.dp, vertical = 6.dp),
                                    ) {
                                        Text(tag, color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            }
                        }

                        // Plot synopsis
                        if (!meta?.plot.isNullOrBlank()) {
                            Spacer(Modifier.height(16.dp))
                            Text(
                                text = meta!!.plot!!,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
                                fontSize = 14.sp,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                                lineHeight = 22.sp,
                            )
                        }

                        Spacer(Modifier.height(26.dp))

                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Button(
                                onClick = { onItemClick(item, meta?.backdropUrl) },
                                colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.2f), contentColor = Color.White),
                                shape = RoundedCornerShape(24.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.5f)),
                                contentPadding = PaddingValues(horizontal = 32.dp, vertical = 16.dp),
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(22.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Play / View Details", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            }

                            val bookmarkId = if (provider != null) "${provider.name}_${item.url.hashCode()}" else ""
                            var isBookmarked by remember(bookmarkId) { mutableStateOf(if (bookmarkId.isNotEmpty()) DesktopDataStore.isBookmarked(bookmarkId) else false) }

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
                                            ),
                                        )
                                    }
                                    isBookmarked = !isBookmarked
                                },
                                modifier = Modifier
                                    .size(48.dp)
                                    .background(Color.White.copy(alpha = 0.2f), CircleShape)
                                    .border(1.dp, Color.White.copy(alpha = 0.5f), CircleShape),
                            ) {
                                Icon(
                                    imageVector = if (isBookmarked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                    contentDescription = "Bookmark",
                                    tint = if (isBookmarked) Color.Red else Color.White,
                                    modifier = Modifier.size(24.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

        // Left/Right buttons
        IconButton(
            onClick = {
                scope.launch {
                    val next = if (pagerState.isScrollInProgress) pagerState.targetPage - 1 else pagerState.currentPage - 1
                    pagerState.animateScrollToPage(
                        page = next,
                        animationSpec = androidx.compose.animation.core.tween(durationMillis = 1000),
                    )
                }
            },
            modifier = Modifier.align(Alignment.CenterStart).padding(start = 16.dp).background(Color.Black.copy(alpha = 0.3f), CircleShape),
        ) {
            Icon(Icons.Default.KeyboardArrowLeft, contentDescription = "Previous", tint = Color.White, modifier = Modifier.size(32.dp))
        }

        IconButton(
            onClick = {
                scope.launch {
                    val next = if (pagerState.isScrollInProgress) pagerState.targetPage + 1 else pagerState.currentPage + 1
                    pagerState.animateScrollToPage(
                        page = next,
                        animationSpec = androidx.compose.animation.core.tween(durationMillis = 1000),
                    )
                }
            },
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 16.dp).background(Color.Black.copy(alpha = 0.3f), CircleShape),
        ) {
            Icon(Icons.Default.KeyboardArrowRight, contentDescription = "Next", tint = Color.White, modifier = Modifier.size(32.dp))
        }

        // Pill-style page indicators
        Row(
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(displayItems.size) { dotIndex ->
                val isSelected = (pagerState.currentPage % displayItems.size) == dotIndex
                val dotWidth by androidx.compose.animation.core.animateDpAsState(
                    targetValue = if (isSelected) 24.dp else 6.dp,
                    animationSpec = androidx.compose.animation.core.spring(stiffness = 600f),
                    label = "dot_w_$dotIndex",
                )
                Box(
                    modifier = Modifier
                        .height(6.dp)
                        .width(dotWidth)
                        .clip(CircleShape)
                        .background(if (isSelected) DesktopUi.Accent else DesktopUi.TextMuted.copy(alpha = 0.5f)),
                )
            }
        }
    }
}
