package com.tbmedtrack.app.ui.components

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Bottom space a scrollable screen must reserve so its last item can scroll fully clear
 * of the floating bottom navigation. Computed dynamically:
 *   nav pill height (64dp) + the nav's own vertical padding (24dp) +
 *   the Android gesture / navigation-bar inset + extra breathing room (extra).
 *
 * This adapts to gesture nav, 3-button nav, notches, and different device sizes.
 */
@Composable
fun bottomNavContentPadding(extra: Dp = 56.dp): Dp {
    val navBarInset = WindowInsets.navigationBars
        .asPaddingValues()
        .calculateBottomPadding()
    return 64.dp + 24.dp + navBarInset + extra
}
