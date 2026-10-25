// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
 * The trip's progress bar: the standard linear progress indicator with the arrow on it. A trip that is predicted
 * to end under the reserve is cut where the level crosses it ([lowFrom], a fraction of the whole trip): the first
 * part is the normal indicator, the last one a solid error-colored one, a small gap between them, as the
 * notification's progress style draws it. The end dot marks the destination.
 */
@Composable
internal fun TripBar(
    progress: Float,
    lowFrom: Float?,
    modifier: Modifier = Modifier,
) {
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
    val driven = progress.coerceIn(0f, 1f)
    val split = lowFrom?.coerceIn(0f, 1f)?.takeIf { it > 0f && it < 1f }
    BoxWithConstraints(modifier.fillMaxWidth().height(ICON_SIZE).semantics { contentDescription = description }) {
        Row(Modifier.align(Alignment.Center).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(SEGMENT_GAP)) {
            val indicator = Modifier.height(BAR_HEIGHT)
            if (split == null) {
                LinearProgressIndicator(
                    progress = { driven },
                    modifier = indicator.weight(1f),
                    color = if (lowFrom != null) low else MaterialTheme.colorScheme.primary,
                    trackColor = if (lowFrom != null) low else MaterialTheme.colorScheme.surfaceVariant,
                )
            } else {
                LinearProgressIndicator(
                    progress = { (driven / split).coerceAtMost(1f) },
                    modifier = indicator.weight(split),
                    drawStopIndicator = {},
                )
                LinearProgressIndicator(
                    progress = { 1f },
                    modifier = indicator.weight(1f - split),
                    color = low,
                )
            }
        }
        Image(
            painter = tracker,
            contentDescription = null,
            modifier = Modifier.size(ICON_SIZE).offset(x = ((maxWidth - ICON_SIZE) * driven)),
        )
    }
}

private val ICON_SIZE = 24.dp
private val BAR_HEIGHT = 8.dp
private val SEGMENT_GAP = 4.dp
private const val PERCENT_FULL = 100f
