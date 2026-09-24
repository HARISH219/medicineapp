package com.tbmedtrack.app.ui.components

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Bottom space a scrollable screen must reserve so its last item can scroll fully clear
 * of the floating bottom navigation AND the elevated center (+) button.
 *
 * Breakdown (matches [com.tbmedtrack.app.ui.TbMedApp] FloatingBottomNav):
 *   nav pill height (64dp)
 *   + the nav Box vertical padding (12dp top + 12dp bottom = 24dp)
 *   + the raised (+) button that sits ~29dp above the pill's top edge (rounded to 30dp)
 *   + the Android gesture / navigation-bar inset (WindowInsets.navigationBars)
 *   + extra breathing room ([extra]).
 *
 * Dynamic, so it adapts to gesture nav, 3-button nav, notches, and different device sizes.
 * Use this as the bottom contentPadding of any full-screen scrollable that sits under the nav.
 */
@Composable
fun bottomNavContentPadding(extra: Dp = 48.dp): Dp {
    val navBarInset = WindowInsets.navigationBars
        .asPaddingValues()
        .calculateBottomPadding()
    return 64.dp + 24.dp + 30.dp + navBarInset + extra
}
