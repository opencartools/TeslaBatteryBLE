// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.preferredFrameRate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The app's busy spinner: Material's indeterminate indicator in a layer of its own that asks for [SPINNER_FRAME_RATE].
 * A smooth spinner otherwise draws a frame on every display refresh (120 a second on many phones), and each frame
 * re-composites the whole window. On Android 15 and later the display can drop to the requested rate while the spinner
 * is all that moves; older versions ignore the request. The layer keeps a spinner frame from redrawing the screen
 * around it.
 */
@Composable
internal fun BusySpinner(size: Dp) {
    CircularProgressIndicator(
        modifier = Modifier.size(size).preferredFrameRate(SPINNER_FRAME_RATE),
        strokeWidth = 2.dp,
    )
}

/** Frames per second the spinner asks for: smooth enough for a small spinner, a quarter of a 120 Hz display. */
private const val SPINNER_FRAME_RATE = 30f
