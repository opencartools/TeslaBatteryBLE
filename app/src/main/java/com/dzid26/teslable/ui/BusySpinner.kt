// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ui

import android.provider.Settings
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.preferredFrameRate
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay

/**
 * The app's busy spinner: a Material arc turned by our own clock at [SPINNER_FPS]. Material's indeterminate indicator
 * animates on the frame clock, so it draws on every display refresh (120 a second on many phones), and each frame
 * re-composites the whole window. Here a coroutine moves the angle [SPINNER_FPS] times a second and the angle is read
 * only in the layer's own properties (no recomposition, no redraw of anything else), so the app draws that often on any
 * phone. The spinner also asks the display for the same rate (Android's requested frame rate): on a display with
 * adaptive refresh the panel can then drop to it too; elsewhere the request is ignored. Nothing ticks while the app is in
 * the background.
 *
 * It reads as "in progress" to TalkBack, stands still when the system's animations are off, and stands still in the
 * Paparazzi snapshots.
 */
@Composable
internal fun BusySpinner(
    size: Dp,
    strokeWidth: Dp = 2.dp,
) {
    val angle = remember { mutableFloatStateOf(0f) }
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val animate =
        !LocalInspectionMode.current &&
            remember { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f }
    if (animate) {
        LaunchedEffect(lifecycle) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    delay(FRAME_MS)
                    angle.floatValue = (angle.floatValue + DEGREES_PER_FRAME) % FULL_TURN
                }
            }
        }
    }
    CircularProgressIndicator(
        progress = { ARC },
        modifier =
            Modifier
                .size(size)
                .preferredFrameRate(SPINNER_FPS.toFloat())
                .graphicsLayer { rotationZ = angle.floatValue }
                .clearAndSetSemantics { progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate },
        strokeWidth = strokeWidth,
        trackColor = Color.Transparent,
    )
}

/** Frames per second the spinner turns at: smooth enough for a small arc, a fifth of a 120 Hz display and a mode many panels have. */
private const val SPINNER_FPS = 24
private const val FRAME_MS = 1000L / SPINNER_FPS
private const val FULL_TURN = 360f

/** One turn every [TURN_MS]. */
private const val TURN_MS = 1000f
private const val DEGREES_PER_FRAME = FULL_TURN * FRAME_MS / TURN_MS

/** The share of the circle the arc covers. */
private const val ARC = 0.3f
