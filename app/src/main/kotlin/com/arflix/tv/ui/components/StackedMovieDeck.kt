package com.arflix.tv.ui.components

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import coil.size.Precision
import com.arflix.tv.data.model.MediaItem
import com.arflix.tv.data.model.MediaType
import com.arflix.tv.ui.theme.ArflixTypography
import com.arflix.tv.util.parseRatingValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val LandscapeCardShape = RoundedCornerShape(20.dp)
private val CardBorderColor = Color(0x33FFFFFF)
private val FrontCardBorderColor = Color(0x55FFFFFF)

/**
 * 16:9 Landscape Stacked Movie Deck.
 *
 * Implements a horizontal deck of large landscape 16:9 cards:
 * - Active front card (Movie 1) is dominant, positioned toward the left side, occupying ~48%
 *   of screen width with a strict 16:9 aspect ratio.
 * - Behind the front card, 3-4 subsequent 16:9 landscape cards are placed, progressively offset
 *   toward the right so only their right strips are exposed, with symmetrical vertical step-downs.
 * - Active card hosts its own backdrop, logo/title, rating, year, genre, and description.
 * - Swiping smoothly transitions Movie 2 into Movie 1's exact left position and size.
 */
@Composable
fun StackedMovieDeck(
    items: List<MediaItem>,
    currentIndex: Int,
    onCurrentIndexChanged: (Int) -> Unit,
    onDetails: (MediaItem) -> Unit,
    cardLogoUrls: Map<String, String> = emptyMap(),
    cardImdbRatings: Map<String, String> = emptyMap(),
    modifier: Modifier = Modifier,
    maxVisibleCards: Int = 5,
    autoAdvanceDurationMs: Long = 10000L
) {
    if (items.isEmpty()) return

    val density = LocalDensity.current
    val coroutineScope = rememberCoroutineScope()

    BoxWithConstraints(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        val totalAvailableWidth = maxWidth
        // Dominant center card occupies ~64% of total available width to maximize presence and eliminate wasted side space
        val centerCardWidth = (totalAvailableWidth * 0.64f).coerceIn(380.dp, 840.dp)
        val centerCardHeight = centerCardWidth * (9f / 16f)

        // Generous peek width per stacked wing card so side tiles are clearly visible
        val remainingWingSpace = (totalAvailableWidth - centerCardWidth) / 2f
        val stepOffset = (remainingWingSpace * 0.42f).coerceIn(48.dp, 84.dp)

        val stepOffsetPx = with(density) { stepOffset.toPx() }
        val cardWidthPx = with(density) { centerCardWidth.toPx() }

        val animOffset = remember { Animatable(0f) }
        var isDragging by remember { mutableStateOf(false) }
        var lastInteractionTime by remember { mutableStateOf(System.currentTimeMillis()) }

        val context = LocalContext.current
        val imageLoader = remember(context) { context.imageLoader }

        // Preload / preheat Coil images for visible cards around currentIndex
        LaunchedEffect(currentIndex, items, cardLogoUrls) {
            if (items.isEmpty()) return@LaunchedEffect
            val preloadOffsets = listOf(-2, -1, 0, 1, 2, 3)
            for (rel in preloadOffsets) {
                val rawIdx = (currentIndex + rel) % items.size
                val idx = if (rawIdx < 0) rawIdx + items.size else rawIdx
                val itm = items.getOrNull(idx) ?: continue
                val bdUrl = itm.backdrop ?: itm.image
                if (!bdUrl.isNullOrBlank()) {
                    imageLoader.enqueue(
                        ImageRequest.Builder(context)
                            .data(bdUrl)
                            .precision(Precision.INEXACT)
                            .allowHardware(true)
                            .build()
                    )
                }
                val lgUrl = cardLogoUrls["${itm.mediaType}_${itm.id}"]
                if (!lgUrl.isNullOrBlank()) {
                    imageLoader.enqueue(
                        ImageRequest.Builder(context)
                            .data(lgUrl)
                            .precision(Precision.INEXACT)
                            .allowHardware(true)
                            .build()
                    )
                }
            }
        }

        // Auto advance when idle
        LaunchedEffect(currentIndex, isDragging, lastInteractionTime, autoAdvanceDurationMs, items.size) {
            if (autoAdvanceDurationMs <= 0 || items.size <= 1 || isDragging) return@LaunchedEffect
            delay(autoAdvanceDurationMs)
            if (!isDragging && System.currentTimeMillis() - lastInteractionTime >= autoAdvanceDurationMs) {
                animOffset.animateTo(
                    targetValue = -cardWidthPx,
                    animationSpec = tween(durationMillis = 380, easing = FastOutSlowInEasing)
                )
                onCurrentIndexChanged((currentIndex + 1) % items.size)
                animOffset.snapTo(0f)
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(centerCardHeight + 24.dp)
                .pointerInput(items.size, currentIndex) {
                    if (items.size <= 1) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragStart = {
                            isDragging = true
                            lastInteractionTime = System.currentTimeMillis()
                        },
                        onDragEnd = {
                            isDragging = false
                            lastInteractionTime = System.currentTimeMillis()
                            val currentDrag = animOffset.value
                            val threshold = cardWidthPx * 0.16f
                            coroutineScope.launch {
                                if (currentDrag < -threshold) {
                                    // Swiped left -> advance to next movie seamlessly
                                    animOffset.animateTo(
                                        targetValue = -cardWidthPx,
                                        animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing)
                                    )
                                    onCurrentIndexChanged((currentIndex + 1) % items.size)
                                    animOffset.snapTo(0f)
                                } else if (currentDrag > threshold) {
                                    // Swiped right -> go to previous movie seamlessly
                                    animOffset.animateTo(
                                        targetValue = cardWidthPx,
                                        animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing)
                                    )
                                    val prevIndex = if (currentIndex - 1 < 0) items.size - 1 else currentIndex - 1
                                    onCurrentIndexChanged(prevIndex)
                                    animOffset.snapTo(0f)
                                } else {
                                    animOffset.animateTo(
                                        targetValue = 0f,
                                        animationSpec = spring(
                                            dampingRatio = Spring.DampingRatioMediumBouncy,
                                            stiffness = Spring.StiffnessMedium
                                        )
                                    )
                                }
                            }
                        },
                        onDragCancel = {
                            isDragging = false
                            coroutineScope.launch {
                                animOffset.animateTo(0f, spring(stiffness = Spring.StiffnessMedium))
                            }
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            val newTarget = (animOffset.value + dragAmount * 0.95f)
                                .coerceIn(-cardWidthPx * 0.8f, cardWidthPx * 0.8f)
                            coroutineScope.launch {
                                animOffset.snapTo(newTarget)
                            }
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            val dragRatio = (animOffset.value / cardWidthPx).coerceIn(-1f, 1f)

            val candidateIndices = when {
                dragRatio < -0.01f -> listOf(0, -1, 1, -2, 2, -3, 3)
                dragRatio > 0.01f -> listOf(0, 1, -1, 2, -2, 3, -3)
                else -> listOf(0, -1, 1, -2, 2)
            }

            val renderedCards = candidateIndices
                .map { rel ->
                    val rawIndex = (currentIndex + rel) % items.size
                    val itemIndex = if (rawIndex < 0) rawIndex + items.size else rawIndex
                    rel to itemIndex
                }
                .distinctBy { it.second }
                .map { (rel, itemIndex) ->
                    val item = items[itemIndex]
                    val effectiveRelativePos = rel + dragRatio
                    val absPos = kotlin.math.abs(effectiveRelativePos)

                    val scale = (1f - (absPos * 0.085f)).coerceIn(0.78f, 1.0f)
                    val compensation = if (effectiveRelativePos > 0f) {
                        (cardWidthPx / 2f) * (1f - scale)
                    } else if (effectiveRelativePos < 0f) {
                        -(cardWidthPx / 2f) * (1f - scale)
                    } else {
                        0f
                    }
                    val translationX = (effectiveRelativePos * stepOffsetPx) + compensation
                    val elevation = ((3f - absPos) * 5f).coerceIn(2f, 16f).dp
                    val dimAlpha = (absPos * 0.22f).coerceIn(0f, 0.60f)
                    val zIndexVal = 10f - (absPos * 2.5f)

                    val cardAlpha = when {
                        absPos > 2.5f -> (3f - absPos).coerceIn(0f, 1f)
                        else -> 1f
                    }

                    val contentAlpha = (1f - (absPos * 1.5f)).coerceIn(0f, 1f)
                    val scrimAlpha = (1f - (absPos * 0.35f)).coerceIn(0.45f, 1.0f)

                    CardRenderInfo(
                        item = item,
                        itemIndex = itemIndex,
                        rel = rel,
                        effectiveRelativePos = effectiveRelativePos,
                        absPos = absPos,
                        scale = scale,
                        translationX = translationX,
                        elevation = elevation,
                        dimAlpha = dimAlpha,
                        zIndexVal = zIndexVal,
                        cardAlpha = cardAlpha,
                        contentAlpha = contentAlpha,
                        scrimAlpha = scrimAlpha
                    )
                }
                .sortedByDescending { it.absPos }

            for (card in renderedCards) {
                key("${card.item.mediaType}_${card.item.id}_${card.itemIndex}") {
                    LandscapeDeckCard(
                        item = card.item,
                        absPos = card.absPos,
                        contentAlpha = card.contentAlpha,
                        scrimAlpha = card.scrimAlpha,
                        cardWidth = centerCardWidth,
                        scale = card.scale,
                        translationX = card.translationX,
                        alpha = card.cardAlpha,
                        elevation = card.elevation,
                        dimAlpha = card.dimAlpha,
                        zIndex = card.zIndexVal,
                        cardLogoUrls = cardLogoUrls,
                        cardImdbRatings = cardImdbRatings,
                        onCardClick = {
                            lastInteractionTime = System.currentTimeMillis()
                            if (card.absPos < 0.5f) {
                                onDetails(card.item)
                            } else {
                                coroutineScope.launch {
                                    if (card.effectiveRelativePos > 0f) {
                                        animOffset.animateTo(
                                            targetValue = -cardWidthPx,
                                            animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing)
                                        )
                                        onCurrentIndexChanged((currentIndex + 1) % items.size)
                                        animOffset.snapTo(0f)
                                    } else if (card.effectiveRelativePos < 0f) {
                                        animOffset.animateTo(
                                            targetValue = cardWidthPx,
                                            animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing)
                                        )
                                        val prevIndex = if (currentIndex - 1 < 0) items.size - 1 else currentIndex - 1
                                        onCurrentIndexChanged(prevIndex)
                                        animOffset.snapTo(0f)
                                    }
                                }
                            }
                        }
                    )
                }
            }
        }
    }
}

private data class CardRenderInfo(
    val item: MediaItem,
    val itemIndex: Int,
    val rel: Int,
    val effectiveRelativePos: Float,
    val absPos: Float,
    val scale: Float,
    val translationX: Float,
    val elevation: Dp,
    val dimAlpha: Float,
    val zIndexVal: Float,
    val cardAlpha: Float,
    val contentAlpha: Float,
    val scrimAlpha: Float
)

/**
 * 16:9 Landscape Card within the horizontal deck.
 * Features continuous cinematic scrims and smooth crossfading clearlogo/metadata.
 */
@Composable
private fun LandscapeDeckCard(
    item: MediaItem,
    absPos: Float,
    contentAlpha: Float,
    scrimAlpha: Float,
    cardWidth: Dp,
    scale: Float,
    translationX: Float,
    alpha: Float,
    elevation: Dp,
    dimAlpha: Float,
    zIndex: Float,
    cardLogoUrls: Map<String, String>,
    cardImdbRatings: Map<String, String>,
    onCardClick: () -> Unit
) {
    val context = LocalContext.current
    val backdropUrl = item.backdrop ?: item.image
    val isFront = absPos < 0.5f

    val logoUrl = remember(item.id, item.mediaType, cardLogoUrls) {
        cardLogoUrls["${item.mediaType}_${item.id}"]
    }

    val dynamicImdb = cardImdbRatings["${item.mediaType}_${item.id}"]
    val ratingString = remember(item.id, dynamicImdb, item.imdbRating, item.tmdbRating, item.rating) {
        when {
            !dynamicImdb.isNullOrBlank() && parseRatingValue(dynamicImdb) > 0f -> dynamicImdb
            parseRatingValue(item.imdbRating) > 0f -> item.imdbRating
            parseRatingValue(item.tmdbRating) > 0f -> item.tmdbRating
            parseRatingValue(item.rating) > 0f -> item.rating
            else -> ""
        }
    }

    val year = remember(item.id, item.releaseDate, item.year) {
        val rd = item.releaseDate
        if (!rd.isNullOrBlank()) {
            runCatching {
                val parsed = java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.ENGLISH).parse(rd)
                parsed?.let { java.text.SimpleDateFormat("yyyy", java.util.Locale.ENGLISH).format(it) }
            }.getOrNull() ?: item.year
        } else {
            item.year
        }
    }

    val primaryGenre = remember(item.id, item.genreIds, context) {
        item.genreIds.firstNotNullOfOrNull { id ->
            val res = if (item.mediaType == MediaType.TV) tvGenreNameRes(id) else movieGenreNameRes(id)
            res?.let { context.getString(it) }
        }.orEmpty()
    }

    val genreIcon = remember(primaryGenre) {
        genreEmojiFor(primaryGenre)
    }

    val cleanOverview = remember(item.id, item.overview) {
        item.overview
            .replace(Regex("<[^>]*>"), " ")
            .replace(Regex("&nbsp;"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    val textShadow = remember {
        Shadow(
            color = Color.Black.copy(alpha = 0.95f),
            offset = Offset(0f, 2f),
            blurRadius = 8f
        )
    }

    // Responsive scaling based on card size
    val isLargeCard = cardWidth >= 600.dp
    val logoMaxHeight = if (isLargeCard) 96.dp else 64.dp
    val logoMaxWidth = if (isLargeCard) 440.dp else 280.dp
    val titleFontSize = if (isLargeCard) 30.sp else 22.sp
    val metadataFontSize = if (isLargeCard) 14.5.sp else 12.sp
    val metadataIconSize = if (isLargeCard) 16.dp else 13.dp
    val overviewFontSize = if (isLargeCard) 13.5.sp else 11.5.sp
    val overviewLineHeight = if (isLargeCard) 19.sp else 16.sp
    val cardPaddingHorizontal = if (isLargeCard) 28.dp else 18.dp
    val cardPaddingBottom = if (isLargeCard) 24.dp else 14.dp

    Box(
        modifier = Modifier
            .zIndex(zIndex)
            .offset { IntOffset(translationX.roundToInt(), 0) }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                this.alpha = alpha
            }
            .width(cardWidth)
            .aspectRatio(16f / 9f)
            .shadow(elevation = elevation, shape = LandscapeCardShape, clip = false)
            .clip(LandscapeCardShape)
            .background(Color(0xFF141419))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onCardClick
            )
            .border(
                width = if (isFront) 1.5.dp else 1.dp,
                color = if (isFront) FrontCardBorderColor else CardBorderColor,
                shape = LandscapeCardShape
            )
    ) {
        // ── 1. Full-bleed Landscape Backdrop Image ───────────────────────────
        if (backdropUrl.isNotBlank()) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(backdropUrl)
                    .precision(Precision.INEXACT)
                    .allowHardware(true)
                    .crossfade(150)
                    .build(),
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }

        // ── 2. Cinematic Scrims ──────────────────────────────────────────────
        if (scrimAlpha > 0.01f) {
            // Horizontal scrim (left-to-right dark gradient for text legibility)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            colorStops = arrayOf(
                                0.00f to Color.Black.copy(alpha = 0.95f * scrimAlpha),
                                0.42f to Color.Black.copy(alpha = 0.85f * scrimAlpha),
                                0.72f to Color.Black.copy(alpha = 0.40f * scrimAlpha),
                                1.00f to Color.Transparent
                            )
                        )
                    )
            )

            // Bottom vertical scrim
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colorStops = arrayOf(
                                0.00f to Color.Transparent,
                                0.35f to Color.Transparent,
                                0.70f to Color.Black.copy(alpha = 0.65f * scrimAlpha),
                                1.00f to Color.Black.copy(alpha = 0.92f * scrimAlpha)
                            )
                        )
                    )
            )
        }

        // Dimming overlay on cards layered behind
        if (dimAlpha > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = dimAlpha))
            )
        }

        // ── 3. Internal Content Hierarchy for the Dominant Front Card ────────
        if (contentAlpha > 0.01f) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { this.alpha = contentAlpha }
                    .padding(horizontal = cardPaddingHorizontal, vertical = cardPaddingBottom),
                verticalArrangement = Arrangement.Bottom
            ) {
                // Title / Clearlogo (substantially increased size)
                if (!logoUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(logoUrl)
                            .precision(Precision.INEXACT)
                            .allowHardware(true)
                            .crossfade(false)
                            .build(),
                        contentDescription = item.title,
                        contentScale = ContentScale.Fit,
                        alignment = Alignment.CenterStart,
                        modifier = Modifier
                            .heightIn(max = logoMaxHeight)
                            .widthIn(max = logoMaxWidth)
                    )
                } else {
                    Text(
                        text = item.title.uppercase(),
                        style = ArflixTypography.heroTitle.copy(
                            fontSize = titleFontSize,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.4.sp,
                            shadow = textShadow
                        ),
                        color = Color(0xFFE50914), // Stylized red anchor
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.height(if (isLargeCard) 10.dp else 6.dp))

                // Metadata Row: ★ 7.3/10  ·  📅 2026  ·  👻 Horror (larger text & icons)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(if (isLargeCard) 8.dp else 5.dp)
                ) {
                    if (ratingString.isNotBlank()) {
                        Icon(
                            imageVector = Icons.Filled.Star,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(metadataIconSize)
                        )
                        Text(
                            text = "$ratingString/10",
                            style = ArflixTypography.caption.copy(
                                fontSize = metadataFontSize,
                                fontWeight = FontWeight.SemiBold,
                                shadow = textShadow
                            ),
                            color = Color.White
                        )
                    }

                    if (year.isNotBlank()) {
                        if (ratingString.isNotBlank()) {
                            Text(
                                text = "·",
                                style = ArflixTypography.caption.copy(fontSize = metadataFontSize, shadow = textShadow),
                                color = Color.White.copy(alpha = 0.6f)
                            )
                        }
                        Icon(
                            imageVector = Icons.Filled.DateRange,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.9f),
                            modifier = Modifier.size(metadataIconSize)
                        )
                        Text(
                            text = year,
                            style = ArflixTypography.caption.copy(
                                fontSize = metadataFontSize,
                                fontWeight = FontWeight.Medium,
                                shadow = textShadow
                            ),
                            color = Color.White.copy(alpha = 0.9f)
                        )
                    }

                    if (primaryGenre.isNotBlank()) {
                        if (ratingString.isNotBlank() || year.isNotBlank()) {
                            Text(
                                text = "·",
                                style = ArflixTypography.caption.copy(fontSize = metadataFontSize, shadow = textShadow),
                                color = Color.White.copy(alpha = 0.6f)
                            )
                        }
                        Text(
                            text = "$genreIcon $primaryGenre",
                            style = ArflixTypography.caption.copy(
                                fontSize = metadataFontSize,
                                fontWeight = FontWeight.Medium,
                                shadow = textShadow
                            ),
                            color = Color.White.copy(alpha = 0.9f)
                        )
                    }
                }

                if (cleanOverview.isNotBlank()) {
                    Spacer(modifier = Modifier.height(if (isLargeCard) 10.dp else 6.dp))
                    Text(
                        text = cleanOverview,
                        style = ArflixTypography.body.copy(
                            fontSize = overviewFontSize,
                            lineHeight = overviewLineHeight,
                            shadow = textShadow
                        ),
                        color = Color.White.copy(alpha = 0.88f),
                        maxLines = if (isLargeCard) 3 else 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/**
 * Contextual genre emoji mapping for clean metadata row display.
 */
private fun genreEmojiFor(genre: String): String {
    val lower = genre.lowercase()
    return when {
        lower.contains("horror") -> "👻"
        lower.contains("action") -> "⚔️"
        lower.contains("comedy") -> "😂"
        lower.contains("drama") -> "🎭"
        lower.contains("sci-fi") || lower.contains("science fiction") -> "🚀"
        lower.contains("fantasy") -> "✨"
        lower.contains("animation") || lower.contains("anime") -> "🎨"
        lower.contains("thriller") || lower.contains("mystery") -> "🔍"
        lower.contains("crime") -> "🕵️"
        lower.contains("adventure") -> "🧭"
        lower.contains("romance") -> "❤️"
        lower.contains("documentary") -> "📽️"
        lower.contains("family") || lower.contains("kids") -> "👨‍👩‍👧"
        lower.contains("music") -> "🎵"
        lower.contains("war") -> "🪖"
        lower.contains("western") -> "🤠"
        else -> "🎬"
    }
}
