// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.dzid26.teslable.R
import kotlin.math.roundToInt

/**
 * The trip's progress bar: the standard linear progress indicator with the arrow on it, drawn inverted (mirrored, filling
 * the distance still to go) so the part already driven is the dimmed track on the left and the part still to go is in
 * full color on the right. The stop dot is left out, as mirrored it would sit at the start.
 */
@Composable
internal fun TripBar(
    progress: Float,
    modifier: Modifier = Modifier,
) {
    val tracker = painterResource(R.drawable.ic_driving_tracker)
    val description = stringResource(R.string.trip_progress_description, (progress * PERCENT_FULL).roundToInt())
    val driven = progress.coerceIn(0f, 1f)
    BoxWithConstraints(modifier.fillMaxWidth().height(ICON_SIZE).semantics { contentDescription = description }) {
        LinearProgressIndicator(
            progress = { 1f - driven },
            drawStopIndicator = {},
            modifier =
                Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .height(BAR_HEIGHT)
                    .scale(scaleX = -1f, scaleY = 1f),
        )
        Image(
            painter = tracker,
            contentDescription = null,
            modifier = Modifier.size(ICON_SIZE).offset(x = ((maxWidth - ICON_SIZE) * driven)),
        )
    }
}

private val ICON_SIZE = 24.dp
private val BAR_HEIGHT = 8.dp
private const val PERCENT_FULL = 100f
