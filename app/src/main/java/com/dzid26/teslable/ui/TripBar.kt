// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.dzid26.teslable.R
import com.dzid26.teslable.TRIP_COLOR_LOW
import com.dzid26.teslable.core.history.TRIP_RESERVE_PERCENT
import kotlin.math.roundToInt

/**
 * The trip's progress bar: the arrow on a track that is drawn in two colors, the theme's color up to where the car predicts
 * the battery falls under the reserve ([lowFrom], a fraction of the whole trip) and the error color from there
 * to the destination. What is driven is solid, what is left is faded, except the low stretch, which stays solid
 * so a trip that ends low is not missed. Without [lowFrom] it is one color.
 */
@Composable
internal fun TripBar(
    progress: Float,
    lowFrom: Float?,
    modifier: Modifier = Modifier,
) {
    val normal = MaterialTheme.colorScheme.primary
    val low = Color(TRIP_COLOR_LOW)
    val tracker = painterResource(R.drawable.ic_driving_tracker)
    val done = (progress * PERCENT_FULL).roundToInt()
    val description =
        if (lowFrom == null) {
            stringResource(R.string.trip_progress_description, done)
        } else {
            stringResource(
                R.string.trip_progress_low_description,
                done,
                TRIP_RESERVE_PERCENT.roundToInt(),
                (lowFrom * PERCENT_FULL).roundToInt(),
            )
        }
    Canvas(modifier = modifier.fillMaxWidth().height(ICON_HEIGHT).semantics { contentDescription = description }) {
        val barHeight = BAR_HEIGHT.toPx()
        val top = (size.height - barHeight) / 2f
        val barSize = Size(size.width, barHeight)
        val radius = CornerRadius(barHeight / 2f)
        val split = lowFrom?.coerceIn(0f, 1f)?.let { it * size.width }
        val driven = progress.coerceIn(0f, 1f) * size.width
        // Each stretch: the faded track (solid for the low one), then the driven part solid on top of it.
        val stretches = listOf(Triple(0f, split ?: size.width, normal), Triple(split ?: size.width, size.width, low))
        for ((from, to, color) in stretches.filter { it.second > it.first }) {
            clipRect(left = from, right = to) {
                drawRoundRect(if (color == low) color else color.copy(alpha = TRACK_ALPHA), Offset(0f, top), barSize, radius)
                clipRect(right = driven) {
                    drawRoundRect(color, Offset(0f, top), barSize, radius)
                }
            }
        }
        // The destination: a dot inside the end of the track.
        drawCircle(if (split != null) low else normal, END_DOT_RADIUS.toPx(), Offset(size.width - barHeight / 2f, size.height / 2f))
        val iconSize = size.height
        val x = (driven - iconSize / 2f).coerceIn(0f, size.width - iconSize)
        translate(left = x) {
            with(tracker) { draw(Size(iconSize, iconSize)) }
        }
    }
}

private val ICON_HEIGHT = 24.dp
private val BAR_HEIGHT = 8.dp
private val END_DOT_RADIUS = 1.5.dp
private const val TRACK_ALPHA = 0.3f
private const val PERCENT_FULL = 100f
