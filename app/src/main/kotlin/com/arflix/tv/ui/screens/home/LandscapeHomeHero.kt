package com.arflix.tv.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.arflix.tv.R
import com.arflix.tv.data.model.MediaItem
import com.arflix.tv.data.model.Profile
import com.arflix.tv.ui.components.ProfileAvatarVisual
import com.arflix.tv.ui.components.StackedMovieDeck

/**
 * Landscape Home Hero section:
 * Hosts the horizontal 16:9 [StackedMovieDeck] aligned toward the left side of the screen,
 * backed by an ambient atmospheric backdrop crossfade and top profile/search controls.
 */
@Composable
fun LandscapeHomeHero(
    items: List<MediaItem>,
    cardLogoUrls: Map<String, String> = emptyMap(),
    cardImdbRatings: Map<String, String> = emptyMap(),
    currentProfile: Profile? = null,
    onNavigateToSearch: () -> Unit = {},
    onSwitchProfile: () -> Unit = {},
    onDetails: (MediaItem) -> Unit = {},
    activeIndex: Int = 0,
    onActiveIndexChanged: (Int) -> Unit = {},
    modifier: Modifier = Modifier
) {
    if (items.isEmpty()) return

    val configuration = LocalConfiguration.current

    // Dominant center card occupies ~64% of screen width in landscape
    val screenWidth = configuration.screenWidthDp.dp
    val centerCardWidth = ((screenWidth - 48.dp) * 0.64f).coerceIn(380.dp, 840.dp)
    val centerCardHeight = centerCardWidth * (9f / 16f)
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    // Accounts for status-bar padding, 46.dp header, 4.dp spacer, deck's 24.dp extra height, and 8.dp bottom padding
    val headerAndDeckExtra = 46.dp + 4.dp + 24.dp + 8.dp
    val heroHeight = statusBarTop + centerCardHeight + headerAndDeckExtra

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(heroHeight)
    ) {
        // Content Column: Header on top, Deck on bottom
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            // Header Row: Profile Avatar & Search
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 6.dp)
                    .zIndex(20f),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (currentProfile != null) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .clickable { onSwitchProfile() }
                    ) {
                        ProfileAvatarVisual(
                            profile = currentProfile,
                            letterFontSize = 14.sp,
                            iconPadding = 4.dp
                        )
                    }
                } else {
                    Spacer(modifier = Modifier.size(34.dp))
                }

                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = stringResource(R.string.search),
                    tint = Color.White,
                    modifier = Modifier
                        .size(26.dp)
                        .clickable { onNavigateToSearch() }
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // 16:9 Landscape Stacked Movie Deck (Centered bilateral layout)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 24.dp, bottom = 8.dp)
                    .zIndex(10f),
                contentAlignment = Alignment.Center
            ) {
                StackedMovieDeck(
                    items = items,
                    currentIndex = activeIndex,
                    onCurrentIndexChanged = onActiveIndexChanged,
                    onDetails = onDetails,
                    cardLogoUrls = cardLogoUrls,
                    cardImdbRatings = cardImdbRatings
                )
            }
        }
    }
}
