package com.arflix.tv.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AppBottomBarResponsiveLayoutTest {
    @Test fun subscreenFlagsOnlyAffectTheirOwnDestination() {
        assertThat(shouldShowBottomBar(true, "home", false, true, true)).isTrue()
        assertThat(shouldShowBottomBar(true, "settings", false, true, false)).isFalse()
        assertThat(shouldShowBottomBar(true, "tv", false, false, true)).isFalse()
        assertThat(shouldShowBottomBar(true, "details/movie/1", false)).isFalse()
        assertThat(shouldShowBottomBar(true, "collection/1", false)).isFalse()
        assertThat(shouldShowBottomBar(true, "settings-other", false)).isFalse()
    }

    @Test
    fun landscapePhoneUsesFloatingDock() {
        val mode = appBottomBarMode(
            isTouchDevice = true,
            smallestScreenWidthDp = 411,
            screenWidthDp = 892,
            screenHeightDp = 360,
        )

        assertThat(mode).isEqualTo(AppBottomBarMode.LANDSCAPE_FLOATING_DOCK)
    }

    @Test
    fun floatingDockKeepsTheStandardTouchTargetAndIconSize() {
        val spec = appBottomBarSpec(AppBottomBarMode.LANDSCAPE_FLOATING_DOCK)

        assertThat(spec.itemHeightDp).isEqualTo(52)
        assertThat(spec.iconSizeDp).isEqualTo(32)
        assertThat(spec.labelFontSizeSp).isEqualTo(11)
    }

    @Test
    fun floatingDockTracksTheReferenceScreenWidthRatio() {
        for (screenWidthDp in listOf(892, 1280, 1920, 2560)) {
            val ratio = landscapeDockWidthDp(screenWidthDp).toDouble() / screenWidthDp
            assertThat(ratio).isWithin(0.001).of(0.34)
        }
    }

    @Test
    fun floatingDockKeepsSmallScreenTabsTappableWithoutLosingEdgeGaps() {
        assertThat(landscapeDockWidthDp(600)).isEqualTo(280)
        assertThat(landscapeDockWidthDp(360)).isEqualTo(280)
        assertThat(landscapeDockWidthDp(300)).isEqualTo(268)
    }

    @Test
    fun floatingPhoneDockReservesItsHeightGapAndSystemNavigationBeforeMeasurement() {
        val clearance = appBottomBarClearance(
            AppBottomBarMode.LANDSCAPE_FLOATING_DOCK, true, 360, 0f, 24f
        )
        assertThat(clearance).isEqualTo(AppBottomBarClearance(104f, 104f))
    }

    @Test
    fun floatingTabletDockUsesItsResponsiveHeight() {
        val clearance = appBottomBarClearance(
            AppBottomBarMode.LANDSCAPE_FLOATING_DOCK, true, 800, 88f, 24f
        )
        assertThat(clearance).isEqualTo(AppBottomBarClearance(128f, 128f))
    }

    @Test
    fun floatingDockReservesTheMeasuredHeightWhenLargerThanTheFallback() {
        val clearance = appBottomBarClearance(
            AppBottomBarMode.LANDSCAPE_FLOATING_DOCK, true, 360, 80f, 0f
        )
        assertThat(clearance).isEqualTo(AppBottomBarClearance(96f, 96f))
    }

    @Test
    fun finalScrollableRowClearsPersistentDockForPhonesAndTablets() {
        for (height in listOf(320, 360, 800, 1200)) {
            for (navigation in listOf(0f, 24f, 48f)) {
                val dockHeight = landscapeDockHeightDp(height).toFloat()
                val clearance = appBottomBarClearance(
                    AppBottomBarMode.LANDSCAPE_FLOATING_DOCK, true, height, dockHeight, navigation
                )
                val dockTop = height - navigation - LANDSCAPE_DOCK_BOTTOM_GAP_DP - dockHeight
                val finalRowBottom = height - 16f - clearance.scrollInsetDp
                assertThat(finalRowBottom).isAtMost(dockTop - 16f)
            }
        }
    }

    @Test
    fun standardBarDoesNotDoubleCountMeasuredSystemNavigation() {
        val clearance = appBottomBarClearance(AppBottomBarMode.STANDARD, true, 892, 76f, 24f)
        assertThat(clearance).isEqualTo(AppBottomBarClearance(24f, 76f))
    }

    @Test
    fun standardBarKeepsItsExistingFallbackAndScrollInset() {
        val clearance = appBottomBarClearance(AppBottomBarMode.STANDARD, true, 892, 0f, 48f)
        assertThat(clearance).isEqualTo(AppBottomBarClearance(48f, 100f))
    }

    @Test
    fun hiddenBarsLeaveNoNavigationClearanceInEitherMode() {
        for (mode in AppBottomBarMode.values()) {
            assertThat(appBottomBarClearance(mode, false, 800, 88f, 24f))
                .isEqualTo(AppBottomBarClearance(0f, 0f))
        }
    }

    @Test
    fun portraitPhoneKeepsStandardBottomBar() {
        val mode = appBottomBarMode(
            isTouchDevice = true,
            smallestScreenWidthDp = 411,
            screenWidthDp = 411,
            screenHeightDp = 892,
        )

        assertThat(mode).isEqualTo(AppBottomBarMode.STANDARD)
    }

    @Test
    fun landscapeTabletUsesFloatingDock() {
        val mode = appBottomBarMode(
            isTouchDevice = true,
            smallestScreenWidthDp = 700,
            screenWidthDp = 1280,
            screenHeightDp = 800,
        )

        assertThat(mode).isEqualTo(AppBottomBarMode.LANDSCAPE_FLOATING_DOCK)
    }

    @Test
    fun televisionKeepsStandardBottomBar() {
        val mode = appBottomBarMode(
            isTouchDevice = false,
            smallestScreenWidthDp = 720,
            screenWidthDp = 1280,
            screenHeightDp = 720,
        )

        assertThat(mode).isEqualTo(AppBottomBarMode.STANDARD)
    }

    @Test
    fun mobileFirstLaunchCloudConnectShowsBottomBar() {
        val showBar = shouldShowBottomBar(
            isMobile = true,
            currentRoute = "settings?autoCloudAuth=true",
            isFullscreenRoute = false
        )

        assertThat(showBar).isTrue()
    }

    @Test
    fun mobileProfileSelectionHidesBottomBar() {
        val showBar = shouldShowBottomBar(
            isMobile = true,
            currentRoute = com.arflix.tv.navigation.Screen.ProfileSelection.route,
            isFullscreenRoute = false
        )

        assertThat(showBar).isFalse()
    }

    @Test
    fun mobileLoginHidesBottomBar() {
        val showBar = shouldShowBottomBar(
            isMobile = true,
            currentRoute = com.arflix.tv.navigation.Screen.Login.route,
            isFullscreenRoute = false
        )

        assertThat(showBar).isFalse()
    }

    @Test
    fun mobilePlayerFullscreenHidesBottomBar() {
        val showBar = shouldShowBottomBar(
            isMobile = true,
            currentRoute = "player/movie/123",
            isFullscreenRoute = true
        )

        assertThat(showBar).isFalse()
    }

    @Test
    fun nonMobileDeviceHidesBottomBar() {
        val showBar = shouldShowBottomBar(
            isMobile = false,
            currentRoute = "settings?autoCloudAuth=true",
            isFullscreenRoute = false
        )

        assertThat(showBar).isFalse()
    }

    @Test
    fun unresolvedDestinationHidesBottomBar() {
        assertThat(shouldShowBottomBar(true, null, false)).isFalse()
    }

    @Test
    fun settingsDestinationPatternShowsBottomBar() {
        assertThat(shouldShowBottomBar(
            true,
            "settings?autoCloudAuth={autoCloudAuth}&initialSection={initialSection}&installPackUrl={installPackUrl}",
            false
        )).isTrue()
    }

    @Test
    fun liveTvGuideShowsBarButFullscreenPlaybackHidesIt() {
        assertThat(shouldShowBottomBar(true, "tv", false)).isTrue()
        assertThat(shouldShowBottomBar(true, "tv", true)).isFalse()
    }
}
